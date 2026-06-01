use crate::enforce::RuleHit;
use chrono::{DateTime, Utc};
use serde::Deserialize;
use std::collections::HashMap;

#[derive(Debug, Clone, Default, Deserialize)]
pub struct ValueSourceDef {
    #[serde(default)]
    pub kind: String,
    #[serde(default)]
    pub r#ref: Option<String>,
    #[serde(default)]
    pub value: Option<String>,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[allow(dead_code)]
pub struct ListEntryDef {
    #[serde(default)]
    pub value: String,
    #[serde(default)]
    pub remark: Option<String>,
    #[serde(default)]
    pub created_at: Option<String>,
    #[serde(default)]
    pub expires_at: Option<String>,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[allow(dead_code)]
pub struct NamedListBlock {
    #[serde(default)]
    pub list_name: String,
    #[serde(default)]
    pub dimension: String,
    #[serde(default)]
    pub remark: Option<String>,
    #[serde(default)]
    pub rule_id: String,
    #[serde(default)]
    pub rule_revision: i32,
    #[serde(default)]
    pub reason_code: String,
    #[serde(default)]
    pub risk_score: i32,
    #[serde(default)]
    pub intent_action: String,
    #[serde(default)]
    pub enforce_mode: String,
    #[serde(default)]
    pub canary_percent: Option<i32>,
    #[serde(default)]
    pub entries: Vec<ListEntryDef>,
    #[serde(default)]
    pub value_source: Option<ValueSourceDef>,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct CumulativeRuleBlock {
    #[serde(default)]
    pub cumulative_name: String,
    #[serde(default)]
    pub dimension: String,
    #[serde(default)]
    pub window_kind: String,
    #[serde(default)]
    pub window_minutes: Option<i32>,
    #[serde(default)]
    pub window_hours: Option<i32>,
    #[serde(default)]
    pub timezone: Option<String>,
    #[serde(default)]
    pub threshold: i32,
    #[serde(default)]
    pub compare_op: Option<String>,
    #[serde(default)]
    pub rule_id: String,
    #[serde(default)]
    pub rule_revision: i32,
    #[serde(default)]
    pub reason_code: String,
    #[serde(default)]
    pub risk_score: i32,
    #[serde(default)]
    pub intent_action: String,
    #[serde(default)]
    pub enforce_mode: String,
    #[serde(default)]
    pub canary_percent: Option<i32>,
    #[serde(default)]
    pub value_source: Option<ValueSourceDef>,
}

pub fn resolve_value(
    dimension: &str,
    vs: Option<&ValueSourceDef>,
    content: &str,
    user_id: Option<&str>,
    device_id: Option<&str>,
    client_ip: Option<&str>,
    session_id: Option<&str>,
    vars: &HashMap<String, String>,
) -> Option<String> {
    if let Some(src) = vs {
        if !src.kind.is_empty() && src.kind != "default" {
            let v = resolve_source(src, content, user_id, device_id, client_ip, session_id, vars);
            return normalize(v);
        }
    }
    normalize(resolve_dimension(
        dimension, content, user_id, device_id, client_ip, session_id, vars,
    ))
}

fn resolve_source(
    src: &ValueSourceDef,
    content: &str,
    user_id: Option<&str>,
    device_id: Option<&str>,
    client_ip: Option<&str>,
    session_id: Option<&str>,
    vars: &HashMap<String, String>,
) -> Option<String> {
    match src.kind.as_str() {
        "literal" => src.value.clone(),
        "request_field" => resolve_request_field(src.r#ref.as_deref(), user_id, device_id, client_ip, session_id),
        "var" => src.r#ref.as_ref().and_then(|k| vars.get(k)).cloned(),
        "content" => Some(content.to_string()),
        _ => None,
    }
}

fn resolve_dimension(
    dimension: &str,
    content: &str,
    user_id: Option<&str>,
    device_id: Option<&str>,
    client_ip: Option<&str>,
    session_id: Option<&str>,
    vars: &HashMap<String, String>,
) -> Option<String> {
    if dimension.starts_with("var:") {
        return vars.get(&dimension[4..]).cloned();
    }
    match dimension {
        "user_id" => user_id.map(|s| s.to_string()),
        "device_id" => device_id.map(|s| s.to_string()),
        "ip" | "ip_cidr" => client_ip.map(|s| s.to_string()),
        "session_id" => session_id.map(|s| s.to_string()),
        "keyword" | "content" => Some(content.to_string()),
        "var" => None,
        other => resolve_request_field(Some(other), user_id, device_id, client_ip, session_id),
    }
}

fn resolve_request_field(
    field: Option<&str>,
    user_id: Option<&str>,
    device_id: Option<&str>,
    client_ip: Option<&str>,
    session_id: Option<&str>,
) -> Option<String> {
    match field.unwrap_or("") {
        "user_id" => user_id.map(|s| s.to_string()),
        "device_id" => device_id.map(|s| s.to_string()),
        "client_ip" | "ip" => client_ip.map(|s| s.to_string()),
        "session_id" => session_id.map(|s| s.to_string()),
        "content" => None,
        _ => None,
    }
}

fn normalize(raw: Option<String>) -> Option<String> {
    raw.map(|s| s.trim().to_string()).filter(|s| !s.is_empty() && s.len() <= 256)
}

fn entry_active(entry: &ListEntryDef) -> bool {
    if let Some(ref exp) = entry.expires_at {
        if let Ok(dt) = exp.parse::<DateTime<Utc>>() {
            if dt <= Utc::now() {
                return false;
            }
        }
    }
    true
}

fn active_values(block: &NamedListBlock) -> Vec<&str> {
    block
        .entries
        .iter()
        .filter(|e| entry_active(e) && !e.value.is_empty())
        .map(|e| e.value.as_str())
        .collect()
}

pub fn match_list(block: &NamedListBlock, value: &str, content: &str) -> bool {
    let values = active_values(block);
    if values.is_empty() {
        return false;
    }
    if value.is_empty() && block.dimension != "keyword" {
        return false;
    }
    if block.dimension == "keyword" {
        let lower = content.to_ascii_lowercase();
        return values.iter().any(|kw| {
            if kw.chars().any(|c| c > '\u{007f}') {
                content.contains(kw)
            } else {
                lower.contains(&kw.to_ascii_lowercase())
            }
        });
    }
    values
        .iter()
        .any(|e| *e == value || (block.dimension == "var" && var_hit(value, e)))
}

fn var_hit(actual: &str, entry: &str) -> bool {
    let Some(eq) = entry.find('=') else {
        return entry == actual;
    };
    if eq == 0 || eq >= entry.len() - 1 {
        return false;
    }
    actual == &entry[eq + 1..]
}

pub fn collect_named_list_hits(
    lists: &[NamedListBlock],
    content: &str,
    user_id: Option<&str>,
    device_id: Option<&str>,
    client_ip: Option<&str>,
    session_id: Option<&str>,
    vars: &HashMap<String, String>,
) -> Vec<RuleHit> {
    let mut hits = Vec::new();
    for block in lists {
        let value = resolve_value(
            &block.dimension,
            block.value_source.as_ref(),
            content,
            user_id,
            device_id,
            client_ip,
            session_id,
            vars,
        );
        let Some(v) = value else { continue };
        if !match_list(block, &v, content) {
            continue;
        }
        if block.risk_score <= 0 || block.intent_action.eq_ignore_ascii_case("allow") {
            return Vec::new();
        }
        hits.push(hit_from_list_block(block));
    }
    hits
}

pub fn hit_from_list_block(block: &NamedListBlock) -> RuleHit {
    RuleHit {
        rule_id: if block.rule_id.is_empty() {
            block.list_name.clone()
        } else {
            block.rule_id.clone()
        },
        rule_revision: block.rule_revision.max(1),
        reason_code: if block.reason_code.is_empty() {
            "LIST_MATCH".into()
        } else {
            block.reason_code.clone()
        },
        risk_score: block.risk_score,
        intent_action: if block.intent_action.is_empty() {
            if block.risk_score >= 100 {
                "deny".into()
            } else if block.risk_score <= 0 {
                "allow".into()
            } else {
                "review".into()
            }
        } else {
            block.intent_action.clone()
        },
        enforce_mode: if block.enforce_mode.is_empty() {
            "dry_run".into()
        } else {
            block.enforce_mode.clone()
        },
        canary_percent: block.canary_percent,
    }
}

pub fn hit_from_cumulative_block(block: &CumulativeRuleBlock) -> RuleHit {
    RuleHit {
        rule_id: if block.rule_id.is_empty() {
            block.cumulative_name.clone()
        } else {
            block.rule_id.clone()
        },
        rule_revision: block.rule_revision.max(1),
        reason_code: if block.reason_code.is_empty() {
            "CUMULATIVE_EXCEEDED".into()
        } else {
            block.reason_code.clone()
        },
        risk_score: if block.risk_score > 0 { block.risk_score } else { 100 },
        intent_action: if block.intent_action.is_empty() {
            if block.risk_score >= 100 {
                "deny".into()
            } else {
                "review".into()
            }
        } else {
            block.intent_action.clone()
        },
        enforce_mode: if block.enforce_mode.is_empty() {
            "dry_run".into()
        } else {
            block.enforce_mode.clone()
        },
        canary_percent: block.canary_percent,
    }
}
