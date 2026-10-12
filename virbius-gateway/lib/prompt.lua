-- Extract user prompt from OpenAI-style chat JSON.
local json_util = require("json_util")

local _M = {}

function _M.extract(raw, decode_fn)
    local decode = decode_fn or json_util.decode
    if not raw or raw == "" then
        return ""
    end
    local ok, body = pcall(decode, raw)
    if not ok or type(body) ~= "table" then
        return raw
    end
    if type(body.input) == "string" and body.input ~= "" then
        return body.input
    end
    if type(body.prompt) == "string" and body.prompt ~= "" then
        return body.prompt
    end
    local messages = body.messages
    if type(messages) ~= "table" then
        return raw
    end
    local parts = {}
    for _, msg in ipairs(messages) do
        if type(msg) == "table" and msg.role == "user" then
            local c = msg.content
            if type(c) == "string" and c ~= "" then
                table.insert(parts, c)
            elseif type(c) == "table" then
                for _, part in ipairs(c) do
                    if type(part) == "table" and part.type == "text"
                        and type(part.text) == "string" and part.text ~= "" then
                        table.insert(parts, part.text)
                    end
                end
            end
        end
    end
    if #parts > 0 then
        return table.concat(parts, "\n")
    end
    return raw
end

function _M.read_prompt_content(decode_fn)
    ngx.req.read_body()
    local raw = ngx.req.get_body_data() or ""
    return _M.extract(raw, decode_fn)
end

local function decode_body(raw, decode_fn)
    if not raw or raw == "" then
        return nil
    end
    local decode = decode_fn or json_util.decode
    local ok, body = pcall(decode, raw)
    if not ok or type(body) ~= "table" then
        return nil
    end
    return body
end

local function message_text(c)
    if type(c) == "string" then
        return c
    end
    if type(c) ~= "table" then
        return nil
    end
    local parts = {}
    for _, part in ipairs(c) do
        if type(part) == "table" and part.type == "text"
            and type(part.text) == "string" and part.text ~= "" then
            table.insert(parts, part.text)
        end
    end
    if #parts > 0 then
        return table.concat(parts, "\n")
    end
    return nil
end

function _M.extract_last_user(raw, decode_fn)
    local body = decode_body(raw, decode_fn)
    if not body then
        return raw or ""
    end
    if type(body.input) == "string" and body.input ~= "" then
        return body.input
    end
    if type(body.prompt) == "string" and body.prompt ~= "" then
        return body.prompt
    end
    local messages = body.messages
    if type(messages) ~= "table" then
        return raw or ""
    end
    local last
    for _, msg in ipairs(messages) do
        if type(msg) == "table" and msg.role == "user" then
            local text = message_text(msg.content)
            if text and text ~= "" then
                last = text
            end
        end
    end
    return last or ""
end

function _M.extract_meta(raw, decode_fn)
    local body = decode_body(raw, decode_fn)
    if not body then
        return { stream = false }
    end
    return {
        model = type(body.model) == "string" and body.model or nil,
        stream = body.stream == true,
        tools = type(body.tools) == "table" and #body.tools > 0 or false,
        n = type(body.n) == "number" and body.n or 1,
    }
end

function _M.read_prompt_inputs(decode_fn)
    ngx.req.read_body()
    local raw = ngx.req.get_body_data() or ""
    return raw, _M.extract(raw, decode_fn), _M.extract_last_user(raw, decode_fn)
end

return _M
