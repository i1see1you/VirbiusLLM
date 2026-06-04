-- virbius-guard APISIX plugin (MVP)
-- trace: client header or gateway-generated ULID; illegal format -> 400
-- access lists: subject (user/device/ip) + keyword on 管侧 only

local core = require("apisix.core")
local http = require("resty.http")
local uuid = require("resty.jit-uuid")

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
    version = 0.8,
    priority = 7999,
    name = plugin_name,
    schema = schema,
}

local lists_cache = {}

local function valid_trace_id(id)
    if not id or id == "" or #id < 16 or #id > 128 then
        return false
    end
    if id:match("^%x%x%x%x%x%x%x%x%-%x%x%x%x%-4%x%x%x%-[89abAB]%x%x%x%-%x%x%x%x%x%x%x%x%x%x%x%x$") then
        return true
    end
    if id:match("^[0-9A-HJKMNP-TV-Z]{26}$") then
        return true
    end
    return false
end

local function new_trace_id()
    return uuid()
end

local function read_body()
    ngx.req.read_body()
    return ngx.req.get_body_data() or ""
end

local function load_lists(path)
    local attr = io.popen("stat -f %m " .. path .. " 2>/dev/null")
    local mtime = 0
    if attr then
        local out = attr:read("*a")
        attr:close()
        mtime = tonumber(out) or 0
    end
    if lists_cache.path == path and lists_cache.mtime == mtime and lists_cache.data then
        return lists_cache.data
    end
    local f = io.open(path, "r")
    if not f then
        return nil
    end
    local raw = f:read("*a")
    f:close()
    local data = core.json.decode(raw)
    if not data then
        return nil
    end
    lists_cache.path = path
    lists_cache.mtime = mtime
    lists_cache.data = data
    return data
end

local function keyword_hit(content, keywords)
    if not content or content == "" or not keywords then
        return false
    end
    local lower = string.lower(content)
    for _, kw in ipairs(keywords) do
        if kw and kw ~= "" then
            if kw:match("[\128-\255]") or kw:match("[\194-\244]") then
                if content:find(kw, 1, true) then
                    return true
                end
            elseif lower:find(string.lower(kw), 1, true) then
                return true
            end
        end
    end
    return false
end

local function ip_to_num(ip)
    local a, b, c, d = ip:match("^(%d+)%.(%d+)%.(%d+)%.(%d+)$")
    if not a then
        return nil
    end
    return tonumber(a) * 16777216 + tonumber(b) * 65536 + tonumber(c) * 256 + tonumber(d)
end

local function ip_in_cidr(ip, cidr)
    local base, prefix = cidr:match("^([^/]+)/(%d+)$")
    if not base then
        return ip == cidr
    end
    local ipn = ip_to_num(ip)
    local basen = ip_to_num(base)
    prefix = tonumber(prefix)
    if not ipn or not basen or not prefix or prefix > 32 then
        return false
    end
    local bit = require("bit")
    local mask = 0
    if prefix > 0 then
        mask = bit.bnot(bit.rshift(0xffffffff, prefix))
    end
    return bit.band(ipn, mask) == bit.band(basen, mask)
end

local function ip_in_any(ip, cidrs)
    if not ip or not cidrs then
        return false
    end
    for _, cidr in ipairs(cidrs) do
        if ip_in_cidr(ip, cidr) then
            return true
        end
    end
    return false
end

local function in_set(val, arr)
    if not val or not arr then
        return false
    end
    for _, v in ipairs(arr) do
        if v == val then
            return true
        end
    end
    return false
end

local function header_key(name)
    return string.lower(name or "")
end

local function query_args()
    return ngx.req.get_uri_args() or {}
end

local function query_value(name)
    local args = query_args()
    local val = args[name]
    if type(val) == "table" then
        return val[1]
    end
    if val == nil then
        return nil
    end
    return tostring(val)
end

local function resolve_context_vars(ctx, bindings, user_id, device_id, client_ip)
    local out = {}
    local defs = bindings and bindings.vars or {}
    for logical, def in pairs(defs) do
        local from = def.from
        local val = nil
        if from == "query" and def.name then
            val = query_value(def.name)
        elseif from == "header" and def.name then
            val = core.request.header(ctx, def.name)
        elseif from == "subject" and def.field == "user_id" then
            val = user_id
        elseif from == "subject" and def.field == "device_id" then
            val = device_id
        elseif from == "network" and def.field == "ip" then
            val = client_ip
        end
        if val and val ~= "" then
            out[logical] = tostring(val)
        end
    end
    return out
