use serde::Deserialize;

#[derive(Debug, Clone, Default)]
pub struct BindContext {
    pub scene: String,
    pub app_id: Option<String>,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct BindRefBlock {
    #[serde(default)]
    pub scenes: Vec<String>,
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
    if r.scenes.is_empty() {
        return false;
    }
    if ctx.scene.is_empty() {
        return false;
    }
    r.scenes.iter().any(|s| s == "*" || s == &ctx.scene)
}

#[cfg(test)]
mod tests {
    use super::*;

    // ── Scene path (primary) ──

    #[test]
    fn route_scene_match() {
        let ctx = BindContext {
            scene: "chat".into(),
            ..Default::default()
        };
        let bind_ref = BindRefBlock {
            scenes: vec!["chat".into(), "sse".into()],
            ..Default::default()
        };
        assert!(matches_route(Some(&bind_ref), &ctx));
    }

    #[test]
    fn route_scene_wildcard() {
        let ctx = BindContext {
            scene: "any_scene".into(),
            ..Default::default()
        };
        let bind_ref = BindRefBlock {
            scenes: vec!["*".into()],
            ..Default::default()
        };
        assert!(matches_route(Some(&bind_ref), &ctx));
    }

    #[test]
    fn route_scene_no_match() {
        let ctx = BindContext {
            scene: "chat".into(),
            ..Default::default()
        };
        let bind_ref = BindRefBlock {
            scenes: vec!["sse".into(), "streaming".into()],
            ..Default::default()
        };
        assert!(!matches_route(Some(&bind_ref), &ctx));
    }

    #[test]
    fn route_scene_missing() {
        let ctx = BindContext {
            scene: String::new(),
            ..Default::default()
        };
        let bind_ref = BindRefBlock {
            scenes: vec!["chat".into()],
            ..Default::default()
        };
        assert!(!matches_route(Some(&bind_ref), &ctx));
    }

    #[test]
    fn route_no_bind_ref() {
        let ctx = BindContext {
            scene: "chat".into(),
            ..Default::default()
        };
        assert!(!matches_route(None, &ctx));
    }

    // ── Service ──

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
