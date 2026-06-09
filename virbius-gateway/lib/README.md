# virbius-gateway/lib

Shared Lua modules for APISIX `virbius-guard` and OpenResty `access.lua`.

| Module | Role |
|--------|------|
| `context_vars.lua` | `context_bindings` → logical vars |
| `scene_registry.lua` | Load + `resolve_scene(app_id, uri, query, headers)` |
| `access_lists.lua` | Gateway access list match + merge |
| `uri_match.lua` | URI pattern (`*` prefix) |
| `file_cache.lua` | JSON file load + mtime cache |
| `prompt.lua` | Extract chat prompt from request body |
| `trace.lua` | Trace id validation |
| `effective.lua` | Load compiler `effective-*.json` (OpenResty) |
