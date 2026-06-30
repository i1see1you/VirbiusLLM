use crc32fast::Hasher;
use log::{info, warn};
use redis::{Client as RedisClient, Commands};
use std::collections::HashMap;
use std::env;
use std::sync::Arc;
use tokio::sync::RwLock;

/// Resolves the machine-level pool (stable / canary) for this gateway-agent instance.
pub struct NodePoolResolver {
    pub instance_id: String,
    pub bucket: u32,
}

impl NodePoolResolver {
    pub fn from_env() -> Self {
        let instance_id = env::var("VIRBIUS_INSTANCE_ID").unwrap_or_else(|_| {
            let hostname = hostname();
            let pid = std::process::id();
            format!("{}-{}", hostname, pid)
        });
        let bucket = crc32_bucket(&instance_id);
        info!(
            "NodePoolResolver instance={} bucket={}",
            instance_id, bucket
        );
        Self {
            instance_id,
            bucket,
        }
    }

    pub fn resolve_pool(&self, canary_percent: u32) -> &'static str {
        if canary_percent > 0 && self.bucket < canary_percent {
            "canary"
        } else {
            "stable"
        }
    }
}

/// Cached deploy rollout pointer per tenant.
#[derive(Debug, Clone, Default)]
#[allow(dead_code)]
pub struct DeployPointer {
    pub canary_percent: u32,
    pub canary_engine_revision: i64,
    pub stable_engine_revision: i64,
    pub canary_gateway_revision: i64,
    pub stable_gateway_revision: i64,
    pub target_version: String,
}

/// Watches the `virbius:deploy:notify` Redis stream to refresh the local deploy pointer cache.
pub struct DeployRolloutWatcher {
    pub pointers: Arc<RwLock<HashMap<String, DeployPointer>>>,
}

