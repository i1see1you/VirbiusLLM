-- Synthesize OpenAI-compatible chat completion payloads for QA proxy replies.
local _M = {}

local function ids(trace_id, opts)
    return {
        id = "chatcmpl-" .. (trace_id or ""),
        created = opts.created or os.time(),
        model = opts.model or "virbius-qa-reply",
    }
end

local function virbius_ext(opts)
    return {
        proxy_replied = true,
        qa_entry_id = opts.qa_entry_id,
        trace_id = opts.trace_id,
    }
end

function _M.completion(answer, opts)
    opts = opts or {}
    local b = ids(opts.trace_id, opts)
    return {
        id = b.id,
        object = "chat.completion",
        created = b.created,
        model = b.model,
        choices = {
            {
                index = 0,
                message = { role = "assistant", content = answer },
                finish_reason = "stop",
            },
        },
        usage = { prompt_tokens = 0, completion_tokens = 0, total_tokens = 0 },
        virbius = virbius_ext(opts),
    }
end

function _M.sse_chunks(answer, opts)
    opts = opts or {}
    local b = ids(opts.trace_id, opts)
    local ext = virbius_ext(opts)
    return {
        {
            id = b.id,
            object = "chat.completion.chunk",
            created = b.created,
            model = b.model,
            choices = { { index = 0, delta = { role = "assistant", content = "" } } },
            virbius = ext,
        },
        {
            id = b.id,
            object = "chat.completion.chunk",
            created = b.created,
            model = b.model,
            choices = { { index = 0, delta = { content = answer } } },
        },
        {
            id = b.id,
            object = "chat.completion.chunk",
            created = b.created,
            model = b.model,
            choices = { { index = 0, delta = {}, finish_reason = "stop" } },
        },
    }
end

return _M
