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
    version = 0.5,
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

local function side_allows(side, content, user_id, device_id, client_ip, vars_map)
    if not side then
        return false
    end
    if in_set(user_id, side.user_ids) then
        return true
    end
    if in_set(device_id, side.device_ids) then
        return true
    end
    if ip_in_any(client_ip, side.ip_cidrs) then
        return true
    end
    if var_list_hit(vars_map, side.vars) then
        return true
    end
    if keyword_hit(content, side.keywords) then
        return true
    end
    return false
end

local function side_denies(side, content, user_id, device_id, client_ip, vars_map)
    if not side then
        return nil
    end
    if in_set(user_id, side.user_ids) then
        return "GW_SUBJECT_USER_DENY", "gw_subject_network_deny"
    end
    if in_set(device_id, side.device_ids) then
        return "GW_SUBJECT_DEVICE_DENY", "gw_subject_network_deny"
    end
    if ip_in_any(client_ip, side.ip_cidrs) then
        return "GW_NETWORK_IP_DENY", "gw_subject_network_deny"
    end
    if var_list_hit(vars_map, side.vars) then
        return "GW_CONTEXT_VAR_DENY", "gw_request_param_deny"
    end
    if keyword_hit(content, side.keywords) then
        return "GW_CONTENT_KEYWORD_DENY", "gw_content_deny"
    end
    return nil
end

local function check_access_lists(conf, ctx, content, user_id, device_id, client_ip)
    local lists = load_lists(conf.lists_file or "./data/gateway/default-access-lists.json")
    if not lists then
        return nil, {}
    end
    local bindings = lists.context_bindings or {}
    local vars_ctx = resolve_context_vars(ctx, bindings, user_id, device_id, client_ip)
    if side_allows(lists.allow, content, user_id, device_id, client_ip, vars_ctx) then
        return nil, vars_ctx
    end
    local reason, rule_id = side_denies(lists.deny, content, user_id, device_id, client_ip, vars_ctx)
    if reason then
        return { reason, rule_id }, vars_ctx
    end
    return nil, vars_ctx
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
    local client_ip = ngx.var.remote_addr
    local content = read_body()

    local block, vars_ctx = check_access_lists(conf, ctx, content, user_id, device_id, client_ip)
    if block then
        return core.response.exit(403, {
            code = "POLICY_BLOCK",
            message = "blocked by gateway access list",
            trace_id = trace_id,
            reason_code = block[1],
            rule_id = block[2],
        })
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
        user_id = user_id,
        device_id = device_id,
        client_ip = client_ip,
        vars = vars_ctx,
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
        })
    end

    ngx.req.set_header("X-Virbius-Trace-Id", trace_id)
end

return _M
