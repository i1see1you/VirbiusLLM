# 网关产物：Redis 对象存储 + Config Bus 指针

> 阶段 B 落地细表：**Pointer/Blob Redis 契约**、**Admin HTTP API（OpenAPI 草案）**、**gateway-sync Sidecar 状态机**。  
> 与 [DESIGN.md §8.10](../DESIGN.md)、[openresty-gateway.md](./openresty-gateway.md) 对齐；**本文档为方案，实现前以代码为准**。

---

## 1. 范围

| 项 | 说明 |
|----|------|
| 对象存储（首期） | Redis STRING Blob，不可变 revision |
| Config Bus（首期） | Redis HASH Pointer + Redis Stream 通知 |
| 消费者 | **gateway-sync** Sidecar → 本地 cache → OpenResty/APISIX `lists_file` |
| 不在范围 | Edge manifest Redis 化（可复用同一模式，另文） |

---

## 2. Redis Key 与字段（Normative）

### 2.1 Blob（对象存储）

| Key | 类型 | TTL | 说明 |
|-----|------|-----|------|
| `virbius:artifacts:gateway:{tenant}:r{rev}:access-lists:staging` | STRING | 无（发布完删除或 RENAME） | 写入中 |
| `virbius:artifacts:gateway:{tenant}:r{rev}:access-lists` | STRING | 可选 `RETAIN_DAYS` | `{tenant}-access-lists.json` 字节 |
| `virbius:artifacts:gateway:{tenant}:r{rev}:scene-registry:staging` | STRING | 同上 | 写入中 |
| `virbius:artifacts:gateway:{tenant}:r{rev}:scene-registry` | STRING | 同上 | scene-registry JSON 字节 |
| `virbius:artifacts:gateway:{tenant}:history` | ZSET | 无 | member=`{rev}` score=`published_at` unix |

**可选扩展 Blob**（单包 >4MB 或 script_rules 过大时）：

| Key | 说明 |
|-----|------|
| `virbius:artifacts:gateway:{tenant}:r{rev}:script-rules` | 仅 `script_rules[]` JSON 数组 |

Blob 内容为 **UTF-8 JSON 文本**（与当前 `ArtifactService` 写盘格式一致），非压缩；后续可加 `content_encoding=gzip` 字段。

### 2.2 Config Bus Pointer

| Key | 类型 | 说明 |
|-----|------|------|
| `virbius:config:gateway:{tenant}:seq` | STRING | 单调递增 revision 生成器 |
| `virbius:config:gateway:{tenant}` | HASH | 当前生效指针（见 §2.3） |

### 2.3 Pointer HASH 字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `artifact_revision` | string (int64) | ✅ | 与 `seq` 当前值一致 |
| `published_at` | string (ISO-8601 UTC) | ✅ | 发布时间 |
| `bundle_id` | string | | PoC `poc-default` |
| `bundle_version` | string | | PoC `0.1.0` |
| `schema_version` | string | ✅ | access-lists JSON 内 `schema_version`，当前 `"2"` |
| `storage` | string | ✅ | 首期固定 `redis`；后期 `s3` |
| `access_lists_key` | string | ✅* | `storage=redis` 时 Blob Key |
| `scene_registry_key` | string | ✅* | 同上 |
| `access_lists_sha256` | string (64 hex) | ✅ | 小写 hex |
| `scene_registry_sha256` | string (64 hex) | ✅ | 小写 hex |
| `access_lists_url` | string | | `storage=s3` 时 HTTPS URL |
| `scene_registry_url` | string | | 同上 |
| `access_lists_bytes` | string (int) | | 字节长度，Sidecar 预分配/校验 |
| `scene_registry_bytes` | string (int) | | 同上 |
| `script_rules_key` | string | | 可选拆分 Blob |
| `script_rules_sha256` | string | | 可选 |
| `publish_id` | string (uuid) | | 与 rollout/sync 关联 |
| `trigger` | string | | `rollout` \| `access_list` \| `cumulative` \| `manual` \| `bootstrap` |

