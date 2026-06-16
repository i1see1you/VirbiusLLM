use crate::bind_scope::BindContext;
use crate::cumulative::{self, RequestCtx};
use crate::enforce::{self, GatewayCheckResult};
use crate::policy_engine::{hit_from_script_rule, ScriptRuleBlock, CumulativeDefBlock, ListDefBlock};
use crate::script::{run_lua_decide, ScriptEnv};
use redis::Commands;
use serde::Deserialize;
use std::{
    collections::HashMap,
    env,
    fs,
    path::PathBuf,
    sync::RwLock,
    time::{Duration, Instant},
};

#[derive(Debug, Clone, Default, Deserialize)]
struct VarDef {
    #[serde(default)]
    from: String,
    #[serde(default)]
    name: Option<String>,
    #[serde(default)]
    field: Option<String>,
}

#[derive(Debug, Clone, Default, Deserialize)]
struct ContextBindings {
    #[serde(default)]
    vars: HashMap<String, VarDef>,
}

#[derive(Debug, Clone, Deserialize)]
struct ListsFile {
    #[serde(default)]
    tenant_id: String,
    #[serde(default)]
    memory_lists: Vec<ListDefBlock>,
    #[serde(default)]
    redis_list_index: Vec<crate::list_redis::RedisListIndexBlock>,
    #[serde(default)]
    cumulatives: Vec<CumulativeDefBlock>,
    #[serde(default)]
    script_rules: Vec<ScriptRuleBlock>,
    #[serde(default)]
    context_bindings: ContextBindings,
}

impl ListsFile {
    fn memory_lists(&self) -> &[ListDefBlock] {
        &self.memory_lists
    }
}

pub struct AccessListChecker {
    cache: RwLock<Option<CachedLists>>,
    file_path: PathBuf,
    redis_url: String,
    tenant: String,
    pointer_prefix: String,
    blob_prefix: String,
    poll_sec: u64,
}

struct CachedLists {
    revision: i64,
    data: ListsFile,
    checked_at: Instant,
}

impl AccessListChecker {
    pub fn from_env() -> Self {
        let data_dir = env::var("VIRBIUS_DATA_DIR").unwrap_or_else(|_| "./data".into());
        let tenant = env::var("VIRBIUS_TENANT_ID").unwrap_or_else(|_| "default".into());
        let file_path = env::var("VIRBIUS_GATEWAY_LISTS_PATH").map(PathBuf::from).unwrap_or_else(|_| {
            PathBuf::from(&data_dir)
                .join("gateway")
                .join(format!("{tenant}-access-lists.json"))
        });
        let redis_url = env::var("VIRBIUS_REDIS_URL").unwrap_or_else(|_| "redis://127.0.0.1:6379".into());
        let pointer_prefix = env::var("VIRBIUS_GATEWAY_POINTER_PREFIX")
            .unwrap_or_else(|_| "virbius:config:gateway".into());
        let blob_prefix = env::var("VIRBIUS_GATEWAY_BLOB_PREFIX")
            .unwrap_or_else(|_| "virbius:artifacts:gateway".into());
        let poll_sec = env::var("VIRBIUS_GATEWAY_SYNC_POLL_SEC")
            .ok()
            .and_then(|s| s.parse().ok())
            .unwrap_or(10);

        let initial = Self::fetch_from_redis(&redis_url, &tenant, &pointer_prefix, &blob_prefix)
            .or_else(|_| Self::fetch_from_file(&file_path))
            .ok();

        let cache = initial.map(|(rev, data)| CachedLists {
            revision: rev,
            data,
            checked_at: Instant::now(),
        });

        Self {
            cache: RwLock::new(cache),
            file_path,
            redis_url,
            tenant,
            pointer_prefix,
            blob_prefix,
            poll_sec,
        }
    }

    pub fn effective_vars(
        &self,
        provided_vars: Option<&HashMap<String, String>>,
        user_id: Option<&str>,
        device_id: Option<&str>,
        client_ip: Option<&str>,
        query: &HashMap<String, String>,
        headers: &HashMap<String, String>,
    ) -> HashMap<String, String> {
        let lists = match self.load() {
            Ok(l) => l,
            Err(_) => return HashMap::new(),
        };
        effective_vars_map(
            provided_vars,
            &lists.context_bindings,
            user_id,
            device_id,
            client_ip,
            query,
            headers,
        )
    }

