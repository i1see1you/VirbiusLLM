-- virbius-guard APISIX plugin (MVP)
-- trace: client header or gateway-generated UUID v4; illegal format -> 400
-- access lists: subject (user/device/ip) + keyword on gateway side only

local function add_lib_path()
    local info = debug.getinfo(1, "S")
    local source = info and info.source or ""
    if source:sub(1, 1) == "@" then
        source = source:sub(2)
    end
    local plugin_dir = source:match("^(.*)/")
    if plugin_dir then
        package.path = plugin_dir .. "/../../lib/?.lua;" .. package.path
    end
    local env_root = os.getenv("VIRBIUS_GATEWAY_LIB")
    if env_root and env_root ~= "" then
        package.path = env_root:gsub("/$", "") .. "/?.lua;" .. package.path
    end
end
add_lib_path()

local core = require("apisix.core")
local http = require("resty.http")
local uuid = require("resty.jit-uuid")
local json_util = require("json_util")
local context_vars_mod = require("context_vars")
local scene_registry_mod = require("scene_registry")
local access_lists_mod = require("access_lists")
local config_redis_mod = require("config_redis")
local prompt_mod = require("prompt")
local trace_mod = require("trace")

json_util.set_codec(core.json.encode, core.json.decode)

uuid.seed()

local plugin_name = "virbius-guard"

local schema = {
    type = "object",
    properties = {
        bundle_version = { type = "string" },
        evaluate = { type = "boolean", default = true },
        agent_url = { type = "string", default = "http://127.0.0.1:9070" },
        fail_mode = { type = "string", enum = { "open", "close" }, default = "open" },
        tenant_id = { type = "string", default = "default" },
        scene = { type = "string" },
        auth_mode = { type = "string", enum = { "optional", "required" }, default = "optional" },
        lists_file = {
            type = "string",
            default = "./data/gateway/default-access-lists.json",
        },
        scene_registry_file = {
            type = "string",
            default = "./data/gateway/default-scene-registry.json",
        },
    },
    required = { "bundle_version" },
}

local _M = {
    version = 0.9,
    priority = 7999,
    name = plugin_name,
    schema = schema,
}

local function get_header(ctx, name)
    return core.request.header(ctx, name)
end

local function read_prompt_content()
    ngx.req.read_body()
    return prompt_mod.extract(ngx.req.get_body_data() or "", core.json.decode)
end

local function check_access_lists(lists_source, ctx, content, user_id, device_id, client_ip)
    local file = type(lists_source) == "string" and lists_source or nil
    local data = type(lists_source) == "table" and lists_source or nil
    return access_lists_mod.check(
        data or file or "./data/gateway/default-access-lists.json",
        function(name)
            return get_header(ctx, name)
        end,
        content,
        user_id,
        device_id,
        client_ip
    )
end

