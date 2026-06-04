use serde::Deserialize;

#[derive(Debug, Clone, Default)]
pub struct BindContext {
    pub scene: String,
    pub route_uri: Option<String>,
    pub app_id: Option<String>,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct BindRefBlock {
    #[serde(default)]
    pub scenes: Vec<String>,
    #[serde(default)]
    pub uris: Vec<String>,
    #[serde(default)]
    pub app_ids: Vec<String>,
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
    let Some(app_id) = ctx.app_id.as_deref() else {
        return false;
    };
    if app_id.is_empty() {
        return false;
    }
    r.app_ids.iter().any(|id| id == app_id)
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
            scene: "beta_chat".into(),
            route_uri: Some("/v1/other".into()),
            ..Default::default()
        };
        let bind_ref = BindRefBlock {
            uris: vec!["/v1/chat/completions".into()],
            scenes: vec!["beta_chat".into()],
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

    #[test]
    fn service_app_ids() {
        let ctx = BindContext {
            app_id: Some("medical-prod".into()),
            ..Default::default()
        };
        let bind_ref = BindRefBlock {
            app_ids: vec!["beta".into(), "medical-prod".into()],
            ..Default::default()
        };
        assert!(matches_service(Some(&bind_ref), &ctx));
        let ctx2 = BindContext {
            app_id: Some("unknown".into()),
            ..Default::default()
        };
        assert!(!matches_service(Some(&bind_ref), &ctx2));
    }
}
