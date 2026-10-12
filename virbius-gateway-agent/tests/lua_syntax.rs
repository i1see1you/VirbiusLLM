use std::path::PathBuf;

fn gateway_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("..")
        .join("virbius-gateway")
}

fn check(relative: &str) {
    let path = gateway_dir().join(relative);
    let code =
        std::fs::read_to_string(&path).unwrap_or_else(|e| panic!("read {}: {e}", path.display()));
    let lua = mlua::Lua::new();
    if let Err(e) = lua.load(&code).into_function() {
        panic!("lua syntax error in {relative}: {e}");
    }
}

#[test]
fn lua_shared_modules_compile() {
    check("lib/prompt.lua");
    check("lib/reply.lua");
}

#[test]
fn lua_gateway_plugins_compile() {
    check("plugins/apisix/virbius-guard.lua");
    check("plugins/openresty/access.lua");
}