\* `storage=redis` 时必填 Key；`storage=s3` 时必填 URL。

**Pointer 示例（redis）**：

```json
{
  "artifact_revision": "42",
  "published_at": "2026-05-20T12:00:00Z",
  "bundle_id": "poc-default",
  "bundle_version": "0.1.0",
  "schema_version": "2",
  "storage": "redis",
  "access_lists_key": "virbius:artifacts:gateway:default:r42:access-lists",
  "scene_registry_key": "virbius:artifacts:gateway:default:r42:scene-registry",
  "access_lists_sha256": "a1b2c3...",
  "scene_registry_sha256": "d4e5f6...",
  "access_lists_bytes": "18342",
  "scene_registry_bytes": "1204",
  "publish_id": "550e8400-e29b-41d4-a716-446655440000",
  "trigger": "rollout"
}
```

### 2.4 变更通知 Stream

| Key | 类型 | 说明 |
|-----|------|------|
| `virbius:config:events` | Stream | 全局配置变更流 |

**XADD 字段**（单次 gateway 发布）：

| 字段 | 说明 |
|------|------|
| `layer` | `gateway` |
| `tenant_id` | |
| `artifact_revision` | |
| `published_at` | |
| `access_lists_sha256` | |
| `trigger` | |

**Consumer group**：`virbius-gateway-sync`（Sidecar 启动时 `XGROUP CREATE` MKSTREAM）。

### 2.5 节点 ACK

| Key | 类型 | 说明 |
|-----|------|------|
| `virbius:ack:gateway:{tenant}:{node_id}` | HASH | Sidecar 上报 |

| 字段 | 说明 |
|------|------|
| `artifact_revision` | 已成功加载的 revision |
| `loaded_at` | ISO-8601 |
| `access_lists_sha256` | 本地校验值 |
| `scene_registry_sha256` | |
| `cache_dir` | 本地目录 |
| `status` | `ok` \| `degraded`（用旧 revision）\| `error` |
| `last_error` | 最近一次失败原因 |
| `hostname` | 可选 |

**TTL**：ACK Key 建议 **24h** 滑动刷新；Sidecar 每成功 sync 一次 `EXPIRE`。

---

## 3. 发布原子性（Lua 语义）

脚本名建议：`virbius_gateway_publish.lua`。

```text
INPUT: tenant, access_lists_body, scene_registry_body, metadata_hash_fields...

1. rev = INCR virbius:config:gateway:{tenant}:seq
2. SET staging keys (access-lists, scene-registry)
3. RENAME staging → live (两个 Blob)
4. HSET virbius:config:gateway:{tenant} (全部 Pointer 字段)
5. ZADD history rev now
6. ZREMRANGEBYRANK history 0 -(RETAIN_K+1)  -- 保留最近 K 个 rev
7. 对被淘汰 rev 的 Blob Key  DEL（异步亦可）
8. XADD virbius:config:events ...
9. RETURN rev
```

**失败回滚**：任一步失败则不执行步骤 4–8；staging Key `DEL`。

---

## 4. Admin HTTP API（OpenAPI 草案）

> 拟纳入 `registry.openapi.yaml` **v1.7.0**；gateway 拉取面为 **裸 JSON**（与 Edge 一致），Admin 查询为 **ApiResult**。

### 4.1 Tag

```yaml
tags:
  - name: gateway-delivery
    description: 网关产物版本探测与快照拉取（裸 JSON；Redis 后端）
  - name: admin-gateway-artifacts
    description: 网关产物发布状态与节点 ACK（Admin）
```

### 4.2 路径

#### `GET /api/v1/gateway/tenants/{tenantId}/policy-version`

与 Edge `policy-version` 对称；Sidecar **可**用此 API 替代直接读 Redis Pointer（运维友好、可走 mTLS）。

