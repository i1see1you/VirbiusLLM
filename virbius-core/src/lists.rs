use serde::Deserialize;
use std::{env, fs, path::PathBuf, sync::OnceLock};

#[derive(Debug, Default, Clone, Deserialize)]
struct KeywordSide {
    #[serde(default)]
    keywords: Vec<String>,
}

#[derive(Debug, Default, Clone, Deserialize)]
struct EdgeListsFile {
    #[serde(default)]
    deny: KeywordSide,
    #[serde(default)]
    allow: KeywordSide,
}

static LISTS: OnceLock<EdgeListsFile> = OnceLock::new();

pub fn init_from_env() {
    let _ = load_lists();
}

fn load_lists() -> &'static EdgeListsFile {
    LISTS.get_or_init(|| {
        let path = lists_path();
        if let Ok(raw) = fs::read_to_string(&path) {
            if let Ok(parsed) = serde_json::from_str::<EdgeListsFile>(&raw) {
                return parsed;
            }
        }
        EdgeListsFile::default()
    })
}

fn lists_path() -> PathBuf {
    if let Ok(p) = env::var("VIRBIUS_EDGE_LISTS_PATH") {
        return PathBuf::from(p);
    }
    let data = env::var("VIRBIUS_DATA_DIR").unwrap_or_else(|_| "./data".into());
    let tenant = env::var("VIRBIUS_TENANT_ID").unwrap_or_else(|_| "default".into());
    PathBuf::from(data)
        .join("edge")
        .join(format!("{tenant}-content-lists.json"))
}

pub struct EdgeHit {
    pub rule_id: &'static str,
    pub reason_code: &'static str,
}

pub fn scan_content(content: &str) -> Option<EdgeHit> {
    let lists = load_lists();
    if keyword_hit(content, &lists.allow.keywords) {
        return None;
    }
    if keyword_hit(content, &lists.deny.keywords) {
        return Some(EdgeHit {
            rule_id: "edge_l0_content_deny",
            reason_code: "EDGE_CONTENT_KEYWORD_DENY",
        });
    }
    None
}

fn keyword_hit(content: &str, keywords: &[String]) -> bool {
    if content.is_empty() {
        return false;
    }
    let lower = content.to_ascii_lowercase();
    keywords.iter().any(|kw| {
        if kw.is_empty() {
            return false;
        }
        if kw.chars().any(|c| c > '\u{007f}') {
            content.contains(kw.as_str())
        } else {
            lower.contains(&kw.to_ascii_lowercase())
        }
    })
}
