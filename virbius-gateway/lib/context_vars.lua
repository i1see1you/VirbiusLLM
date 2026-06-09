-- context_bindings → logical vars (header / query / subject / network).
local _M = {}

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

function _M.query_args()
    return query_args()
end

function _M.resolve(bindings, get_header, user_id, device_id, client_ip)
    local out = {}
    local defs = bindings and bindings.vars or {}
    for logical, def in pairs(defs) do
        local from = def.from
        local val = nil
        if from == "query" and def.name then
            val = query_value(def.name)
        elseif from == "header" and def.name and get_header then
            val = get_header(def.name)
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

return _M
