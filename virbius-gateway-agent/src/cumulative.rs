use crate::bind_scope::{matches_bind_rule, BindContext};
use crate::policy_engine::{resolve_value, CumulativeDefBlock, IngestTargetDef, ValueSourceDef};
use chrono::{DateTime, NaiveTime, TimeZone, Utc};
use chrono_tz::Tz;
use redis::Client;
use std::collections::{HashMap, HashSet};
use std::env;
use std::sync::{Mutex, OnceLock};

type Slot = i64;
type Count = i64;
type Key = String;

const MAX_WINDOW_MINUTES: i32 = 10080;

static REDIS_CLIENT: OnceLock<Option<Client>> = OnceLock::new();
static MEMORY: Mutex<Option<HashMap<Key, HashMap<Slot, Count>>>> = Mutex::new(None);

fn redis_client() -> Option<&'static Client> {
    REDIS_CLIENT
        .get_or_init(|| {
            env::var("VIRBIUS_REDIS_URL")
                .ok()
                .filter(|s| !s.is_empty())
                .and_then(|url| Client::open(url).ok())
        })
        .as_ref()
}

fn use_redis() -> bool {
    redis_client().is_some()
}

pub struct RequestCtx<'a> {
    pub content: &'a str,
    pub user_id: Option<&'a str>,
    pub device_id: Option<&'a str>,
    pub client_ip: Option<&'a str>,
    pub session_id: Option<&'a str>,
    pub vars: &'a HashMap<String, String>,
}

/** Phase A ingest only (script rules read counts separately). */
pub fn ingest_only(
    tenant_id: &str,
    defs: &[CumulativeDefBlock],
    bind: &BindContext,
    req: &RequestCtx<'_>,
) {
    ingest_all(tenant_id, defs, bind, req);
}

pub fn read_count(tenant_id: &str, def: &CumulativeDefBlock, value: &str) -> Count {
    let Some(w_min) = window_minutes_def(def) else {
        return 0;
    };
    let g = granularity_minutes(w_min, &def.window_kind);
    let end_slot = current_slot(g);
    let key = redis_key(tenant_id, &def.cumulative_name, value);
    read_key_for_window(
        &key,
        &def.window_kind,
        def.timezone.as_ref(),
        w_min,
        g,
        end_slot,
    )
}

fn ingest_all(
    tenant_id: &str,
    defs: &[CumulativeDefBlock],
    bind: &BindContext,
    req: &RequestCtx<'_>,
) {
    let mut seen_keys: HashSet<String> = HashSet::new();
    let mut ordered: Vec<&CumulativeDefBlock> = defs.iter().collect();
    ordered.sort_by_key(|d| d.priority);

    for def in ordered {
        if !should_ingest_def(def, bind) {
            continue;
        }
        let Some(w_min) = window_minutes_def(def) else {
            continue;
        };
        let g = granularity_minutes(w_min, &def.window_kind);
        let slot = current_slot(g);
        let ttl = ttl_seconds(w_min, &def.window_kind);

        for target in &def.ingest_targets {
            let vs = target_as_value_source(target);
            let value = match resolve_value(
                &def.dimension,
                vs.as_ref(),
                req.content,
                req.user_id,
                req.device_id,
                req.client_ip,
                req.session_id,
                req.vars,
            ) {
                Some(v) => v,
                None => continue,
            };
            let key = redis_key(tenant_id, &def.cumulative_name, &value);
            if seen_keys.insert(key.clone()) {
                ingest_key(&key, slot, ttl);
            }
        }
    }
}

fn should_ingest_def(def: &CumulativeDefBlock, bind: &BindContext) -> bool {
    if def.binding_rules.is_empty() {
        return true;
    }
    def.binding_rules
        .iter()
        .any(|r| matches_bind_rule(r, bind))
}

fn target_as_value_source(target: &IngestTargetDef) -> Option<ValueSourceDef> {
    if target.kind.is_empty() || target.kind == "default" {
        return None;
    }
    Some(ValueSourceDef {
        kind: target.kind.clone(),
        r#ref: target.r#ref.clone(),
        value: target.value.clone(),
    })
}