end

local registry_cache = {}

local function load_registry(conf)
    local path = conf.scene_registry_file
    if not path or path == "" then
        local lists = load_lists(conf.lists_file or "./data/gateway/default-access-lists.json")
        if lists and lists.scene_registry then
            return lists.scene_registry
        end
        return nil
    end
    local attr = io.popen("stat -f %m " .. path .. " 2>/dev/null")
    local mtime = 0
    if attr then
        local out = attr:read("*a")
        attr:close()
        mtime = tonumber(out) or 0
    end
    if registry_cache.path == path and registry_cache.mtime == mtime and registry_cache.data then
        return registry_cache.data
    end
    local f = io.open(path, "r")
    if not f then
        local lists = load_lists(conf.lists_file or "./data/gateway/default-access-lists.json")
        if lists and lists.scene_registry then
            return lists.scene_registry
        end
        return nil
    end
    local raw = f:read("*a")
    f:close()
    local root = core.json.decode(raw)
    if not root then
        return nil
    end
    local data = root.scene_registry or root
    registry_cache.path = path
    registry_cache.mtime = mtime
    registry_cache.data = data
    return data
end

local function normalize_uri(raw)
    if not raw or raw == "" then
        return nil
    end
    local s = raw
    local q = string.find(s, "?", 1, true)
    if q then
        s = string.sub(s, 1, q - 1)
    end
    local h = string.find(s, "#", 1, true)
    if h then
        s = string.sub(s, 1, h - 1)
    end
    if string.sub(s, 1, 1) ~= "/" then
        s = "/" .. s
    end
    return s
end

local function uri_matches(route_uri, pattern)
    local uri = normalize_uri(route_uri)
    local pat = normalize_uri(pattern)
    if not uri or not pat then
        return false
    end
    if string.sub(pat, -1) == "*" then
        local prefix = string.sub(pat, 1, -2)
        return string.sub(uri, 1, string.len(prefix)) == prefix
    end
    return uri == pat
end

local function match_map(expected, actual)
    if not expected then
        return true
    end
    for k, v in pairs(expected) do
        local got = actual and actual[k]
        if got == nil or tostring(got) ~= tostring(v) then
            return false
        end
    end
    return true
end

local function default_scene_for_app(scenes, app_id)
    for scene_id, def in pairs(scenes or {}) do
        if def.app_id == app_id and def.default then
            return scene_id
        end
    end
    return nil
end

local function resolve_scene(registry, app_id, route_uri, query, headers)
    if not registry or not app_id or app_id == "" then
        return nil, "missing_app_id"
    end
    local scenes = registry.scenes
    if not scenes then
        return nil, "no_scenes"
    end
    local known = false
    for _, def in pairs(scenes) do
        if def.app_id == app_id then
            known = true
            break
        end
    end
    if not known then
        if registry.fail_on_unknown_app then
            return nil, "unknown_app"
        end
        return default_scene_for_app(scenes, app_id), "default"
    end
    local candidates = {}
    for scene_id, def in pairs(scenes) do
        if def.app_id ~= app_id then
            goto continue
        end
        if not def.uris or #def.uris == 0 then
            goto continue
        end
        local uri_hit = false
        for _, pat in ipairs(def.uris) do
            if uri_matches(route_uri, pat) then
                uri_hit = true
                break
            end
        end
        if not uri_hit then
            goto continue
        end
        local match = def.match or {}
        if not match_map(match.query, query) then
            goto continue
        end
        if not match_map(match.headers, headers) then
            goto continue
        end
        candidates[#candidates + 1] = {
            scene_id = scene_id,
            priority = def.priority or 0,
        }
        ::continue::
    end
    if #candidates > 0 then
        table.sort(candidates, function(a, b)
            return a.priority > b.priority
        end)
        return candidates[1].scene_id, "rule"
    end
    local def_scene = default_scene_for_app(scenes, app_id)
    if def_scene then
        return def_scene, "default"
    end
    if registry.fail_on_unresolved_scene then
        return nil, "unresolved"
    end
    return nil, "unresolved"
end

local function var_entry_hit(vars_map, entry)
    local eq = string.find(entry, "=", 1, true)
    if not eq or eq <= 1 then
        return false
    end
    local logical = string.sub(entry, 1, eq - 1)
    local want = string.sub(entry, eq + 1)
    return vars_map[logical] ~= nil and vars_map[logical] == want
end

local function var_list_hit(vars_map, entries)
    if not entries then
        return false
    end
    for _, entry in ipairs(entries) do
        if var_entry_hit(vars_map, entry) then
            return true
        end
    end
    return false
