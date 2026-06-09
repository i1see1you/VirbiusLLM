use redis::Commands;
use serde::Deserialize;
use std::collections::HashMap;
use std::env;
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

static REDIS_CLIENT: OnceLock<Option<redis::Client>> = OnceLock::new();
static MATCH_CACHE: OnceLock<Mutex<HashMap<String, CacheEntry>>> = OnceLock::new();

#[derive(Clone, Copy)]
struct CacheEntry {
    hit: bool,
    score: f64,
    cached_at: Instant,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct RedisListIndexBlock {
    #[serde(default)]
    pub list_name: String,
    #[serde(default)]
    pub dimension: String,
    #[serde(default)]
    pub redis_key: String,
}

fn redis_client() -> Option<&'static redis::Client> {
    REDIS_CLIENT
        .get_or_init(|| {
            env::var("VIRBIUS_REDIS_URL")
                .ok()
                .filter(|s| !s.is_empty())
                .and_then(|url| redis::Client::open(url).ok())
        })
        .as_ref()
}

fn cache_ttl() -> Duration {
    let sec = env::var("VIRBIUS_LIST_MATCH_CACHE_TTL_SEC")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(60);
    Duration::from_secs(sec.max(1))
}

fn match_cache() -> &'static Mutex<HashMap<String, CacheEntry>> {
    MATCH_CACHE.get_or_init(|| Mutex::new(HashMap::new()))
}

fn is_active(score: f64, now_sec: i64) -> bool {
    if score == 0.0 {
        return true;
    }
    score > now_sec as f64
}

pub fn redis_list_match(tenant_id: &str, list_name: &str, redis_key: &str, lookup_value: &str) -> bool {
    if lookup_value.is_empty() || redis_key.is_empty() {
        return false;
    }
    let cache_key = format!("{tenant_id}:{list_name}:{lookup_value}");
    let now = Instant::now();
    let now_sec = chrono::Utc::now().timestamp();
    if let Ok(guard) = match_cache().lock() {
        if let Some(e) = guard.get(&cache_key) {
            if now.duration_since(e.cached_at) <= cache_ttl() {
                if !(e.hit && e.score > 0.0 && e.score <= now_sec as f64) {
                    return e.hit;
                }
            }
        }
    }
    let Some(client) = redis_client() else {
        return false;
    };
    let score: Option<f64> = client
        .get_connection()
        .ok()
        .and_then(|mut conn| conn.zscore(redis_key, lookup_value).ok())
        .flatten();
    let hit = score.map(|s| is_active(s, now_sec)).unwrap_or(false);
    if let Ok(mut guard) = match_cache().lock() {
        guard.insert(
            cache_key,
            CacheEntry {
                hit,
                score: score.unwrap_or(0.0),
                cached_at: now,
            },
        );
    }
    hit
}

pub fn match_redis_list_by_name(
    tenant_id: &str,
    index: &[RedisListIndexBlock],
    list_name: &str,
    explicit_value: Option<&str>,
    content: &str,
    user_id: Option<&str>,
    device_id: Option<&str>,
    client_ip: Option<&str>,
    vars: &HashMap<String, String>,
) -> bool {
    let Some(block) = index.iter().find(|b| b.list_name == list_name) else {
        return false;
    };
    if block.redis_key.is_empty() {
        return false;
    }
    let dim = block.dimension.as_str();
    if let Some(v) = explicit_value {
        return redis_list_match(tenant_id, list_name, &block.redis_key, v);
    }
    if let Some(logical) = var_logical_name(dim) {
        if let Some(val) = vars.get(logical) {
            if !val.is_empty() {
                return redis_list_match(tenant_id, list_name, &block.redis_key, val);
            }
        }
        return false;
    }
    let lookup = match dim {
        "user_id" => user_id,
        "device_id" => device_id,
        "ip" | "ip_cidr" => client_ip,
        "keyword" | "content" => Some(content),
        _ => None,
    };
    lookup
        .filter(|v| !v.is_empty())
        .map(|v| redis_list_match(tenant_id, list_name, &block.redis_key, v))
        .unwrap_or(false)
}

fn var_logical_name(dim: &str) -> Option<&str> {
    dim.strip_prefix("var:").filter(|s| !s.is_empty())
}