| 项 | 值 |
|----|-----|
| Auth | `virbiusApiKeyBearer`（`tenant_viewer`+）；auth 关闭可省略 |
| Response | **裸 JSON** |

**Response 200 — `GatewayPolicyVersion`**：

```yaml
GatewayPolicyVersion:
  type: object
  required:
    - tenant_id
    - artifact_revision
    - access_lists_sha256
    - scene_registry_sha256
    - published_at
    - storage
  properties:
    tenant_id:
      type: string
    artifact_revision:
      type: integer
      format: int64
    access_lists_sha256:
      type: string
      description: SHA-256 hex of access-lists blob bytes
    scene_registry_sha256:
      type: string
    published_at:
      type: string
      format: date-time
    storage:
      type: string
      enum: [redis, s3]
    bundle_id:
      type: string
    bundle_version:
      type: string
    schema_version:
      type: string
      example: "2"
    # storage=redis 时 control 内网可读；对外 API 可不返回 key，仅 sha256
    access_lists_locator:
      type: string
      description: redis key 或 https url（视 storage）
    scene_registry_locator:
      type: string
```

**Errors**：401/403 同 Edge；404 无 meta。

---

#### `GET /api/v1/gateway/tenants/{tenantId}/snapshot`

条件下载网关快照（Sidecar 拉 body；等同 Redis GET Blob）。

| Header | 说明 |
|--------|------|
| `If-None-Match` | 期望 `artifact_revision` 字符串 |
| `Authorization` | Bearer 或 `X-Virbius-Api-Key` |

**Query**：

| 参数 | 必填 | 说明 |
|------|------|------|
| `part` | | `access-lists`（默认）\| `scene-registry` \| `all` |

**Response 200**（`part=access-lists`）：`application/json` body = 原 `{tenant}-access-lists.json` 内容。

**Headers**：

| Header | 说明 |
|--------|------|
| `ETag` | `"42"`（revision 字符串） |
| `X-Content-Sha256` | hex |

**304**：`If-None-Match` 与当前 revision 一致。

**`part=all`**：multipart 或 JSON 包装（实现二选一；建议 **两次 GET** 更简单，不用 `all`）。

---

#### `GET /api/v1/admin/tenants/{tenantId}/gateway-artifacts/policy-version`

Admin 包装版；data 同 `GatewayPolicyVersion` + 发布元数据。

```yaml
GatewayArtifactAdminDetail:
  allOf:
    - $ref: '#/components/schemas/GatewayPolicyVersion'
    - type: object
      properties:
        publish_id:
          type: string
        trigger:
          type: string
        nodes_ok:
          type: integer
        nodes_total:
          type: integer
        nodes_pending:
          type: array
          items:
            type: string
```

---

#### `GET /api/v1/admin/tenants/{tenantId}/gateway-artifacts/nodes`

列出 `virbius:ack:gateway:{tenant}:*` 聚合结果。

**Response data**：

```yaml
GatewayNodeAck:
  type: object
  properties:
    node_id:
      type: string
    artifact_revision:
      type: integer
      format: int64
    loaded_at:
      type: string
      format: date-time
    status:
      type: string
      enum: [ok, degraded, error]
    last_error:
      type: string
```

---

#### `POST /api/v1/admin/tenants/{tenantId}/gateway-artifacts/refresh`

手动触发 `refreshArtifacts` + Redis publish（等同 `sync-rules` 的 gateway 部分）；须 `tenant_admin`+。

**Response data**：

```yaml
GatewayPublishResult:
  type: object
  required: [artifact_revision, storage, pointer_key]
  properties:
    artifact_revision:
      type: integer
      format: int64
    storage:
      type: string
    pointer_key:
      type: string
    access_lists_sha256:
      type: string
    scene_registry_sha256:
      type: string
    local_fallback_written:
      type: boolean
      description: 是否双写本地 data/gateway（过渡配置）
```

---

### 4.3 securitySchemes

