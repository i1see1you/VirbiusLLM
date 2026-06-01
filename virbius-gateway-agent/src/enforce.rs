use crate::engine::Signal;

#[derive(Debug, Clone)]
pub struct RuleHit {
    pub rule_id: String,
    pub rule_revision: i32,
    pub reason_code: String,
    pub risk_score: i32,
    pub intent_action: String,
    pub enforce_mode: String,
    pub canary_percent: Option<i32>,
}

#[derive(Debug, Clone)]
pub struct GatewayCheckResult {
    pub effective_action: String,
    pub max_risk_score: i32,
    pub primary: Option<RuleHit>,
    pub prior_signals: Vec<Signal>,
}

pub fn in_canary_bucket(session_id: Option<&str>, percent: i32) -> bool {
    if percent >= 100 {
        return true;
    }
    if percent <= 0 {
        return false;
    }
    let key = session_id.filter(|s| !s.is_empty()).unwrap_or("default");
    let crc = crc32fast::hash(key.as_bytes());
    ((crc % 100) as i32) < percent
}

fn intent_priority(intent: &str) -> i32 {
    match intent.trim().to_ascii_lowercase().as_str() {
        "deny" => 100,
        "captcha" => 50,
        "review" => 30,
        _ => 0,
    }
}

fn is_allow_intent(intent: &str) -> bool {
    matches!(intent.trim().to_ascii_lowercase().as_str(), "allow")
}

fn normalize_intent(intent: &str, risk_score: i32) -> String {
    let t = intent.trim().to_ascii_lowercase();
    if matches!(t.as_str(), "allow" | "deny" | "captcha" | "review") {
        return t;
    }
    if risk_score >= 100 {
        "deny".into()
    } else if risk_score <= 0 {
        "allow".into()
    } else {
        "review".into()
    }
}

fn is_full(mode: &str) -> bool {
    mode.trim().eq_ignore_ascii_case("full")
}

fn is_canary_effective(hit: &RuleHit, session_id: Option<&str>) -> bool {
    if !hit.enforce_mode.trim().eq_ignore_ascii_case("canary") {
        return false;
    }
    let pct = hit.canary_percent.unwrap_or(0);
    in_canary_bucket(session_id, pct)
}

fn effective_enforce(hits: &[&RuleHit], session_id: Option<&str>) -> bool {
    hits.iter().any(|h| is_full(&h.enforce_mode))
        || hits.iter().any(|h| is_canary_effective(h, session_id))
}

fn pick_primary(hits: &[RuleHit]) -> Option<RuleHit> {
    hits.iter()
        .max_by(|a, b| {
            a.risk_score
                .cmp(&b.risk_score)
                .then_with(|| a.rule_revision.cmp(&b.rule_revision))
                .then_with(|| a.rule_id.cmp(&b.rule_id))
        })
        .cloned()
}

pub fn merge(hits: &[RuleHit], session_id: Option<&str>) -> GatewayCheckResult {
    if hits.is_empty() {
        return GatewayCheckResult {
            effective_action: "allow".into(),
            max_risk_score: 0,
            primary: None,
            prior_signals: Vec::new(),
        };
    }
    if hits.iter().any(|h| is_allow_intent(&h.intent_action)) {
        return GatewayCheckResult {
            effective_action: "allow".into(),
            max_risk_score: 0,
            primary: None,
            prior_signals: Vec::new(),
        };
    }
    let max_priority = hits.iter().map(|h| intent_priority(&h.intent_action)).max().unwrap_or(0);
    if max_priority <= 0 {
        return GatewayCheckResult {
            effective_action: "allow".into(),
            max_risk_score: 0,
            primary: None,
            prior_signals: Vec::new(),
        };
    }
    let top: Vec<RuleHit> = hits
        .iter()
        .filter(|h| intent_priority(&h.intent_action) == max_priority)
        .cloned()
        .collect();
    let max_risk_score = top.iter().map(|h| h.risk_score).max().unwrap_or(0);
    let primary = pick_primary(&top);
    let intent = primary
        .as_ref()
        .map(|p| normalize_intent(&p.intent_action, p.risk_score))
        .unwrap_or_else(|| "allow".into());
    let refs: Vec<&RuleHit> = top.iter().collect();
    let effective = effective_enforce(&refs, session_id);
    let effective_action = match intent.as_str() {
        "deny" => {
            if effective {
                "block"
            } else {
                "review"
            }
        }
        "captcha" => {
            if effective {
                "captcha"
            } else {
                "review"
            }
        }
        "review" => "review",
        _ => "allow",
    };
    let terminal = effective_action == "block" || effective_action == "captcha";
    let prior_signals = if effective_action == "review" {
        hits_to_prior_signals(hits)
    } else {
        Vec::new()
    };
    GatewayCheckResult {
        effective_action: effective_action.to_string(),
        max_risk_score,
        primary,
        prior_signals: if terminal { Vec::new() } else { prior_signals },
    }
}

pub fn is_terminal(action: &str) -> bool {
    action == "block" || action == "captcha"
}

fn hits_to_prior_signals(hits: &[RuleHit]) -> Vec<Signal> {
    hits.iter()
        .filter(|h| !is_allow_intent(&h.intent_action))
        .map(|h| Signal {
            rule_id: h.rule_id.clone(),
            rule_revision: h.rule_revision,
            source: "gateway".into(),
            layer: "gateway".into(),
            score: Some(h.risk_score as f64),
            reason_code: Some(h.reason_code.clone()),
            intent_action: Some(h.intent_action.clone()),
            enforce_mode: Some(h.enforce_mode.clone()),
            canary_percent: h.canary_percent,
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hit(intent: &str, enforce: &str, score: i32) -> RuleHit {
        RuleHit {
            rule_id: "r1".into(),
            rule_revision: 1,
            reason_code: "X".into(),
            risk_score: score,
            intent_action: intent.into(),
            enforce_mode: enforce.into(),
            canary_percent: None,
        }
    }

    #[test]
    fn dry_run_deny_is_review() {
        let m = merge(&[hit("deny", "dry_run", 100)], Some("sess-a"));
        assert_eq!(m.effective_action, "review");
        assert_eq!(m.max_risk_score, 100);
    }

    #[test]
    fn full_deny_is_block() {
        let m = merge(&[hit("deny", "full", 100)], None);
        assert_eq!(m.effective_action, "block");
    }

    #[test]
    fn deny_beats_captcha() {
        let m = merge(
            &[hit("deny", "dry_run", 100), hit("captcha", "full", 80)],
            None,
        );
        assert_eq!(m.effective_action, "review");
        assert_eq!(m.max_risk_score, 100);
    }
}
