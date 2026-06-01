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
        scene = { type = "string", default = "chat" },
        lists_file = {
            type = "string",
            default = "./data/gateway/default-access-lists.json",
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

    local hits, vars_ctx = check_access_lists(conf, ctx, content, user_id, device_id, client_ip)
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
        scene = conf.scene or "chat",
        role = "user",
        content = content,
        trace_id = trace_id,
        session_id = session_id,
        user_id = user_id,
        device_id = device_id,
        client_ip = client_ip,
        vars = vars_ctx,
        prior_signals = prior_signals,
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