复用 `virbiusApiKeyBearer`。`/api/v1/gateway/**` 与 Edge 相同：`tenant_viewer` 可读 snapshot；Pointer 读路径 tenant 须匹配。

---

### 4.4 DB 表 `tb_gateway_artifact_meta`

| 列 | 类型 | 说明 |
|----|------|------|
| `tenant_id` | VARCHAR PK | |
| `artifact_revision` | BIGINT | |
| `access_lists_sha256` | VARCHAR(64) | |
| `scene_registry_sha256` | VARCHAR(64) | |
| `published_at` | TIMESTAMP | |
| `publish_id` | VARCHAR(36) | 可空 |
| `trigger` | VARCHAR(32) | 可空 |

`policy-version` API 优先读 DB；Redis Pointer 为运行时真源，发布成功后 **双写** DB。

---

## 5. sync_ack 扩展（PublishOrchestrator / refresh 响应）

嵌入现有 `refreshArtifacts` / rollout 副作用响应：

```json
{
  "refreshed": true,
  "artifacts": {
    "gateway_redis": {
      "storage": "redis",
      "artifact_revision": 42,
      "pointer_key": "virbius:config:gateway:default",
      "access_lists_sha256": "...",
      "scene_registry_sha256": "..."
    },
    "gateway_local": "/data/gateway/default-access-lists.json"
  },
  "engine_reload": { "...": "..." },
  "gateway_sync_ack": {
    "nodes_ok": 2,
    "nodes_total": 3,
    "nodes_pending": ["openresty-pod-3"]
  }
}
```

MVP-OPENSPEC §6.3 映射：

```json
"rules_artifact": {
  "storage": "redis",
  "artifact_revision": 42,
  "nodes_ok": 2
}
```

---

## 6. gateway-sync Sidecar 状态机

### 6.1 状态一览

| 状态 | 含义 |
|------|------|
| `INIT` | 进程启动，读配置 |
| `LOAD_LOCAL` | 读 `{cache_dir}/meta.json` + 已有 JSON |
| `SYNC` | 拉取远程 revision / body |
| `VERIFY` | sha256 校验 |
| `WRITE` | 原子写本地文件 |
| `ACK` | 写 Redis ACK |
| `IDLE` | 等待 poll 间隔或 Stream 事件 |
| `DEGRADED` | 远程失败，继续用本地旧 revision |
| `STOP` | 收到 SIGTERM |

### 6.2 转移表

| 从 | 事件 | 到 | 动作 |
|----|------|-----|------|
| INIT | config ok | LOAD_LOCAL | 创建 cache_dir |
| LOAD_LOCAL | meta 存在 | IDLE | `local_revision` = meta.rev |
| LOAD_LOCAL | meta 不存在 | SYNC | `local_revision` = 0 |
| IDLE | timer / stream msg | SYNC | 记录 `target_hint_rev` |
| SYNC | remote rev ≤ local | IDLE | 无操作 |
| SYNC | remote rev > local | VERIFY | GET Blob 或 HTTP snapshot |
| VERIFY | sha256 ok | WRITE | |
| VERIFY | sha256 fail | DEGRADED | log；**不**改本地文件 |
| WRITE | rename ok | ACK | 写 meta.json |
| WRITE | io error | DEGRADED | |
| ACK | redis ok | IDLE | `local_revision` = remote |
| ACK | redis fail | IDLE | 本地已更新；下轮重试 ACK |
| SYNC | network/redis error | DEGRADED | 若 local_revision>0 |
| SYNC | network error & local=0 | SYNC | 退避重试；**blocking** 首启 |
| * | SIGTERM | STOP | 刷 ACK status=stopped |
| DEGRADED | timer | SYNC | 重试 |
| DEGRADED | sync success | IDLE | 离开 degraded |

### 6.3 本地 meta.json

路径：`{VIRBIUS_GATEWAY_CACHE_DIR}/meta.json`

