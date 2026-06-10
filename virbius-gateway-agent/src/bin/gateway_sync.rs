//! Gateway artifact sidecar: sync Redis/OSS pointer + blobs → local cache for OpenResty/APISIX.

use redis::Commands;
use reqwest::header::{AUTHORIZATION, IF_NONE_MATCH};
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

const DEFAULT_POLL_SEC: u64 = 10;
const DEFAULT_FIRST_SYNC_TIMEOUT_SEC: u64 = 120;

#[derive(Debug, Deserialize)]
struct PolicyVersion {
    tenant_id: String,
    artifact_revision: i64,
    access_lists_sha256: String,
    scene_registry_sha256: String,
    storage: Option<String>,
}

#[derive(Debug, Deserialize, serde::Serialize)]
struct LocalMeta {
    tenant_id: String,
    artifact_revision: i64,
    access_lists_sha256: String,
    scene_registry_sha256: String,
    synced_at: String,
    node_id: String,
}

fn main() {
    if let Err(e) = run() {
        eprintln!("virbius-gateway-sync: {e}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), String> {
    let tenant = env::var("VIRBIUS_GATEWAY_SYNC_TENANT").unwrap_or_else(|_| "default".into());
    let cache_dir = env::var("VIRBIUS_GATEWAY_CACHE_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from("./data/gateway"));
    let node_id = env::var("VIRBIUS_GATEWAY_NODE_ID").unwrap_or_else(|_| hostname());
    let poll_sec = env::var("VIRBIUS_GATEWAY_SYNC_POLL_SEC")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(DEFAULT_POLL_SEC);
    let source = env::var("VIRBIUS_GATEWAY_SYNC_SOURCE").unwrap_or_else(|_| "redis,http".into());
    let redis_url = env::var("VIRBIUS_REDIS_URL").unwrap_or_else(|_| "redis://127.0.0.1:6379".into());
    let control_base = env::var("VIRBIUS_CONTROL_BASE_URL").unwrap_or_else(|_| "http://127.0.0.1:8080".into());
    let api_key = env::var("VIRBIUS_GATEWAY_SYNC_API_KEY")
        .or_else(|_| env::var("VIRBIUS_EDGE_API_KEY"))
        .ok();
    let pointer_prefix = env::var("VIRBIUS_GATEWAY_POINTER_PREFIX")
        .unwrap_or_else(|_| "virbius:config:gateway".into());
    let ack_prefix = env::var("VIRBIUS_GATEWAY_ACK_PREFIX").unwrap_or_else(|_| "virbius:ack:gateway".into());
    let ack_ttl: u64 = env::var("VIRBIUS_GATEWAY_ACK_TTL_SEC")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(86400);

    fs::create_dir_all(&cache_dir).map_err(|e| format!("mkdir cache: {e}"))?;
    let meta_path = cache_dir.join("meta.json");
    let local_rev = read_local_meta(&meta_path).map(|m| m.artifact_revision).unwrap_or(0);

    if local_rev == 0 {
        blocking_first_sync(
            &tenant,
            &cache_dir,
            &meta_path,
            &node_id,
            &source,
            &redis_url,
            &control_base,
            api_key.as_deref(),
            &pointer_prefix,
            &ack_prefix,
            ack_ttl,
            DEFAULT_FIRST_SYNC_TIMEOUT_SEC,
        )?;
    }

    loop {
        if let Err(e) = sync_once(
            &tenant,
            &cache_dir,
            &meta_path,
            &node_id,
            &source,
            &redis_url,
            &control_base,
            api_key.as_deref(),
            &pointer_prefix,
            &ack_prefix,
            ack_ttl,
        ) {
            eprintln!("virbius-gateway-sync: sync warning: {e}");
        }
        thread::sleep(Duration::from_secs(poll_sec));
    }
}

fn blocking_first_sync(
    tenant: &str,
    cache_dir: &Path,
    meta_path: &Path,
    node_id: &str,
    source: &str,
    redis_url: &str,
    control_base: &str,
    api_key: Option<&str>,
    pointer_prefix: &str,
    ack_prefix: &str,
    ack_ttl: u64,
    timeout_sec: u64,
) -> Result<(), String> {
    let deadline = SystemTime::now() + Duration::from_secs(timeout_sec);
    loop {
        match sync_once(
            tenant,
            cache_dir,
            meta_path,
            node_id,
            source,
            redis_url,
            control_base,
            api_key,
            pointer_prefix,
            ack_prefix,
            ack_ttl,
        ) {
            Ok(true) => return Ok(()),
            Ok(false) => {}
            Err(e) => eprintln!("virbius-gateway-sync: first sync retry: {e}"),
        }
        if SystemTime::now() > deadline {
            return Err("first sync timeout".into());
        }
        thread::sleep(Duration::from_secs(2));
    }
}

fn sync_once(
    tenant: &str,
    cache_dir: &Path,
    meta_path: &Path,
    node_id: &str,
    source: &str,
    redis_url: &str,
    control_base: &str,
    api_key: Option<&str>,
    pointer_prefix: &str,
    ack_prefix: &str,
    ack_ttl: u64,
) -> Result<bool, String> {
    let local_rev = read_local_meta(meta_path)
        .map(|m| m.artifact_revision)
        .unwrap_or(0);
    let policy = fetch_policy(source, tenant, redis_url, control_base, api_key, pointer_prefix)?;
    if policy.artifact_revision <= local_rev {
        return Ok(false);
    }
    let al = fetch_part(
        source,
        tenant,
        &policy,
        "access-lists",
        redis_url,
        control_base,
        api_key,
        pointer_prefix,
    )?;
    let sr = fetch_part(
        source,
        tenant,
        &policy,
        "scene-registry",
        redis_url,
        control_base,
        api_key,
        pointer_prefix,
    )?;
    verify_sha(&al, &policy.access_lists_sha256)?;
    verify_sha(&sr, &policy.scene_registry_sha256)?;
    atomic_write(cache_dir, tenant, "access-lists", &al)?;
    atomic_write(cache_dir, tenant, "scene-registry", &sr)?;
    let synced_at = chrono::Utc::now().to_rfc3339();
    let meta = LocalMeta {
        tenant_id: tenant.to_string(),
        artifact_revision: policy.artifact_revision,
        access_lists_sha256: policy.access_lists_sha256.clone(),
        scene_registry_sha256: policy.scene_registry_sha256.clone(),
        synced_at: synced_at.clone(),
        node_id: node_id.to_string(),
    };
    fs::write(meta_path, serde_json::to_string_pretty(&meta).unwrap())
        .map_err(|e| format!("write meta: {e}"))?;
    write_ack(
        redis_url,
        ack_prefix,
        tenant,
        node_id,
        ack_ttl,
        &policy,
        &synced_at,
        "ok",
        "",
        cache_dir,
    )?;
    eprintln!(
        "virbius-gateway-sync: tenant={tenant} revision={} synced",
        policy.artifact_revision
    );
    Ok(true)
}

fn fetch_policy(
    source: &str,
    tenant: &str,
    redis_url: &str,
    control_base: &str,
    api_key: Option<&str>,
    pointer_prefix: &str,
) -> Result<PolicyVersion, String> {
    if source.contains("redis") {
        if let Ok(p) = policy_from_redis(tenant, redis_url, pointer_prefix) {
            return Ok(p);
        }
    }
    if source.contains("http") {
        return policy_from_http(tenant, control_base, api_key);
    }
    Err("policy-version unavailable".into())
}

fn policy_from_redis(tenant: &str, redis_url: &str, pointer_prefix: &str) -> Result<PolicyVersion, String> {
    let client = redis::Client::open(redis_url).map_err(|e| format!("redis client: {e}"))?;
    let mut conn = client
        .get_connection()
        .map_err(|e| format!("redis connect: {e}"))?;
    let key = format!("{pointer_prefix}:{tenant}");
    let hash: std::collections::HashMap<String, String> = conn
        .hgetall(&key)
        .map_err(|e| format!("hgetall: {e}"))?;
    let rev: i64 = hash
        .get("artifact_revision")
        .ok_or("missing artifact_revision")?
        .parse()
        .map_err(|e| format!("parse revision: {e}"))?;
    Ok(PolicyVersion {
        tenant_id: tenant.to_string(),
        artifact_revision: rev,
        access_lists_sha256: hash
            .get("access_lists_sha256")
            .cloned()
            .unwrap_or_default(),
        scene_registry_sha256: hash
            .get("scene_registry_sha256")
            .cloned()
            .unwrap_or_default(),
        storage: hash.get("storage").cloned(),
    })
}

fn policy_from_http(tenant: &str, control_base: &str, api_key: Option<&str>) -> Result<PolicyVersion, String> {
    let url = format!("{control_base}/api/v1/gateway/tenants/{tenant}/policy-version");
    let client = reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(15))
        .build()
        .map_err(|e| e.to_string())?;
    let mut req = client.get(&url);
    if let Some(key) = api_key {
        req = req.header(AUTHORIZATION, format!("Bearer {key}"));
    }
    let resp = req.send().map_err(|e| format!("http policy: {e}"))?;
    if !resp.status().is_success() {
        return Err(format!("policy-version HTTP {}", resp.status()));
    }
    resp.json::<PolicyVersion>().map_err(|e| format!("policy json: {e}"))
}

fn fetch_part(
    source: &str,
    tenant: &str,
    policy: &PolicyVersion,
    part: &str,
    redis_url: &str,
    control_base: &str,
    api_key: Option<&str>,
    pointer_prefix: &str,
) -> Result<Vec<u8>, String> {
    if source.contains("redis") {
        if let Ok(bytes) = blob_from_redis(tenant, policy, part, redis_url, pointer_prefix) {
            return Ok(bytes);
        }
    }
    if source.contains("http") {
        return blob_from_http(tenant, policy, part, control_base, api_key);
    }
    Err(format!("fetch {part} failed"))
}

fn blob_from_redis(
    tenant: &str,
    policy: &PolicyVersion,
    part: &str,
    redis_url: &str,
    _pointer_prefix: &str,
) -> Result<Vec<u8>, String> {
    let client = redis::Client::open(redis_url).map_err(|e| e.to_string())?;
    let mut conn = client.get_connection().map_err(|e| e.to_string())?;
    let prefix = env::var("VIRBIUS_GATEWAY_BLOB_PREFIX")
        .unwrap_or_else(|_| "virbius:artifacts:gateway".into());
    let blob_key = format!("{}:{}:r{}:{}", prefix, tenant, policy.artifact_revision, part);
    let bytes: Vec<u8> = conn.get(blob_key).map_err(|e| format!("redis get blob: {e}"))?;
    Ok(bytes)
}

fn blob_from_http(
    tenant: &str,
    policy: &PolicyVersion,
    part: &str,
    control_base: &str,
    api_key: Option<&str>,
) -> Result<Vec<u8>, String> {
    let url = format!(
        "{control_base}/api/v1/gateway/tenants/{tenant}/snapshot?part={part}"
    );
    let client = reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(30))
        .build()
        .map_err(|e| e.to_string())?;
    let mut req = client
        .get(&url)
        .header(IF_NONE_MATCH, policy.artifact_revision.to_string());
    if let Some(key) = api_key {
        req = req.header(AUTHORIZATION, format!("Bearer {key}"));
    }
    let resp = req.send().map_err(|e| e.to_string())?;
    if resp.status().as_u16() == 304 {
        return Err("unexpected 304 on snapshot".into());
    }
    if !resp.status().is_success() {
        return Err(format!("snapshot HTTP {}", resp.status()));
    }
    resp.bytes().map(|b| b.to_vec()).map_err(|e| e.to_string())
}

fn verify_sha(body: &[u8], expected: &str) -> Result<(), String> {
    let mut hasher = Sha256::new();
    hasher.update(body);
    let hex = hex::encode(hasher.finalize());
    if !expected.is_empty() && hex != expected.to_lowercase() {
        return Err(format!("sha256 mismatch expected={expected} got={hex}"));
    }
    Ok(())
}

fn atomic_write(cache_dir: &Path, tenant: &str, part: &str, body: &[u8]) -> Result<(), String> {
    let filename = if part == "access-lists" {
        format!("{tenant}-access-lists.json")
    } else {
        format!("{tenant}-scene-registry.json")
    };
    let target = cache_dir.join(&filename);
    let tmp = cache_dir.join(format!("{filename}.tmp"));
    fs::write(&tmp, body).map_err(|e| format!("write tmp: {e}"))?;
    fs::rename(&tmp, &target).map_err(|e| format!("rename: {e}"))?;
    Ok(())
}

fn read_local_meta(path: &Path) -> Option<LocalMeta> {
    let raw = fs::read_to_string(path).ok()?;
    serde_json::from_str(&raw).ok()
}

fn write_ack(
    redis_url: &str,
    ack_prefix: &str,
    tenant: &str,
    node_id: &str,
    ttl: u64,
    policy: &PolicyVersion,
    loaded_at: &str,
    status: &str,
    last_error: &str,
    cache_dir: &Path,
) -> Result<(), String> {
    let client = redis::Client::open(redis_url).map_err(|e| e.to_string())?;
    let mut conn = client.get_connection().map_err(|e| e.to_string())?;
    let key = format!("{ack_prefix}:{tenant}:{node_id}");
    conn.hset::<_, _, _, ()>(&key, "artifact_revision", policy.artifact_revision.to_string())
        .map_err(|e| e.to_string())?;
    conn.hset::<_, _, _, ()>(&key, "loaded_at", loaded_at)
        .map_err(|e| e.to_string())?;
    conn.hset::<_, _, _, ()>(&key, "access_lists_sha256", &policy.access_lists_sha256)
        .map_err(|e| e.to_string())?;
    conn.hset::<_, _, _, ()>(&key, "scene_registry_sha256", &policy.scene_registry_sha256)
        .map_err(|e| e.to_string())?;
    conn.hset::<_, _, _, ()>(&key, "cache_dir", cache_dir.display().to_string())
        .map_err(|e| e.to_string())?;
    conn.hset::<_, _, _, ()>(&key, "status", status)
        .map_err(|e| e.to_string())?;
    conn.hset::<_, _, _, ()>(&key, "last_error", last_error)
        .map_err(|e| e.to_string())?;
    conn.hset::<_, _, _, ()>(&key, "hostname", hostname())
        .map_err(|e| e.to_string())?;
    conn.expire::<_, ()>(&key, ttl as i64).map_err(|e| e.to_string())?;
    Ok(())
}

fn hostname() -> String {
    env::var("HOSTNAME").unwrap_or_else(|_| "gateway-node".into())
}