fn window_minutes_def(def: &CumulativeDefBlock) -> Option<i32> {
    if is_calendar_day(&def.window_kind) {
        return Some(1440);
    }
    if let Some(m) = def.window_minutes {
        if m > 0 {
            return Some(m.min(MAX_WINDOW_MINUTES));
        }
    }
    if let Some(h) = def.window_hours {
        if h > 0 {
            return Some((h * 60).min(MAX_WINDOW_MINUTES));
        }
    }
    None
}

fn is_calendar_day(window_kind: &str) -> bool {
    window_kind.eq_ignore_ascii_case("calendar_day")
}

fn granularity_minutes(w_minutes: i32, window_kind: &str) -> i32 {
    if is_calendar_day(window_kind) || w_minutes >= 1440 {
        10
    } else {
        1
    }
}

fn bucket_count(w_minutes: i32, granularity_min: i32) -> i64 {
    ((w_minutes + granularity_min - 1) / granularity_min) as i64
}

fn ttl_seconds(w_minutes: i32, window_kind: &str) -> i64 {
    let w_eff = if is_calendar_day(window_kind) {
        1440
    } else {
        w_minutes
    };
    (w_eff + 120) as i64 * 60
}

fn now_utc() -> DateTime<Utc> {
    Utc::now()
}

fn epoch_secs() -> i64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs() as i64
}

fn current_slot(granularity_min: i32) -> Slot {
    epoch_secs() / (granularity_min as i64 * 60)
}

fn start_slot_calendar_day(now: DateTime<Utc>, zone: Tz, granularity_min: i32) -> Slot {
    let local = now.with_timezone(&zone);
    let date = local.date_naive();
    let start_naive = date.and_time(NaiveTime::from_hms_opt(0, 0, 0).unwrap());
    let start = zone
        .from_local_datetime(&start_naive)
        .single()
        .unwrap_or_else(|| zone.from_utc_datetime(&start_naive.and_utc().naive_utc()));
    start.timestamp() / (granularity_min as i64 * 60)
}

fn parse_zone(tz: Option<&String>) -> Tz {
    tz.and_then(|s| s.parse::<Tz>().ok())
        .unwrap_or(chrono_tz::UTC)
}

fn redis_key(tenant_id: &str, cumulative_name: &str, value: &str) -> Key {
    format!(
        "virbius:cum:{}:{}:{}",
        tenant_id,
        cumulative_name,
        encode_redis_key_segment(value)
    )
}

fn encode_redis_key_segment(value: &str) -> String {
    value.replace(':', "%3A").replace(' ', "%20")
}

fn ingest_key(key: &str, slot: Slot, ttl_sec: i64) {
    if use_redis() {
        if ingest_key_redis(key, slot, ttl_sec) {
            return;
        }
    }
    ingest_key_memory(key, slot);
}

fn ingest_key_redis(key: &str, slot: Slot, ttl_sec: i64) -> bool {
    let Some(client) = redis_client() else {
        return false;
    };
    let Ok(mut conn) = client.get_connection() else {
        return false;
    };
    let field = slot.to_string();
    let incr: Result<i64, _> = redis::cmd("HINCRBY")
        .arg(key)
        .arg(&field)
        .arg(1)
        .query(&mut conn);
    if incr.is_err() {
        return false;
    }
    let _: Result<i64, _> = redis::cmd("EXPIRE")
        .arg(key)
        .arg(ttl_sec)
        .query(&mut conn);
    true
}

fn ingest_key_memory(key: &str, slot: Slot) {
    let mut guard = MEMORY.lock().unwrap();
    let store = guard.get_or_insert_with(HashMap::new);
    let buckets = store.entry(key.to_string()).or_default();
    *buckets.entry(slot).or_insert(0) += 1;
}

fn read_key_for_window(
    key: &str,
    window_kind: &str,
    timezone: Option<&String>,
    w_min: i32,
    g: i32,
    end_slot: Slot,
) -> Count {
    let start_slot = if is_calendar_day(window_kind) {
        let zone = parse_zone(timezone);
        start_slot_calendar_day(now_utc(), zone, g)
    } else {
        let buckets = bucket_count(w_min, g);
        end_slot - buckets + 1
    };
    if start_slot > end_slot {
        return 0;
    }
    if use_redis() {
        if let Some(count) = read_key_redis(key, start_slot, end_slot) {
            return count;
        }
    }
    read_key_memory(key, start_slot, end_slot)
}

