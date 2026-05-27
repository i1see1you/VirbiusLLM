use serde::Deserialize;
use std::{
    collections::HashMap,
    env,
    fs,
    net::Ipv4Addr,
    path::PathBuf,
    sync::RwLock,
    time::SystemTime,
};

#[derive(Debug, Clone, Default, Deserialize)]
struct ListSide {
    #[serde(default)]
    user_ids: Vec<String>,
    #[serde(default)]
    device_ids: Vec<String>,
    #[serde(default)]
    ip_cidrs: Vec<String>,
    #[serde(default)]
    keywords: Vec<String>,
    #[serde(default)]
    vars: Vec<String>,
}

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
    deny: ListSide,
    #[serde(default)]
    allow: ListSide,
    #[serde(default)]
    context_bindings: ContextBindings,
}

#[derive(Debug, Clone)]
pub struct ListHit {
    pub effective_action: String,
    pub would_block: bool,
    pub reason_code: String,
    pub rule_id: String,
}

pub struct AccessListChecker {
    path: PathBuf,
    cache: RwLock<Option<CachedLists>>,
}

struct CachedLists {
    modified: SystemTime,
    data: ListsFile,
}

impl AccessListChecker {
    pub fn from_env() -> Self {
        let data_dir = env::var("VIRBIUS_DATA_DIR").unwrap_or_else(|_| "./data".into());
        let tenant = env::var("VIRBIUS_TENANT_ID").unwrap_or_else(|_| "default".into());
        let path = env::var("VIRBIUS_GATEWAY_LISTS_PATH").map(PathBuf::from).unwrap_or_else(|_| {
            PathBuf::from(data_dir)
                .join("gateway")
                .join(format!("{tenant}-access-lists.json"))
        });
        Self {
            path,
            cache: RwLock::new(None),
        }
    }

    /// Pre-resolved `provided_vars` wins when non-empty (e.g. from virbius-guard); otherwise parse from query/headers.
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
        content: &str,
        user_id: Option<&str>,
        device_id: Option<&str>,
        client_ip: Option<&str>,
        query: &HashMap<String, String>,
        headers: &HashMap<String, String>,
        provided_vars: Option<&HashMap<String, String>>,
    ) -> Option<ListHit> {
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
        if side_allows(&lists.allow, content, user_id, device_id, client_ip, &vars) {
            return None;
        }
        side_denies(&lists.deny, content, user_id, device_id, client_ip, &vars)
    }

    fn load(&self) -> Result<ListsFile, String> {
        let meta = fs::metadata(&self.path).map_err(|e| e.to_string())?;
        let modified = meta.modified().map_err(|e| e.to_string())?;
        {
            let guard = self.cache.read().map_err(|e| e.to_string())?;
            if let Some(c) = guard.as_ref() {
                if c.modified == modified {
                    return Ok(c.data.clone());
                }
            }
        }
        let raw = fs::read_to_string(&self.path).map_err(|e| e.to_string())?;
        let data: ListsFile = serde_json::from_str(&raw).map_err(|e| e.to_string())?;
        let mut guard = self.cache.write().map_err(|e| e.to_string())?;
        *guard = Some(CachedLists {
            modified,
            data: data.clone(),
        });
        Ok(data)
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

fn side_allows(
    side: &ListSide,
    content: &str,
    user_id: Option<&str>,
    device_id: Option<&str>,
    client_ip: Option<&str>,
    vars: &HashMap<String, String>,
) -> bool {
    if let Some(uid) = user_id {
        if side.user_ids.iter().any(|v| v == uid) {
            return true;
        }
    }
    if let Some(did) = device_id {
        if side.device_ids.iter().any(|v| v == did) {
            return true;
        }
    }
    if let Some(ip) = client_ip {
        if ip_in_any(ip, &side.ip_cidrs) {
            return true;
        }
    }
    if var_list_hit(&side.vars, vars) {
        return true;
    }
    keyword_hit(content, &side.keywords)
}

fn side_denies(
    side: &ListSide,
    content: &str,
    user_id: Option<&str>,
    device_id: Option<&str>,
    client_ip: Option<&str>,
    vars: &HashMap<String, String>,
) -> Option<ListHit> {
    if let Some(uid) = user_id {
        if side.user_ids.iter().any(|v| v == uid) {
            return Some(hit("GW_SUBJECT_USER_DENY", "gw_subject_network_deny"));
        }
    }
    if let Some(did) = device_id {
        if side.device_ids.iter().any(|v| v == did) {
            return Some(hit("GW_SUBJECT_DEVICE_DENY", "gw_subject_network_deny"));
        }
    }
    if let Some(ip) = client_ip {
        if ip_in_any(ip, &side.ip_cidrs) {
            return Some(hit("GW_NETWORK_IP_DENY", "gw_subject_network_deny"));
        }
    }
    if var_list_hit(&side.vars, vars) {
        return Some(hit("GW_CONTEXT_VAR_DENY", "gw_request_param_deny"));
    }
    if keyword_hit(content, &side.keywords) {
        return Some(hit("GW_CONTENT_KEYWORD_DENY", "gw_content_deny"));
    }
    None
}

fn var_list_hit(entries: &[String], vars: &HashMap<String, String>) -> bool {
    entries.iter().any(|entry| {
        let Some((logical, want)) = split_var_entry(entry) else {
            return false;
        };
        vars.get(logical).map(|v| v == want).unwrap_or(false)
    })
}

fn split_var_entry(entry: &str) -> Option<(&str, &str)> {
    let eq = entry.find('=')?;
    if eq == 0 || eq >= entry.len() - 1 {
        return None;
    }
    Some((&entry[..eq], &entry[eq + 1..]))
}

fn hit(reason_code: &str, rule_id: &str) -> ListHit {
    ListHit {
        effective_action: "block".into(),
        would_block: true,
        reason_code: reason_code.into(),
        rule_id: rule_id.into(),
    }
}

fn keyword_hit(content: &str, keywords: &[String]) -> bool {
    if content.is_empty() {
        return false;
    }
    let lower = content.to_ascii_lowercase();
    keywords.iter().any(|kw| {
        if kw.is_empty() {
            return false;
        }
        if kw.chars().any(|c| c > '\u{007f}') {
            content.contains(kw.as_str())
        } else {
            lower.contains(&kw.to_ascii_lowercase())
        }
    })
}

fn ip_in_any(ip: &str, cidrs: &[String]) -> bool {
    let addr: Ipv4Addr = match ip.parse() {
        Ok(a) => a,
        Err(_) => return false,
    };
    let ip_u = u32::from(addr);
    for cidr in cidrs {
        if let Some((net, prefix)) = parse_v4_cidr(cidr) {
            let mask = if prefix == 0 {
                0
            } else {
                !0u32 << (32 - prefix)
            };
            if (ip_u & mask) == (net & mask) {
                return true;
            }
        } else if cidr == ip {
            return true;
        }
    }
    false
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
        let deny = ListSide {
            vars: vec!["app_id=evil".into()],
            ..Default::default()
        };
        assert!(var_list_hit(&deny.vars, &vars));
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

fn parse_v4_cidr(cidr: &str) -> Option<(u32, u32)> {
    let parts: Vec<&str> = cidr.split('/').collect();
    if parts.len() != 2 {
        return None;
    }
    let addr: Ipv4Addr = parts[0].parse().ok()?;
    let prefix: u32 = parts[1].parse().ok()?;
    if prefix > 32 {
        return None;
    }
    Some((u32::from(addr), prefix))
}