```json
{
  "tenant_id": "default",
  "artifact_revision": 42,
  "access_lists_sha256": "...",
  "scene_registry_sha256": "...",
  "synced_at": "2026-05-20T12:00:05Z",
  "node_id": "openresty-pod-1"
}
```

### 6.4 本地产物路径（固定）

| 文件 | 路径 |
|------|------|
| access-lists | `{cache_dir}/{tenant}-access-lists.json` |
| scene-registry | `{cache_dir}/{tenant}-scene-registry.json` |

与 OpenResty `lists_file` / APISIX 插件配置 **逐字一致**。

### 6.5 远程读取模式（配置）

| 模式 | 配置值 | 行为 |
|------|--------|------|
| **redis**（默认） | `VIRBIUS_GATEWAY_SYNC_SOURCE=redis` | HGETALL Pointer → GET Blob |
| **http** | `=http` | GET policy-version + GET snapshot（走 control） |
| **redis+http** | `=redis,http` | Redis 优先；失败 fallback HTTP |

### 6.6 时序（happy path）

```text
Sidecar                         Redis                         OpenResty
  |                               |                               |
  |-- XREADGROUP / poll --------->|                               |
  |<-- pointer rev=42 ------------|                               |
  |-- GET blob r42 -------------->|                               |
  |<-- JSON bytes ----------------|                               |
  | verify sha256                 |                               |
  | write .tmp → rename           |                               |
  |                               |         next request          |
  |                               |<------------------------------|
  |                               |    file_cache mtime changed   |
  |-- HSET ack rev=42 ----------->|                               |
```

### 6.7 首启 vs 稳态

| 场景 | 行为 |
|------|------|
| **首启**（无 meta） | blocking SYNC，直到成功或 `FIRST_SYNC_TIMEOUT`；超时 exit 1（K8s restart） |
| **稳态** | 失败 → DEGRADED，保留旧文件；网关 **fail-open** 继续服务 |
| **Pointer 回滚** | Sidecar 见 rev 变小 **仍允许** 拉取（管理员回滚）；WRITE 覆盖本地 |

### 6.8 与 Stream 去重

Sidecar 维护 `last_stream_id`；收到事件后 **仍比对** Pointer revision，避免重复 WRITE。

---

## 7. Control 配置（application.yml 草案）

```yaml
virbius:
  gateway:
    artifact:
      enabled: true
      storage: redis          # redis | s3（后期）
      redis:
        blob-prefix: virbius:artifacts:gateway
        pointer-prefix: virbius:config:gateway
        events-stream: virbius:config:events
        retain-revisions: 10
      local-fallback: false   # true = 双写 data/gateway（B0 过渡）
```

---

## 8. 实施检查清单

### B0（Control Redis 发布）

- [ ] Lua publish 脚本 + 单元测试（embedded Redis）
- [ ] `tb_gateway_artifact_meta` + seed 不变
- [ ] `refreshArtifacts` 末尾调用 publisher
- [ ] Admin `POST .../gateway-artifacts/refresh`
- [ ] 双写开关 `local-fallback`

### B1（Sidecar）

- [ ] `virbius-gateway-sync` 二进制或 `scripts/gateway-sync.sh` PoC
- [ ] 状态机 + meta.json
- [ ] OpenResty effective 指 `/var/cache/virbius/gateway/...`
- [ ] run-local 文档：分机部署步骤

### B2（可观测）

- [ ] `GET policy-version` / Admin nodes
- [ ] ops 看板：revision、nodes_ok
- [ ] Stream consumer group 监控（lag）

---

## 9. 相关文档

- [openresty-gateway.md](./openresty-gateway.md) §2–3
- [registry.openapi.yaml](./registry.openapi.yaml) — Edge `policy-version` / `manifest`
- [MVP-OPENSPEC.md](./MVP-OPENSPEC.md) §6 PublishOrchestrator
- [rule-rollout.md](./rule-rollout.md) §3.3 副作用