    pub fn check(
        &self,
        bind: &BindContext,
        content: &str,
        user_id: Option<&str>,
        device_id: Option<&str>,
        client_ip: Option<&str>,
        session_id: Option<&str>,
        query: &HashMap<String, String>,
        headers: &HashMap<String, String>,
        provided_vars: Option<&HashMap<String, String>>,
    ) -> Option<GatewayCheckResult> {
        let lists = self.load().ok()?;
        let vars = effective_vars_map(
            provided_vars,
            &lists.context_bindings,
            user_id,
            device_id,
            client_ip,
            query,
            headers,
        );
        let req = RequestCtx {
            content,
            user_id,
            device_id,
            client_ip,
            session_id,
            vars: &vars,
        };
        let tenant_env = env::var("VIRBIUS_TENANT_ID").unwrap_or_else(|_| "default".into());
        let tenant = if lists.tenant_id.is_empty() {
            tenant_env.as_str()
        } else {
            lists.tenant_id.as_str()
        };

        cumulative::ingest_only(tenant, &lists.cumulatives, bind, &req);

        let mut hits = Vec::new();
        for rule in &lists.script_rules {
            if !crate::bind_scope::matches_bind(&rule.bind_scope, rule.bind_ref.as_ref(), bind) {
                continue;
            }
            let env = ScriptEnv {
                content,
                user_id,
                device_id,
                client_ip,
                session_id,
                vars: &vars,
                tenant_id: tenant,
                lists: lists.memory_lists(),
                redis_list_index: &lists.redis_list_index,
                cumulatives: &lists.cumulatives,
            };
            match run_lua_decide(&rule.body, &env) {
                Ok(true) => hits.push(hit_from_script_rule(rule)),
                Ok(false) => {}
                Err(e) => {
                    eprintln!("lua script {} failed: {}", rule.rule_id, e);
                }
            }
        }

        if hits.is_empty() {
            return None;
        }
        let merged = enforce::merge(&hits, session_id);
        Some(merged)
    }

    fn load(&self) -> Result<ListsFile, String> {
        {
            let guard = self.cache.read().map_err(|e| e.to_string())?;
            if let Some(c) = guard.as_ref() {
                if c.checked_at.elapsed() < Duration::from_secs(self.poll_sec) {
                    return Ok(c.data.clone());
                }
            }
        }

        if let Ok((rev, fresh)) = Self::fetch_from_redis(
            &self.redis_url,
            &self.tenant,
            &self.pointer_prefix,
            &self.blob_prefix,
        ) {
            let mut guard = self.cache.write().map_err(|e| e.to_string())?;
            let changed = guard.as_ref().map_or(true, |c| c.revision != rev);
            if changed {
                *guard = Some(CachedLists {
                    revision: rev,
                    data: fresh.clone(),
                    checked_at: Instant::now(),
                });
            } else if let Some(c) = guard.as_mut() {
                c.checked_at = Instant::now();
            }
            return Ok(fresh);
        }

        if let Ok((_, file_data)) = Self::fetch_from_file(&self.file_path) {
            let mut guard = self.cache.write().map_err(|e| e.to_string())?;
            if guard.is_none() {
                *guard = Some(CachedLists {
                    revision: 0,
                    data: file_data.clone(),
                    checked_at: Instant::now(),
                });
            }
            return Ok(file_data);
        }

        let guard = self.cache.read().map_err(|e| e.to_string())?;
        guard
            .as_ref()
            .map(|c| c.data.clone())
            .ok_or_else(|| "no data available".into())
    }

