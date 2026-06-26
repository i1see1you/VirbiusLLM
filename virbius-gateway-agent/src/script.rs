use crate::list_redis::{match_redis_list_by_name, RedisListIndexBlock};
use crate::policy_engine::{match_list_by_name, read_cumulative_count, CumulativeDefBlock, ListDefBlock};
use crate::access_lists::ExtendedVars;
use mlua::{Lua, MultiValue, Value};
use std::collections::HashMap;

pub struct ScriptEnv<'a> {
    pub content: &'a str,
    pub user_id: Option<&'a str>,
    pub device_id: Option<&'a str>,
    pub client_ip: Option<&'a str>,
    pub session_id: Option<&'a str>,
    pub vars: &'a HashMap<String, String>,
    pub tenant_id: &'a str,
    pub lists: &'a [ListDefBlock],
    pub redis_list_index: &'a [RedisListIndexBlock],
    pub cumulatives: &'a [CumulativeDefBlock],
}

pub fn run_lua_decide(body: &str, env: &ScriptEnv<'_>) -> Result<bool, String> {
    let lua = Lua::new();
    let globals = lua.globals();

    let ctx = lua.create_table().map_err(|e| e.to_string())?;
    ctx.set("content", env.content).map_err(|e| e.to_string())?;
    if let Some(v) = env.user_id {
        ctx.set("user_id", v).map_err(|e| e.to_string())?;
    }
    if let Some(v) = env.device_id {
        ctx.set("device_id", v).map_err(|e| e.to_string())?;
    }
    if let Some(v) = env.client_ip {
        ctx.set("client_ip", v).map_err(|e| e.to_string())?;
    }
    if let Some(v) = env.session_id {
        ctx.set("session_id", v).map_err(|e| e.to_string())?;
    }

    let vars_tbl = lua.create_table().map_err(|e| e.to_string())?;
    for (k, v) in env.vars {
        vars_tbl.set(k.as_str(), v.as_str()).map_err(|e| e.to_string())?;
    }
    ctx.set("vars", vars_tbl).map_err(|e| e.to_string())?;

    let vars_for_var = env.vars.clone();
    ctx.set(
        "var",
        lua.create_function(move |_, name: String| Ok(vars_for_var.get(&name).cloned().unwrap_or_default()))
            .map_err(|e| e.to_string())?,
    )
    .map_err(|e| e.to_string())?;

    let lists = env.lists.to_vec();
    let redis_index = env.redis_list_index.to_vec();
    let cumulatives = env.cumulatives.to_vec();
    let content = env.content.to_string();
    let user_id = env.user_id.map(|s| s.to_string());
    let device_id = env.device_id.map(|s| s.to_string());
    let client_ip = env.client_ip.map(|s| s.to_string());
    let session_id = env.session_id.map(|s| s.to_string());
    let vars = env.vars.clone();
    let tenant = env.tenant_id.to_string();

    let content_lm = content.clone();
    let user_id_lm = user_id.clone();
    let device_id_lm = device_id.clone();
    let client_ip_lm = client_ip.clone();
    let session_id_lm = session_id.clone();
    let vars_lm = vars.clone();

    let tenant_lm = tenant.clone();
    let tenant_cum = tenant.clone();

    globals
        .set(
            "listMatch",
            lua.create_function(move |_, args: MultiValue| {
                let mut iter = args.into_iter();
                let name = match iter.next() {
                    Some(Value::String(s)) => s.to_str()?.to_string(),
                    _ => return Ok(false),
                };
                let explicit = match iter.next() {
                    Some(Value::String(s)) => Some(s.to_str()?.to_string()),
                    Some(Value::Integer(i)) => Some(i.to_string()),
                    Some(Value::Number(n)) => Some(n.to_string()),
                    _ => None,
                };
                Ok(if match_list_by_name(
                    &lists,
                    &name,
                    explicit.as_deref(),
                    &content_lm,
                    user_id_lm.as_deref(),
                    device_id_lm.as_deref(),
                    client_ip_lm.as_deref(),
                    session_id_lm.as_deref(),
                    &vars_lm,
                ) {
                    true
                } else {
                    match_redis_list_by_name(
                        &tenant_lm,
                        &redis_index,
                        &name,
                        explicit.as_deref(),
                        &content_lm,
                        user_id_lm.as_deref(),
                        device_id_lm.as_deref(),
                        client_ip_lm.as_deref(),
                        &vars_lm,
                    )
                })
            })
            .map_err(|e| e.to_string())?,
        )
        .map_err(|e| e.to_string())?;

    globals
        .set(
            "getCumulative",
            lua.create_function(move |_, name: String| {
                Ok(read_cumulative_count(
                    &tenant_cum,
                    &cumulatives,
                    &name,
                    &content,
                    user_id.as_deref(),
                    device_id.as_deref(),
                    client_ip.as_deref(),
                    session_id.as_deref(),
                    &vars,
                ))
            })
            .map_err(|e| e.to_string())?,
        )
        .map_err(|e| e.to_string())?;

    globals.set("ctx", ctx).map_err(|e| e.to_string())?;

    let script = if body.contains("function decide") || body.contains("decide(") {
        format!("{body}\nreturn decide(ctx)")
    } else {
        format!("function decide(ctx)\n{body}\nend\nreturn decide(ctx)")
    };

    let result: Value = lua.load(&script).eval().map_err(|e| e.to_string())?;
    Ok(value_to_bool(result))
}

