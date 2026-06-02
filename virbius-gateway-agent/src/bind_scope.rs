use serde::Deserialize;

#[derive(Debug, Clone, Default)]
pub struct BindContext {
    pub scene: String,
    pub route_uri: Option<String>,
    pub upstream_id: Option<String>,
    pub consumer_id: Option<String>,
    pub api_key_group: Option<String>,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct BindRefBlock {
    #[serde(default)]
    pub scenes: Vec<String>,
    #[serde(default)]
    pub uris: Vec<String>,
    #[serde(default)]
    pub upstream_id: Option<String>,
    #[serde(default)]
    pub consumer_id: Option<String>,
    #[serde(default)]
    pub api_key_group: Option<String>,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct BindRuleBlock {
    #[serde(default = "default_bind_scope")]
    pub bind_scope: String,
    #[serde(default)]
    pub bind_ref: Option<BindRefBlock>,
}

fn default_bind_scope() -> String {
    "global".into()
}

pub fn matches_bind(bind_scope: &str, bind_ref: Option<&BindRefBlock>, ctx: &BindContext) -> bool {
    match bind_scope.trim().to_ascii_lowercase().as_str() {
        "service" => matches_service(bind_ref, ctx),
        "route" => matches_route(bind_ref, ctx),
        _ => true,
    }
}

pub fn matches_bind_rule(rule: &BindRuleBlock, ctx: &BindContext) -> bool {
    matches_bind(&rule.bind_scope, rule.bind_ref.as_ref(), ctx)
}

fn matches_service(bind_ref: Option<&BindRefBlock>, ctx: &BindContext) -> bool {
    let Some(r) = bind_ref else {
        return false;
    };
    let mut any = false;
    if let Some(expected) = r.upstream_id.as_deref() {
        any |= ctx.upstream_id.as_deref() == Some(expected);
    }
    if let Some(expected) = r.consumer_id.as_deref() {
        any |= ctx.consumer_id.as_deref() == Some(expected);
    }
    if let Some(expected) = r.api_key_group.as_deref() {
        any |= ctx.api_key_group.as_deref() == Some(expected);
    }
    any
}

fn matches_route(bind_ref: Option<&BindRefBlock>, ctx: &BindContext) -> bool {
    let Some(r) = bind_ref else {
        return false;
    };
    if !r.uris.is_empty() {
        let Some(uri) = normalize_uri(ctx.route_uri.as_deref()) else {
            return false;
        };
        return r.uris.iter().any(|pat| uri_matches(&uri, pat));
    }
    if !r.scenes.is_empty() {
        if ctx.scene.is_empty() {
            return false;
        }
        return r
            .scenes
            .iter()
            .any(|s| s == "*" || s == &ctx.scene);
    }
    false
}

pub fn normalize_uri(raw: Option<&str>) -> Option<String> {
    let mut s = raw?.trim();
    if s.is_empty() {
        return None;
    }
    if let Some(q) = s.find('?') {
        s = &s[..q];
    }
    if let Some(h) = s.find('#') {
        s = &s[..h];
    }
    let mut out = s.to_string();
    if !out.starts_with('/') {
        out.insert(0, '/');
    }
    Some(out)
}

pub fn uri_matches(route_uri: &str, pattern: &str) -> bool {
    let Some(uri) = normalize_uri(Some(route_uri)) else {
        return false;
    };
    let Some(pat) = normalize_uri(Some(pattern)) else {
        return false;
    };
    if let Some(prefix) = pat.strip_suffix('*') {
        return uri.starts_with(prefix);
    }
    uri == pat
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn route_uri_priority() {
        let ctx = BindContext {
            scene: "general_chat".into(),
            route_uri: Some("/v1/other".into()),
            ..Default::default()
        };
        let bind_ref = BindRefBlock {
            uris: vec!["/v1/chat/completions".into()],
            scenes: vec!["general_chat".into()],
            ..Default::default()
        };
        assert!(!matches_route(Some(&bind_ref), &ctx));
    }

    #[test]
    fn route_uri_prefix() {
        let ctx = BindContext {
            route_uri: Some("/v1/chat/completions".into()),
            ..Default::default()
        };
        let bind_ref = BindRefBlock {
            uris: vec!["/v1/chat/*".into()],
            ..Default::default()
        };
        assert!(matches_route(Some(&bind_ref), &ctx));
    }
}