    fn fetch_from_redis(
        redis_url: &str,
        tenant: &str,
        pointer_prefix: &str,
        blob_prefix: &str,
    ) -> Result<(i64, ListsFile), String> {
        let client =
            redis::Client::open(redis_url).map_err(|e| format!("redis client: {e}"))?;
        let mut conn = client.get_connection().map_err(|e| format!("redis connect: {e}"))?;

        let pointer_key = format!("{pointer_prefix}:{tenant}");
        let hash: HashMap<String, String> = conn
            .hgetall(&pointer_key)
            .map_err(|e| format!("hgetall: {e}"))?;

        let rev: i64 = hash
            .get("artifact_revision")
            .ok_or("missing artifact_revision")?
            .parse()
            .map_err(|e| format!("parse revision: {e}"))?;

        let al_key = format!("{blob_prefix}:{tenant}:r{rev}:access-lists");
        let raw: Vec<u8> = conn.get(&al_key).map_err(|e| format!("redis get blob: {e}"))?;
        let data: ListsFile =
            serde_json::from_slice(&raw).map_err(|e| format!("parse json: {e}"))?;

        Ok((rev, data))
    }

    fn fetch_from_file(path: &PathBuf) -> Result<(i64, ListsFile), String> {
        let raw = fs::read_to_string(path).map_err(|e| format!("read file: {e}"))?;
        let data: ListsFile =
            serde_json::from_str(&raw).map_err(|e| format!("parse json: {e}"))?;
        Ok((0, data))
    }
}

fn effective_vars_map(
    provided: Option<&HashMap<String, String>>,
    bindings: &ContextBindings,
    user_id: Option<&str>,
    device_id: Option<&str>,
    client_ip: Option<&str>,
    query: &HashMap<String, String>,
    headers: &HashMap<String, String>,
) -> HashMap<String, String> {
    if let Some(v) = provided {
        if !v.is_empty() {
            return v.clone();
        }
    }
    resolve_vars(bindings, user_id, device_id, client_ip, query, headers)
}

fn resolve_vars(
    bindings: &ContextBindings,
    user_id: Option<&str>,
    device_id: Option<&str>,
    client_ip: Option<&str>,
    query: &HashMap<String, String>,
    headers: &HashMap<String, String>,
) -> HashMap<String, String> {
    let mut out = HashMap::new();
    for (logical, def) in &bindings.vars {
        let val = match def.from.as_str() {
            "query" => def.name.as_ref().and_then(|n| query.get(n)).cloned(),
            "header" => {
                let n = def.name.as_deref().unwrap_or("");
                let key = n.to_ascii_lowercase();
                headers
                    .get(&key)
                    .or_else(|| headers.get(n))
                    .cloned()
            }
            "subject" => match def.field.as_deref() {
                Some("user_id") => user_id.map(|s| s.to_string()),
                Some("device_id") => device_id.map(|s| s.to_string()),
                _ => None,
            },
            "network" => match def.field.as_deref() {
                Some("ip") => client_ip.map(|s| s.to_string()),
                _ => None,
            },
            _ => None,
        };
        if let Some(v) = val {
            if !v.is_empty() {
                out.insert(logical.clone(), v);
            }
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn provided_vars_used_for_var_deny_without_query() {
        let bindings = ContextBindings {
            vars: HashMap::from([(
                "app_id".into(),
                VarDef {
                    from: "header".into(),
                    name: Some("X-App-Id".into()),
                    field: None,
                },
            )]),
        };
        let mut provided = HashMap::new();
        provided.insert("app_id".into(), "evil".into());
        let query = HashMap::new();
        let headers = HashMap::new();
        let vars = effective_vars_map(Some(&provided), &bindings, None, None, None, &query, &headers);
        assert_eq!(vars.get("app_id").map(String::as_str), Some("evil"));
    }

    #[test]
    fn empty_provided_vars_falls_back_to_query() {
        let bindings = ContextBindings {
            vars: HashMap::from([(
                "debug_flag".into(),
                VarDef {
                    from: "query".into(),
                    name: Some("debug".into()),
                    field: None,
                },
            )]),
        };
        let provided = HashMap::new();
        let mut query = HashMap::new();
        query.insert("debug".into(), "1".into());
        let headers = HashMap::new();
        let vars = effective_vars_map(Some(&provided), &bindings, None, None, None, &query, &headers);
        assert_eq!(vars.get("debug_flag").map(String::as_str), Some("1"));
    }
}