impl DeployRolloutWatcher {
    pub fn new() -> Self {
        Self {
            pointers: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    /// Returns a cached pointer for the tenant, or None.
    pub async fn get(&self, tenant_id: &str) -> Option<DeployPointer> {
        self.pointers.read().await.get(tenant_id).cloned()
    }

    /// Spawns a background task that subscribes to the deploy notify stream.
    pub fn start(&self, redis_url: &str) {
        let pointers = self.pointers.clone();
        let url = redis_url.to_string();
        tokio::spawn(async move {
            loop {
                match RedisClient::open(url.as_str()) {
                    Err(e) => {
                        warn!("deploy watcher: cannot create redis client: {}", e);
                        tokio::time::sleep(tokio::time::Duration::from_secs(5)).await;
                        continue;
                    }
                    Ok(client) => {
                        consume(&client, &pointers).await;
                        tokio::time::sleep(tokio::time::Duration::from_secs(3)).await;
                    }
                }
            }
        });
    }
}

async fn consume(client: &RedisClient, pointers: &Arc<RwLock<HashMap<String, DeployPointer>>>) {
    let mut conn = match client.get_connection() {
        Err(e) => {
            warn!("deploy watcher: connection failed: {}", e);
            return;
        }
        Ok(c) => c,
    };
    let stream_key = "virbius:deploy:notify";
    let group = "gateway-agent";
    let consumer = format!("agent-{}", std::process::id());

    let _: Result<(), _> = redis::cmd("XGROUP")
        .arg("CREATE")
        .arg(stream_key)
        .arg(group)
        .arg("$")
        .arg("MKSTREAM")
        .query(&mut conn);

    loop {
        let results: Result<Vec<(String, Vec<(String, HashMap<String, String>)>)>, _> =
            redis::cmd("XREADGROUP")
                .arg("GROUP")
                .arg(group)
                .arg(&consumer)
                .arg("COUNT")
                .arg(1)
                .arg("BLOCK")
                .arg(5000)
                .arg("STREAMS")
                .arg(stream_key)
                .arg(">")
                .query(&mut conn);

        match results {
            Ok(entries) => {
                for (_stream, msgs) in &entries {
                    for (id, fields) in msgs {
                        if let Some(tenant_id) = fields.get("tenant_id") {
                            refresh_pointer(&mut conn, tenant_id, pointers).await;
                        }
                        let _: Result<(), _> =
                            conn.xack::<_, _, _, ()>(stream_key, group, &[id.as_str()]);
                    }
                }
            }
            Err(e) => {
                warn!("deploy watcher: xreadgroup error: {}", e);
                break;
            }
        }
    }
}

async fn refresh_pointer(
    conn: &mut redis::Connection,
    tenant_id: &str,
    pointers: &Arc<RwLock<HashMap<String, DeployPointer>>>,
) {
    let pointer_key = format!("virbius:deploy:active:{}", tenant_id);
    let hash: Result<HashMap<String, String>, _> = conn.hgetall(&pointer_key);
    match hash {
        Ok(h) => {
            let pct: u32 = h
                .get("canary_percent")
                .and_then(|v| v.parse().ok())
                .unwrap_or(0);
            let ptr = DeployPointer {
                canary_percent: pct,
                canary_engine_revision: h
                    .get("canary_engine_revision")
                    .and_then(|v| v.parse().ok())
                    .unwrap_or(0),
                stable_engine_revision: h
                    .get("stable_engine_revision")
                    .and_then(|v| v.parse().ok())
                    .unwrap_or(0),
                canary_gateway_revision: h
                    .get("canary_gateway_revision")
                    .and_then(|v| v.parse().ok())
                    .unwrap_or(0),
                stable_gateway_revision: h
                    .get("stable_gateway_revision")
                    .and_then(|v| v.parse().ok())
                    .unwrap_or(0),
                target_version: h.get("target_version").cloned().unwrap_or_default(),
            };
            pointers.write().await.insert(tenant_id.to_string(), ptr);
            info!(
                "deploy pointer refreshed tenant={} canary_percent={}",
                tenant_id, pct
            );
        }
        Err(e) => {
            warn!("failed to read deploy pointer for {}: {}", tenant_id, e);
        }
    }
}

/// Periodic heartbeat uploader.
pub async fn heartbeat_loop(
    redis_url: &str,
    resolver: Arc<NodePoolResolver>,
    watcher: Arc<DeployRolloutWatcher>,
) {
    let mut interval = tokio::time::interval(tokio::time::Duration::from_secs(30));
    loop {
        interval.tick().await;
        match RedisClient::open(redis_url) {
            Err(e) => warn!("heartbeat: cannot create redis client: {}", e),
            Ok(client) => {
                let mut conn = match client.get_connection() {
                    Err(e) => {
                        warn!("heartbeat: connection failed: {}", e);
                        continue;
                    }
                    Ok(c) => c,
                };
                let now = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap_or_default()
                    .as_secs();

                let tenants: Vec<String> =
                    { watcher.pointers.read().await.keys().cloned().collect() };
                for tenant in &tenants {
                    let pct = watcher
                        .pointers
                        .read()
                        .await
                        .get(tenant)
                        .map(|p| p.canary_percent)
                        .unwrap_or(0);
                    let pool = resolver.resolve_pool(pct);
                    let hostname = hostname();
                    let key = format!("virbius:nodes:gateway:{}:{}", tenant, resolver.instance_id);
                    let fields = &[
                        ("hostname", hostname.as_str()),
                        ("pid", &std::process::id().to_string()),
                        ("pool", pool),
                        ("last_seen", &now.to_string()),
                    ];
                    let _: Result<(), _> = conn.hset_multiple(&key, fields);
                    let _: Result<(), _> = redis::cmd("EXPIRE").arg(&key).arg(60).query(&mut conn);
                }
            }
        }
    }
}

// ---------------------------------------------------------------

fn crc32_bucket(input: &str) -> u32 {
    let mut h = Hasher::new();
    h.update(input.as_bytes());
    h.finalize() % 100
}

fn hostname() -> String {
    env::var("HOSTNAME").unwrap_or_else(|_| "unknown".into())
}