function _M.access(conf, ctx)
    if not conf.evaluate then
        return
    end

    local trace_hdr = get_header(ctx, "X-Virbius-Trace-Id")
    local trace_id = trace_hdr
    if trace_hdr and trace_hdr ~= "" then
        if not trace_mod.valid(trace_hdr) then
            return core.response.exit(400, {
                code = "INVALID_ARGUMENT",
                message = "invalid X-Virbius-Trace-Id",
            })
        end
    else
        trace_id = uuid()
        core.log.warn(plugin_name, " generated trace_id=", trace_id)
    end

    local device_id = get_header(ctx, "X-Virbius-Device-Id")
    local session_id = get_header(ctx, "X-Virbius-Session-Id")
    local client_ip = ngx.var.remote_addr
    local content = read_prompt_content()

    local user_id
    if conf.auth_mode == "required" then
        local consumer = ctx.authenticated_consumer
        if not consumer or not consumer.id then
            return core.response.exit(401, {
                code = "UNAUTHENTICATED",
                message = "authentication required",
                trace_id = trace_id,
            })
        end
        user_id = consumer.id
        ngx.req.clear_header("X-Virbius-User-Id")
    else
        user_id = get_header(ctx, "X-Virbius-User-Id")
    end

    ngx.req.clear_header("X-Virbius-Scene")
    local route_uri = ngx.var.uri
    local query = context_vars_mod.query_args()
    local headers_map = {}

    local config_cache = config_redis_mod.load(conf.tenant_id)
    if not config_cache then
        core.log.warn(plugin_name, " config_redis unavailable, falling back to file")
    end
    local lists_source = config_cache and config_cache.access_lists or conf.lists_file

    local hits, vars_ctx, bindings, extended_defs = check_access_lists(lists_source, ctx, content, user_id, device_id, client_ip)

    local scene_id = nil
    local registry = config_cache
        and scene_registry_mod.load_from_cache(config_cache)
        or scene_registry_mod.load_from_file(
            conf.scene_registry_file,
            conf.lists_file or "./data/gateway/default-access-lists.json"
        )
    if registry then
        local resolved, src = scene_registry_mod.resolve(
            registry,
            vars_ctx and vars_ctx.app_id,
            route_uri,
            query,
            headers_map
        )
        if resolved then
            scene_id = resolved
        elseif registry.fail_on_unknown_app or registry.fail_on_unresolved_scene then
            return core.response.exit(403, {
                code = "UNKNOWN_SCENE",
                message = "scene resolution failed: " .. tostring(src),
                trace_id = trace_id,
            })
        end
    end
    if not scene_id or scene_id == "" then
        scene_id = conf.scene
    end
    if not scene_id or scene_id == "" then
        return core.response.exit(403, {
            code = "UNKNOWN_SCENE",
            message = "scene not resolved",
            trace_id = trace_id,
        })
    end
    ngx.req.set_header("X-Virbius-Scene", scene_id)

    vars_ctx = context_vars_mod.filter_by_scope(vars_ctx, bindings, vars_ctx and vars_ctx.app_id, scene_id)

    local ext_vars = context_vars_mod.compute_extended_vars(vars_ctx, extended_defs, vars_ctx and vars_ctx.app_id, scene_id)
    for k, v in pairs(ext_vars) do vars_ctx[k] = v end

    local prior_signals = nil
    if hits and #hits > 0 then
        local merged = access_lists_mod.merge_actions(hits, session_id)
        if merged.effective_action == "block" and merged.primary then
            return core.response.exit(403, {
                code = "POLICY_BLOCK",
                message = "blocked by gateway access list",
                trace_id = trace_id,
                reason_code = merged.primary.reason_code,
                rule_id = merged.primary.rule_id,
                effective_action = "block",
                max_risk_score = merged.max_risk_score,
            })
        end
        if merged.effective_action == "captcha" and merged.primary then
            return core.response.exit(428, {
                code = "POLICY_CAPTCHA",
                message = "captcha required by gateway access list",
                trace_id = trace_id,
                reason_code = merged.primary.reason_code,
                rule_id = merged.primary.rule_id,
                effective_action = "captcha",
                max_risk_score = merged.max_risk_score,
            })
        end
        if merged.effective_action == "review" then
            prior_signals = access_lists_mod.hits_to_prior_signals(hits)
        end
    end

    if content == "" then
        ngx.req.set_header("X-Virbius-Trace-Id", trace_id)
        return
    end

    local deploy_pool = resolve_deploy_pool(conf.tenant_id, session_id, trace_id)
    if deploy_pool then
        ngx.req.set_header("X-Virbius-Pool", deploy_pool)
    end

    local httpc = http.new()
    httpc:set_timeout(3000)
    local payload = core.json.encode({
        tenant_id = conf.tenant_id or "default",
        scene = scene_id,
        role = "user",
        content = content,
        trace_id = trace_id,
        session_id = session_id,
        user_id = user_id,
        device_id = device_id,
        client_ip = client_ip,
        vars = vars_ctx,
        prior_signals = prior_signals,
        route_uri = route_uri,
        query = query,
        headers = headers_map,
    })
    local res, err = httpc:request_uri(conf.agent_url .. "/v1/evaluate", {
        method = "POST",
        body = payload,
        headers = { ["Content-Type"] = "application/json" },
    })
    if not res then
        core.log.error(plugin_name, " agent error: ", err)
        if conf.fail_mode == "close" then
            return core.response.exit(503, { code = "UNAVAILABLE", message = "agent unavailable" })
        end
        return
    end

    if res.status == 400 then
        return core.response.exit(400, core.json.decode(res.body))
    end
    if res.status >= 500 then
        if conf.fail_mode == "close" then
            return core.response.exit(503, { code = "UNAVAILABLE", message = "agent error" })
        end
        return
    end

    local out = core.json.decode(res.body)
    if out and out.effective_action == "block" then
        return core.response.exit(403, {
            code = "POLICY_BLOCK",
            message = "blocked by virbius policy",
            trace_id = trace_id,
            reason_code = out.reason_code,
            rule_id = out.rule_id,
            effective_action = "block",
            max_risk_score = out.max_risk_score,
        })
    end
    if out and out.effective_action == "captcha" then
        return core.response.exit(428, {
            code = "POLICY_CAPTCHA",
            message = "captcha required by virbius policy",
            trace_id = trace_id,
            reason_code = out.reason_code,
            rule_id = out.rule_id,
            effective_action = "captcha",
            max_risk_score = out.max_risk_score,
        })
    end

    ngx.req.set_header("X-Virbius-Trace-Id", trace_id)