fn value_to_bool(v: Value) -> bool {
    match v {
        Value::Boolean(b) => b,
        Value::Integer(i) => i != 0,
        Value::Number(n) => n != 0.0,
        Value::String(s) => {
            let t = s.to_str().map(|x| x.to_string()).unwrap_or_default();
            !t.is_empty() && !t.eq_ignore_ascii_case("false") && !t.eq_ignore_ascii_case("allow")
        }
        _ => false,
    }
}

pub fn compute_extended_vars(
    extended: &ExtendedVars,
    vars: &HashMap<String, String>,
    app_id: Option<&str>,
    scene: Option<&str>,
) -> HashMap<String, String> {
    let mut out = HashMap::new();
    for (logical, def) in &extended.vars {
        if def.expr.is_empty() {
            continue;
        }
        if let Some(ref scope) = def.scope {
            match scope.bind_scope.as_str() {
                "service" => {
                    if !scope.app_ids.is_empty() {
                        let Some(aid) = app_id else { continue };
                        if !scope.app_ids.iter().any(|id| id == aid) {
                            continue;
                        }
                    }
                }
                "route" => {
                    if !scope.scenes.is_empty() {
                        let Some(s) = scene else { continue };
                        if !scope.scenes.iter().any(|sc| sc == s) {
                            continue;
                        }
                    }
                }
                _ => {}
            }
        }
        let lua = Lua::new();
        let globals = lua.globals();
        let ctx = match lua.create_table() {
            Ok(t) => t,
            Err(_) => continue,
        };
        let vars_for_fn = vars.clone();
        if let Ok(var_fn) = lua.create_function(move |_, name: String| {
            Ok(vars_for_fn.get(&name).cloned().unwrap_or_default())
        }) {
            let _ = ctx.set("var", var_fn);
        }
        let _ = globals.set("ctx", ctx);
        let script = format!("return {}", def.expr);
        match lua.load(&script).eval::<Value>() {
            Ok(val) => {
                if let Some(s) = lua_value_to_string(&val) {
                    if !s.is_empty() {
                        out.insert(logical.clone(), s);
                    }
                }
            }
            Err(e) => {
                eprintln!("extended var {} eval error: {}", logical, e);
            }
        }
    }
    out
}

fn lua_value_to_string(v: &Value) -> Option<String> {
    match v {
        Value::String(s) => s.to_str().ok().map(|x| x.to_string()),
        Value::Integer(i) => Some(i.to_string()),
        Value::Number(n) => Some(n.to_string()),
        Value::Boolean(b) => Some(b.to_string()),
        Value::Nil => None,
        _ => None,
    }
}