end

local function entry_active(expires_at)
    if not expires_at or expires_at == "" then
        return true
    end
    local now = ngx.time()
    local y, m, d, hh, mm, ss = expires_at:match("^(%d+)%-(%d+)%-(%d+)T(%d+):(%d+):(%d+)")
    if not y then
        return true
    end
    local exp = os.time({
        year = tonumber(y),
        month = tonumber(m),
        day = tonumber(d),
        hour = tonumber(hh),
        min = tonumber(mm),
        sec = tonumber(ss),
    })
    return exp > now
end

local function active_values(entries)
    local out = {}
    if not entries then
        return out
    end
    for _, e in ipairs(entries) do
        local val = e
        local exp = nil
        if type(e) == "table" then
            val = e.value
            exp = e.expires_at
        end
        if val and val ~= "" and entry_active(exp) then
            out[#out + 1] = val
        end
    end
    return out
end

local function match_list_block(block, content, user_id, device_id, client_ip, vars_map)
    local dim = block.dimension or ""
    local values = active_values(block.entries)
    if #values == 0 then
        return false
    end
    if dim == "keyword" then
        return keyword_hit(content, values)
    end
    if dim == "user_id" then
        return in_set(user_id, values)
    end
    if dim == "device_id" then
        return in_set(device_id, values)
    end
    if dim == "ip_cidr" or dim == "ip" then
        return ip_in_any(client_ip, values)
    end
    if dim == "var" then
        return var_list_hit(vars_map, values)
    end
    return false
end

local function in_canary_bucket(session_id, percent)
    if not percent then
        return false
    end
    if percent >= 100 then
        return true
    end
    if percent <= 0 then
        return false
    end
    local key = session_id
    if not key or key == "" then
        key = "default"
    end
    local bucket = ngx.crc32_long(key) % 100
    return bucket < percent
end

local function normalize_mode(mode)
    if not mode or mode == "" then
        return "dry_run"
    end
    return string.lower(mode)
end

local function intent_priority(intent)
    if not intent or intent == "" then
        return 0
    end
    intent = string.lower(intent)
    if intent == "deny" then
        return 100
    elseif intent == "captcha" then
        return 50
    elseif intent == "review" then
        return 30
    end
    return 0
end

local function is_allow_intent(intent)
    if not intent or intent == "" then
        return false
    end
    return string.lower(intent) == "allow"
end

local function normalize_intent(intent, risk_score)
    if intent and intent ~= "" then
        intent = string.lower(intent)
        if intent == "allow" or intent == "deny" or intent == "captcha"
            or intent == "review" then
            return intent
        end
    end
    risk_score = risk_score or 0
    if risk_score >= 100 then
        return "deny"
    elseif risk_score <= 0 then
        return "allow"
    end
    return "review"
end

local function is_full(mode)
    return normalize_mode(mode) == "full"
end

local function is_canary_effective(hit, session_id)
    if normalize_mode(hit.enforce_mode) ~= "canary" then
        return false
    end
    return in_canary_bucket(session_id, hit.canary_percent)
end

local function effective_enforce(hits, session_id)
    for _, h in ipairs(hits) do
        if is_full(h.enforce_mode) then
            return true
        end
    end
    for _, h in ipairs(hits) do
        if is_canary_effective(h, session_id) then
            return true
        end
    end
    return false
end

local function pick_primary(hits)
    local primary = hits[1]
    for _, h in ipairs(hits) do
        if (h.risk_score or 0) > (primary.risk_score or 0) then
            primary = h
        elseif (h.risk_score or 0) == (primary.risk_score or 0)
            and (h.rule_revision or 1) > (primary.rule_revision or 1) then
            primary = h
        elseif (h.risk_score or 0) == (primary.risk_score or 0)
            and (h.rule_revision or 1) == (primary.rule_revision or 1)
            and (h.rule_id or "") > (primary.rule_id or "") then
            primary = h
        end
    end
    return primary
end

local function merge_actions(hits, session_id)
    if not hits or #hits == 0 then
        return { effective_action = "allow", max_risk_score = 0, primary = nil }
    end
    for _, h in ipairs(hits) do
        if is_allow_intent(h.intent_action) then
            return { effective_action = "allow", max_risk_score = 0, primary = nil }
        end
    end
    local max_priority = 0
    for _, h in ipairs(hits) do
        local p = intent_priority(h.intent_action)
        if p > max_priority then
            max_priority = p
        end
    end
    if max_priority <= 0 then
        return { effective_action = "allow", max_risk_score = 0, primary = nil }
    end
    local top = {}
    for _, h in ipairs(hits) do
        if intent_priority(h.intent_action) == max_priority then
            top[#top + 1] = h
        end
    end
    local max_risk_score = 0
    for _, h in ipairs(top) do
        if (h.risk_score or 0) > max_risk_score then
            max_risk_score = h.risk_score or 0
        end
    end
    local primary = pick_primary(top)
    local intent = normalize_intent(primary.intent_action, primary.risk_score)
    local effective = effective_enforce(top, session_id)
    local effective_action = "allow"
    if intent == "deny" then
        effective_action = effective and "block" or "review"
    elseif intent == "captcha" then
        effective_action = effective and "captcha" or "review"
    elseif intent == "review" then
        effective_action = "review"
    end
    return { effective_action = effective_action, max_risk_score = max_risk_score, primary = primary }
end

local function hits_to_prior_signals(hits)
    local out = {}
    for _, h in ipairs(hits) do
        if not is_allow_intent(h.intent_action) then
            out[#out + 1] = {
                rule_id = h.rule_id,
                rule_revision = h.rule_revision or 1,
                source = "gateway",
                layer = "gateway",
                score = h.risk_score,
                intent_action = h.intent_action or normalize_intent(nil, h.risk_score),
                reason_code = h.reason_code,
                enforce_mode = h.enforce_mode or "dry_run",
                canary_percent = h.canary_percent,
            }
        end
    end
    return out
end

local function collect_named_list_hits(lists, content, user_id, device_id, client_ip, vars_map)
    if not lists then
        return {}
    end
    local hits = {}
    for _, block in ipairs(lists) do
        if match_list_block(block, content, user_id, device_id, client_ip, vars_map) then
            local score = block.risk_score or 100
            if score <= 0 then
                return {}
            end
            hits[#hits + 1] = {
                rule_id = block.rule_id or block.list_name or "list_match",
                rule_revision = block.rule_revision or 1,
                reason_code = block.reason_code or "LIST_MATCH",
                risk_score = score,
                intent_action = block.intent_action or normalize_intent(nil, score),
                enforce_mode = block.enforce_mode or "dry_run",
                canary_percent = block.canary_percent,
            }
        end
    end
    return hits
end

local function check_access_lists(conf, ctx, content, user_id, device_id, client_ip)
    local lists = load_lists(conf.lists_file or "./data/gateway/default-access-lists.json")
    if not lists then
        return nil, {}
    end
    local bindings = lists.context_bindings or {}
    local vars_ctx = resolve_context_vars(ctx, bindings, user_id, device_id, client_ip)
    local hits = collect_named_list_hits(lists.lists, content, user_id, device_id, client_ip, vars_ctx)
    if #hits == 0 then
        return nil, vars_ctx
    end
    return hits, vars_ctx
end

function _M.access(conf, ctx)
    if not conf.evaluate then
        return
    end

    local trace_hdr = core.request.header(ctx, "X-Virbius-Trace-Id")
    local trace_id = trace_hdr
    if trace_hdr and trace_hdr ~= "" then
        if not valid_trace_id(trace_hdr) then
            return core.response.exit(400, {
                code = "INVALID_ARGUMENT",
                message = "invalid X-Virbius-Trace-Id",
            })
        end
    else
        trace_id = new_trace_id()
        core.log.warn(plugin_name, " generated trace_id=", trace_id)
    end

    local user_id = core.request.header(ctx, "X-Virbius-User-Id")
    local device_id = core.request.header(ctx, "X-Virbius-Device-Id")
    local session_id = core.request.header(ctx, "X-Virbius-Session-Id")
    local client_ip = ngx.var.remote_addr
    local content = read_body()

    ngx.req.clear_header("X-Virbius-Scene")
    local route_uri = ngx.var.uri
    local query = query_args()
    local headers_map = {}

    local hits, vars_ctx = check_access_lists(conf, ctx, content, user_id, device_id, client_ip)

    local scene_id = nil
    local registry = load_registry(conf)
    if registry then
        local resolved, src = resolve_scene(registry, vars_ctx and vars_ctx.app_id, route_uri, query, headers_map)
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

    local prior_signals = nil
    if hits and #hits > 0 then
        local merged = merge_actions(hits, session_id)
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
            prior_signals = hits_to_prior_signals(hits)
        end
    end

    if content == "" then
        ngx.req.set_header("X-Virbius-Trace-Id", trace_id)
        return
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

return _M