end

-- ---------------------------------------------------------------
-- Deploy rollout pointer: read from Redis, compute session pool
-- ---------------------------------------------------------------

local function deploy_redis_connect()
    local redis = require("resty.redis")
    local red = redis:new()
    red:set_timeout(200)
    local redis_url = os.getenv("VIRBIUS_REDIS_URL") or "127.0.0.1:6379"
    local host, port = redis_url:match("^redis://([^:/]+):?(%d*)")
    if not host then
        host, port = redis_url:match("^([^:/]+):?(%d*)")
    end
    host = host or "127.0.0.1"
    port = tonumber(port) or 6379
    local ok, err = red:connect(host, port)
    if not ok then
        return nil, "connect failed: " .. (err or "unknown")
    end
    return red, nil
end

local DEPLOY_POINTER_CACHE = {}
local DEPLOY_POINTER_TTL = 5

function resolve_deploy_pool(tenant_id, session_id, trace_id)
    if not tenant_id or tenant_id == "" then
        return nil
    end
    local now = ngx.time()
    local cached = DEPLOY_POINTER_CACHE[tenant_id]
    local canary_percent
    if cached and now - cached.fetched_at < DEPLOY_POINTER_TTL then
        canary_percent = cached.canary_percent
    else
        local red, err = deploy_redis_connect()
        if not red then
            core.log.warn(plugin_name, " deploy pointer: redis connect failed: ", err)
            return nil
        end
        local pointer_key = "virbius:deploy:active:" .. tenant_id
        local raw, perr = red:hgetall(pointer_key)
        red:set_keepalive(10000, 100)
        if not raw or #raw == 0 then
            DEPLOY_POINTER_CACHE[tenant_id] = { canary_percent = 0, fetched_at = now }
            return nil
        end
        local pointer = {}
        for i = 1, #raw, 2 do
            pointer[raw[i]] = raw[i + 1]
        end
        canary_percent = tonumber(pointer.canary_percent) or 0
        DEPLOY_POINTER_CACHE[tenant_id] = { canary_percent = canary_percent, fetched_at = now }
    end

    if canary_percent <= 0 then
        return nil
    end

    local bucket_key = session_id or trace_id or tenant_id
    local bucket = ngx.crc32_long(bucket_key) % 100
    if bucket < canary_percent then
        return "canary"
    end
    return "stable"
end

-- ---------------------------------------------------------------

return _M