fn read_key_redis(key: &str, start_slot: Slot, end_slot: Slot) -> Option<Count> {
    let client = redis_client()?;
    let mut conn = client.get_connection().ok()?;
    if start_slot == end_slot {
        let v: Option<String> = redis::cmd("HGET")
            .arg(key)
            .arg(start_slot.to_string())
            .query(&mut conn)
            .ok()?;
        return Some(parse_count(v));
    }
    let mut cmd = redis::cmd("HMGET");
    cmd.arg(key);
    for slot in start_slot..=end_slot {
        cmd.arg(slot.to_string());
    }
    let vals: Vec<Option<String>> = cmd.query(&mut conn).ok()?;
    Some(vals.iter().map(|v| parse_count(v.clone())).sum())
}

fn parse_count(v: Option<String>) -> Count {
    v.and_then(|s| s.parse().ok()).unwrap_or(0)
}

fn read_key_memory(key: &str, start_slot: Slot, end_slot: Slot) -> Count {
    let guard = MEMORY.lock().unwrap();
    let Some(store) = guard.as_ref() else {
        return 0;
    };
    let Some(buckets) = store.get(key) else {
        return 0;
    };
    (start_slot..=end_slot)
        .map(|s| buckets.get(&s).copied().unwrap_or(0))
        .sum()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::bind_scope::BindContext;
    use crate::policy_engine::CumulativeDefBlock;
    use crate::script::{run_lua_decide, ScriptEnv};

    #[test]
    fn single_ingest_yields_count_for_script_read() {
        let def = CumulativeDefBlock {
            cumulative_name: "user_req_1h".into(),
            dimension: "user_id".into(),
            window_kind: "rolling".into(),
            window_minutes: Some(60),
            ingest_targets: vec![IngestTargetDef {
                kind: "default".into(),
                ..Default::default()
            }],
            binding_rules: vec![crate::bind_scope::BindRuleBlock {
                bind_scope: "global".into(),
                ..Default::default()
            }],
            ..Default::default()
        };
        let bind = BindContext::default();
        let vars = HashMap::new();
        let req = RequestCtx {
            content: "hi",
            user_id: Some("u-1"),
            device_id: None,
            client_ip: None,
            session_id: None,
            vars: &vars,
        };
        if use_redis() {
            return;
        }
        ingest_only("t", std::slice::from_ref(&def), &bind, &req);
        assert_eq!(read_count("t", &def, "u-1"), 1);

        let env = ScriptEnv {
            content: req.content,
            user_id: req.user_id,
            device_id: req.device_id,
            client_ip: req.client_ip,
            session_id: req.session_id,
            vars: req.vars,
            tenant_id: "t",
            lists: &[],
            redis_list_index: &[],
            cumulatives: std::slice::from_ref(&def),
        };
        let body = "function decide(ctx)\n  return getCumulative('user_req_1h') >= 1\nend";
        assert!(run_lua_decide(body, &env).unwrap());
    }

    #[test]
    fn gateway_fixture_chat_route_ingests_global_and_route_cumulatives() {
        use crate::policy_engine::ScriptRuleBlock;

        let raw = include_str!("../testdata/gateway-access-lists.json");
        let v: serde_json::Value = serde_json::from_str(raw).expect("fixture");
        let defs: Vec<CumulativeDefBlock> =
            serde_json::from_value(v.get("cumulatives").cloned().unwrap()).unwrap();
        let lists: Vec<crate::policy_engine::ListDefBlock> =
            serde_json::from_value(v.get("memory_lists").cloned().unwrap_or_default()).unwrap_or_default();
        let script_rules: Vec<ScriptRuleBlock> =
            serde_json::from_value(v.get("script_rules").cloned().unwrap()).unwrap();
        let bind = BindContext {
            route_uri: Some("/v1/chat/completions".into()),
            ..Default::default()
        };
        let vars = HashMap::new();
        let req = RequestCtx {
            content: "hi",
            user_id: Some("u-int"),
            device_id: None,
            client_ip: None,
            session_id: None,
            vars: &vars,
        };
        if use_redis() {
            return;
        }
        ingest_only("default", &defs, &bind, &req);
        let chat_key = redis_key("default", "chat_user_req_1h", "u-int");
        let global_key = redis_key("default", "user_req_1h_global", "u-int");
        let slot = current_slot(1);
        assert_eq!(read_key_memory(&chat_key, slot, slot), 1);
        assert_eq!(read_key_memory(&global_key, slot, slot), 1);

        let env = ScriptEnv {
            content: req.content,
            user_id: req.user_id,
            device_id: req.device_id,
            client_ip: req.client_ip,
            session_id: req.session_id,
            vars: req.vars,
            tenant_id: "default",
            lists: &lists,
            redis_list_index: &[],
            cumulatives: &defs,
        };
        let chat_rule = script_rules.iter().find(|r| r.rule_id == "rl_chat").unwrap();
        assert!(!run_lua_decide(&chat_rule.body, &env).unwrap());

        for _ in 0..4 {
            ingest_only("default", &defs, &bind, &req);
        }
        assert!(run_lua_decide(&chat_rule.body, &env).unwrap());
    }

    #[test]
    fn gateway_fixture_other_route_skips_route_cumulative_ingest() {
        let raw = include_str!("../testdata/gateway-access-lists.json");
        let v: serde_json::Value = serde_json::from_str(raw).expect("fixture");
        let defs: Vec<CumulativeDefBlock> =
            serde_json::from_value(v.get("cumulatives").cloned().unwrap()).unwrap();
        let bind = BindContext {
            route_uri: Some("/v1/other".into()),
            ..Default::default()
        };
        let vars = HashMap::new();
        let req = RequestCtx {
            content: "hi",
            user_id: Some("u-other"),
            device_id: None,
            client_ip: None,
            session_id: None,
            vars: &vars,
        };
        if use_redis() {
            return;
        }
        ingest_all("default", &defs, &bind, &req);
        let slot = current_slot(1);
        assert_eq!(
            read_key_memory(&redis_key("default", "chat_user_req_1h", "u-other"), slot, slot),
            0
        );
        assert_eq!(
            read_key_memory(&redis_key("default", "user_req_1h_global", "u-other"), slot, slot),
            1
        );
    }

    #[test]
    fn ingest_var_app_id_dimension_from_context_vars() {
        let def = CumulativeDefBlock {
            cumulative_name: "app_req_1h".into(),
            dimension: "var:app_id".into(),
            window_kind: "rolling".into(),
            window_minutes: Some(60),
            ingest_targets: vec![IngestTargetDef {
                kind: "default".into(),
                ..Default::default()
            }],
            binding_rules: vec![crate::bind_scope::BindRuleBlock {
                bind_scope: "global".into(),
                ..Default::default()
            }],
            ..Default::default()
        };
        let bind = BindContext::default();
        let mut vars = HashMap::new();
        vars.insert("app_id".into(), "acme".into());
        let req = RequestCtx {
            content: "hi",
            user_id: Some("u1"),
            device_id: None,
            client_ip: None,
            session_id: None,
            vars: &vars,
        };
        if use_redis() {
            return;
        }
        ingest_all("default", std::slice::from_ref(&def), &bind, &req);
        let slot = current_slot(1);
        assert_eq!(
            read_key_memory(&redis_key("default", "app_req_1h", "acme"), slot, slot),
            1
        );
        let empty_vars = HashMap::new();
        let req_skip = RequestCtx {
            content: "hi",
            user_id: Some("u1"),
            device_id: None,
            client_ip: None,
            session_id: None,
            vars: &empty_vars,
        };
        ingest_all("default", std::slice::from_ref(&def), &bind, &req_skip);
        assert_eq!(
            read_key_memory(&redis_key("default", "app_req_1h", "acme"), slot, slot),
            1
        );
    }
}
