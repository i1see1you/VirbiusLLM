use aho_corasick::AhoCorasick;
use chrono::Utc;
use redis::Commands;
use regex::RegexSet;
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    env, fs,
    path::PathBuf,
    sync::{Arc, RwLock},
    time::{Duration, Instant},
};
use unicode_normalization::UnicodeNormalization;

const SUPPORTED_SCHEMA_VERSION: i64 = 1;

#[derive(Debug, Clone, Deserialize, Serialize, Default)]
pub struct QaSnapshot {
    #[serde(default)]
    pub schema_version: i64,
    #[serde(default)]
    pub revision: i64,
    #[serde(default)]
    pub rollout_state: String,
    #[serde(default)]
    pub canary_percent: i32,
    #[serde(default)]
    pub entries: Vec<QaEntryDef>,
}

fn default_match_mode() -> String {
    "exact".into()
}

fn default_enabled() -> bool {
    true
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct QaEntryDef {
    pub entry_id: String,
    #[serde(default)]
    pub question: String,
    #[serde(default)]
    pub variants: Vec<String>,
    #[serde(default)]
    pub answer: String,
    #[serde(default = "default_match_mode")]
    pub match_mode: String,
    #[serde(default)]
    pub priority: i32,
    #[serde(default = "default_enabled")]
    pub enabled: bool,
    #[serde(default)]
    pub expires_at: Option<String>,
}

#[derive(Debug, Clone)]
pub struct QaCandidate {
    pub entry_id: String,
    pub mode: String,
    pub answer: String,
    pub revision: i32,
    pub rollout_state: String,
    pub canary_percent: i32,
}

#[derive(Debug, PartialEq)]
pub enum ConsumeDecision {
    Reply,
    Suppressed(&'static str),
    DryRunCandidate,
    Skip,
}

pub fn decide_consume(
    final_action: &str,
    degraded: bool,
    rollout_state: &str,
    in_bucket: bool,
) -> ConsumeDecision {
    let action = final_action.trim().to_ascii_lowercase();
    if action == "deny" || action == "block" {
        return ConsumeDecision::Suppressed("deny");
    }
    if action == "captcha" {
        return ConsumeDecision::Suppressed("captcha");
    }
    if action == "review" {
        return ConsumeDecision::Suppressed("review");
    }
    if degraded {
        return ConsumeDecision::Suppressed("degraded");
    }
    match rollout_state.trim().to_ascii_lowercase().as_str() {
        "full" => ConsumeDecision::Reply,
        "canary" => {
            if in_bucket {
                ConsumeDecision::Reply
            } else {
                ConsumeDecision::Skip
            }
        }
        "dry_run" => ConsumeDecision::DryRunCandidate,
        _ => ConsumeDecision::Skip,
    }
}

pub fn normalize(input: &str) -> String {
    let nfkc: String = input.nfkc().collect();
    let mut visible = String::with_capacity(nfkc.len());
    for ch in nfkc.chars() {
        if !is_invisible(ch) {
            visible.push(ch);
        }
    }
    let lowered = visible.to_lowercase();
    let mut collapsed = String::with_capacity(lowered.len());
    let mut in_ws = false;
    for ch in lowered.chars() {
        if ch.is_whitespace() {
            if !in_ws {
                collapsed.push(' ');
                in_ws = true;
            }
        } else {
            collapsed.push(ch);
            in_ws = false;
        }
    }
    collapsed.trim().to_string()
}

fn is_invisible(ch: char) -> bool {
    matches!(ch as u32, 0x200B..=0x200F | 0x2060..=0x2064 | 0xFEFF | 0x00AD)
}

fn is_expired(entry: &QaEntryDef) -> bool {
    entry
        .expires_at
        .as_deref()
        .and_then(|s| chrono::DateTime::parse_from_rfc3339(s).ok())
        .map(|t| t.with_timezone(&Utc) <= Utc::now())
        .unwrap_or(false)
}

#[derive(Debug)]
struct QaIndex {
    entries: Vec<QaEntryDef>,
    exact: HashMap<String, usize>,
    contains: Option<(AhoCorasick, Vec<usize>)>,
    regex: Option<(RegexSet, Vec<usize>)>,
}

impl QaIndex {
    fn build(snapshot: &QaSnapshot) -> Result<Self, Vec<String>> {
        if snapshot.schema_version > SUPPORTED_SCHEMA_VERSION {
            return Err(vec![format!(
                "unsupported schema_version {} (supported <= {})",
                snapshot.schema_version, SUPPORTED_SCHEMA_VERSION
            )]);
        }

        let mut errors: Vec<String> = Vec::new();
        let mut entries: Vec<QaEntryDef> = Vec::new();
        let mut exact: HashMap<String, usize> = HashMap::new();
        let mut contains_patterns: Vec<String> = Vec::new();
        let mut contains_map: Vec<usize> = Vec::new();
        let mut regex_patterns: Vec<String> = Vec::new();
        let mut regex_map: Vec<usize> = Vec::new();

        for e in &snapshot.entries {
            if !e.enabled || is_expired(e) {
                continue;
            }
            let idx = entries.len();
            let mode = e.match_mode.trim().to_ascii_lowercase();
            let mut questions: Vec<String> = vec![e.question.clone()];
            questions.extend(e.variants.iter().cloned());

            match mode.as_str() {
                "exact" => {
                    let mut any = false;
                    for q in &questions {
                        let n = normalize(q);
                        if n.is_empty() {
                            continue;
                        }
                        exact.insert(n, idx);
                        any = true;
                    }
                    if !any {
                        errors.push(format!(
                            "entry {}: exact mode requires non-empty question/variant",
                            e.entry_id
                        ));
                        continue;
                    }
                    entries.push(e.clone());
                }
                "contains" => {
                    let mut any = false;
                    for q in &questions {
                        let n = normalize(q);
                        if n.is_empty() {
                            continue;
                        }
                        contains_patterns.push(n);
                        contains_map.push(idx);
                        any = true;
                    }
                    if !any {
                        errors.push(format!(
                            "entry {}: contains mode requires non-empty question/variant",
                            e.entry_id
                        ));
                        continue;
                    }
                    entries.push(e.clone());
                }
                "regex" => {
                    let mut any = false;
                    for q in &questions {
                        if q.is_empty() {
                            continue;
                        }
                        match regex::Regex::new(q) {
                            Ok(_) => {
                                regex_patterns.push(q.clone());
                                regex_map.push(idx);
                                any = true;
                            }
                            Err(err) => {
                                errors.push(format!("entry {}: regex compile: {}", e.entry_id, err))
                            }
                        }
                    }
                    if !any {
                        if !errors
                            .iter()
                            .any(|x| x.starts_with(&format!("entry {}:", e.entry_id)))
                        {
                            errors.push(format!(
                                "entry {}: regex mode requires non-empty pattern",
                                e.entry_id
                            ));
                        }
                        continue;
                    }
                    entries.push(e.clone());
                }
                other => errors.push(format!(
                    "entry {}: unsupported match_mode {}",
                    e.entry_id, other
                )),
            }
        }

        if !errors.is_empty() {
            return Err(errors);
        }

        let contains = if contains_patterns.is_empty() {
            None
        } else {
            let ac = AhoCorasick::new(contains_patterns.iter())
                .map_err(|e| vec![format!("aho-corasick build: {e}")])?;
            Some((ac, contains_map))
        };
        let regex = if regex_patterns.is_empty() {
            None
        } else {
            let set = RegexSet::new(regex_patterns.iter())
                .map_err(|e| vec![format!("regex set build: {e}")])?;
            Some((set, regex_map))
        };

        Ok(Self {
            entries,
            exact,
            contains,
            regex,
        })
    }

    fn find(&self, norm_input: &str) -> Option<(usize, &'static str, f64)> {
        let mut cands: Vec<(usize, &'static str, f64)> = Vec::new();
        if let Some(&idx) = self.exact.get(norm_input) {
            cands.push((idx, "exact", 1.0));
        }
        if let Some((set, map)) = &self.regex {
            for pid in set.matches(norm_input).iter() {
                if let Some(&idx) = map.get(pid) {
                    cands.push((idx, "regex", 0.9));
                }
            }
        }
        if let Some((ac, map)) = &self.contains {
            for m in ac.find_overlapping_iter(norm_input) {
                if let Some(&idx) = map.get(m.pattern().as_usize()) {
                    if !cands.iter().any(|c| c.0 == idx) {
                        cands.push((idx, "contains", 0.7));
                    }
                }
            }
        }
        cands.into_iter().max_by(|a, b| {
            self.entries[a.0]
                .priority
                .cmp(&self.entries[b.0].priority)
                .then(a.2.partial_cmp(&b.2).unwrap_or(std::cmp::Ordering::Equal))
                .then_with(|| b.0.cmp(&a.0))
        })
    }
}

struct CachedQa {
    revision: i64,
    rollout_state: String,
    canary_percent: i32,
    index: QaIndex,
}

pub struct QaChecker {
    cache: RwLock<Option<Arc<CachedQa>>>,
    checked_at: RwLock<Option<Instant>>,
    file_path: PathBuf,
    redis_url: String,
    tenant: String,
    pointer_prefix: String,
    blob_prefix: String,
    ack_prefix: String,
    poll_sec: u64,
    instance_id: String,
}

impl QaChecker {
    pub fn from_env() -> Self {
        let data_dir = env::var("VIRBIUS_DATA_DIR").unwrap_or_else(|_| "./data".into());
        let tenant = env::var("VIRBIUS_TENANT_ID").unwrap_or_else(|_| "default".into());
        let file_path = env::var("VIRBIUS_QA_PATH")
            .map(PathBuf::from)
            .unwrap_or_else(|_| {
                PathBuf::from(&data_dir)
                    .join("gateway")
                    .join(format!("{tenant}-qa.json"))
            });
        let redis_url =
            env::var("VIRBIUS_REDIS_URL").unwrap_or_else(|_| "redis://127.0.0.1:6379".into());
        let pointer_prefix =
            env::var("VIRBIUS_QA_POINTER_PREFIX").unwrap_or_else(|_| "virbius:config:qa".into());
        let blob_prefix =
            env::var("VIRBIUS_QA_BLOB_PREFIX").unwrap_or_else(|_| "virbius:artifacts:qa".into());
        let ack_prefix =
            env::var("VIRBIUS_QA_ACK_PREFIX").unwrap_or_else(|_| "virbius:ack:qa".into());
        let poll_sec = env::var("VIRBIUS_QA_SYNC_POLL_SEC")
            .ok()
            .and_then(|s| s.parse().ok())
            .unwrap_or(10);
        let instance_id = crate::deploy::NodePoolResolver::from_env().instance_id;

        let checker = Self {
            cache: RwLock::new(None),
            checked_at: RwLock::new(None),
            file_path,
            redis_url,
            tenant,
            pointer_prefix,
            blob_prefix,
            ack_prefix,
            poll_sec,
            instance_id,
        };
        checker.refresh();
        checker
    }

    pub fn match_candidate(&self, last_user_message: Option<&str>) -> Option<QaCandidate> {
        let text = last_user_message?;
        let norm = normalize(text);
        if norm.is_empty() {
            return None;
        }
        let cached = self.load().ok()?;
        if cached.rollout_state.eq_ignore_ascii_case("disabled") {
            return None;
        }
        let (idx, mode, _) = cached.index.find(&norm)?;
        let e = &cached.index.entries[idx];
        Some(QaCandidate {
            entry_id: e.entry_id.clone(),
            mode: mode.to_string(),
            answer: e.answer.clone(),
            revision: cached.revision as i32,
            rollout_state: cached.rollout_state.clone(),
            canary_percent: cached.canary_percent,
        })
    }

    fn load(&self) -> Result<Arc<CachedQa>, String> {
        if !self.is_fresh() {
            self.refresh();
        }
        self.cached()
            .ok_or_else(|| "no qa snapshot available".into())
    }

    fn is_fresh(&self) -> bool {
        self.checked_at
            .read()
            .ok()
            .and_then(|g| *g)
            .map(|t| t.elapsed() < Duration::from_secs(self.poll_sec))
            .unwrap_or(false)
    }

    fn cached(&self) -> Option<Arc<CachedQa>> {
        self.cache.read().ok().and_then(|g| g.clone())
    }

    fn refresh(&self) {
        if let Ok((rev, snapshot)) = Self::fetch_snapshot(
            &self.redis_url,
            &self.tenant,
            &self.pointer_prefix,
            &self.blob_prefix,
        ) {
            match QaIndex::build(&snapshot) {
                Ok(index) => {
                    let changed = self.cached().map(|c| c.revision != rev).unwrap_or(true);
                    if changed {
                        if let Ok(mut guard) = self.cache.write() {
                            *guard = Some(Arc::new(CachedQa {
                                revision: rev,
                                rollout_state: snapshot.rollout_state.clone(),
                                canary_percent: snapshot.canary_percent,
                                index,
                            }));
                        }
                        self.write_ack(rev, "ok", &[]);
                    }
                }
                Err(errors) => {
                    self.write_ack(rev, "failed", &errors);
                }
            }
        } else if let Ok((snapshot, rev)) = Self::fetch_from_file(&self.file_path) {
            if self.cached().is_none() {
                if let Ok(index) = QaIndex::build(&snapshot) {
                    if let Ok(mut guard) = self.cache.write() {
                        *guard = Some(Arc::new(CachedQa {
                            revision: rev,
                            rollout_state: snapshot.rollout_state.clone(),
                            canary_percent: snapshot.canary_percent,
                            index,
                        }));
                    }
                }
            }
        }
        if let Ok(mut guard) = self.checked_at.write() {
            *guard = Some(Instant::now());
        }
    }

    fn fetch_snapshot(
        redis_url: &str,
        tenant: &str,
        pointer_prefix: &str,
        blob_prefix: &str,
    ) -> Result<(i64, QaSnapshot), String> {
        let client = redis::Client::open(redis_url).map_err(|e| format!("redis client: {e}"))?;
        let mut conn = client
            .get_connection()
            .map_err(|e| format!("redis connect: {e}"))?;

        let pointer_key = format!("{pointer_prefix}:{tenant}");
        let hash: HashMap<String, String> = conn
            .hgetall(&pointer_key)
            .map_err(|e| format!("hgetall: {e}"))?;

        let rev: i64 = hash
            .get("qa_revision")
            .ok_or("missing qa_revision")?
            .parse()
            .map_err(|e| format!("parse revision: {e}"))?;

        let blob_key = format!("{blob_prefix}:{tenant}:r{rev}:qa");
        let raw: Vec<u8> = conn
            .get(&blob_key)
            .map_err(|e| format!("redis get blob: {e}"))?;
        let snapshot: QaSnapshot =
            serde_json::from_slice(&raw).map_err(|e| format!("parse qa snapshot: {e}"))?;
        Ok((rev, snapshot))
    }

    fn fetch_from_file(path: &PathBuf) -> Result<(QaSnapshot, i64), String> {
        let raw = fs::read_to_string(path).map_err(|e| format!("read file: {e}"))?;
        let snapshot: QaSnapshot =
            serde_json::from_str(&raw).map_err(|e| format!("parse qa file: {e}"))?;
        let rev = snapshot.revision;
        Ok((snapshot, rev))
    }

    fn write_ack(&self, revision: i64, status: &str, errors: &[String]) {
        let Ok(client) = redis::Client::open(self.redis_url.as_str()) else {
            return;
        };
        let Ok(mut conn) = client.get_connection() else {
            return;
        };
        let key = format!("{}:{}:{}", self.ack_prefix, self.tenant, self.instance_id);
        let errors_json = serde_json::to_string(errors).unwrap_or_else(|_| "[]".into());
        let pairs: Vec<(&str, String)> = vec![
            ("revision", revision.to_string()),
            ("status", status.to_string()),
            ("errors", errors_json),
            ("updated_at", Utc::now().to_rfc3339()),
        ];
        let _: Result<(), _> = conn.hset_multiple(&key, &pairs);
    }
}

pub fn emit_qa_event(
    trace_id: &str,
    scene: &str,
    candidate: &QaCandidate,
    reason_code: &str,
    effective_action: &str,
    session_id: Option<&str>,
    user_id: Option<&str>,
    device_id: Option<&str>,
) {
    let canary_percent = if candidate.rollout_state.eq_ignore_ascii_case("canary") {
        Some(candidate.canary_percent)
    } else {
        None
    };
    let event = crate::audit::build_event(
        trace_id,
        scene,
        "qa",
        Some(candidate.entry_id.as_str()),
        Some(candidate.revision),
        Some(reason_code),
        effective_action,
        0,
        Some(candidate.rollout_state.as_str()),
        canary_percent,
        session_id,
        false,
        user_id,
        device_id,
    );
    crate::audit::emit(event);
}

#[cfg(test)]
mod tests {
    use super::*;

    fn snapshot(entries: Vec<QaEntryDef>) -> QaSnapshot {
        QaSnapshot {
            schema_version: 1,
            revision: 7,
            rollout_state: "full".into(),
            canary_percent: 0,
            entries,
        }
    }

    fn entry(id: &str, question: &str, answer: &str, mode: &str) -> QaEntryDef {
        QaEntryDef {
            entry_id: id.into(),
            question: question.into(),
            variants: Vec::new(),
            answer: answer.into(),
            match_mode: mode.into(),
            priority: 0,
            enabled: true,
            expires_at: None,
        }
    }

    #[test]
    fn normalize_fullwidth_and_case() {
        assert_eq!(normalize("Ｔａｉｗａｎ 台湾"), "taiwan 台湾");
        assert_eq!(normalize("ＡＢＣ"), "abc");
    }

    #[test]
    fn normalize_zero_width_and_whitespace() {
        assert_eq!(normalize("Ta\u{200B}iw\u{FEFF}an"), "taiwan");
        assert_eq!(normalize("  a \t b   c\n"), "a b c");
    }

    #[test]
    fn exact_match_via_variant_and_normalization() {
        let mut e = entry("e1", "什么是核心价值观", "标准回答A", "exact");
        e.variants = vec!["核心价值观是什么".into(), "什么是\u{200B}核心价值观".into()];
        let index = QaIndex::build(&snapshot(vec![e])).expect("build");
        assert!(index.find(&normalize("什么是核心价值观？")).is_none());
        assert!(index.find(&normalize("什么是核心价值观")).is_some());
        assert_eq!(index.find(&normalize("核心价值观是什么")).unwrap().0, 0);
    }

    #[test]
    fn contains_match_substring() {
        let e = entry("e1", "办证", "标准回答B", "contains");
        let index = QaIndex::build(&snapshot(vec![e])).expect("build");
        let (idx, mode, _) = index.find(&normalize("哪里可以办证啊")).expect("hit");
        assert_eq!(idx, 0);
        assert_eq!(mode, "contains");
    }

    #[test]
    fn regex_match() {
        let e = entry("e1", "台.*湾.*国家", "标准回答C", "regex");
        let index = QaIndex::build(&snapshot(vec![e])).expect("build");
        assert!(index.find(&normalize("请问台湾是不是一个国家")).is_some());
        assert!(index.find(&normalize("今天天气不错")).is_none());
    }

    #[test]
    fn invalid_regex_rejected() {
        let e = entry("e1", "(?=lookahead)", "a", "regex");
        assert!(QaIndex::build(&snapshot(vec![e])).is_err());
    }

    #[test]
    fn unsupported_mode_rejected() {
        let e = entry("e1", "q", "a", "similar");
        assert!(QaIndex::build(&snapshot(vec![e])).is_err());
    }

    #[test]
    fn unsupported_schema_version_rejected() {
        let mut s = snapshot(vec![entry("e1", "q", "a", "exact")]);
        s.schema_version = 2;
        assert!(QaIndex::build(&s).is_err());
    }

    #[test]
    fn priority_then_mode_score_arbitration() {
        let low = {
            let mut e = entry("a_low", "办证", "low", "contains");
            e.priority = 1;
            e
        };
        let high = {
            let mut e = entry("b_high", "证", "high", "contains");
            e.priority = 10;
            e
        };
        let index = QaIndex::build(&snapshot(vec![low, high])).expect("build");
        assert_eq!(index.find(&normalize("哪里办证")).expect("hit").0, 1);

        let exact_low = {
            let mut e = entry("c_exact", "办证", "exact", "exact");
            e.priority = 1;
            e
        };
        let contains_same = {
            let mut e = entry("d_contains", "证", "contains", "contains");
            e.priority = 1;
            e
        };
        let index = QaIndex::build(&snapshot(vec![exact_low, contains_same])).expect("build");
        let (idx, mode, _) = index.find(&normalize("办证")).expect("hit");
        assert_eq!(mode, "exact");
        assert_eq!(idx, 0);
    }

    #[test]
    fn expired_entry_skipped() {
        let mut e = entry("e1", "过期问题", "a", "exact");
        e.expires_at = Some("2000-01-01T00:00:00Z".into());
        let index = QaIndex::build(&snapshot(vec![e])).expect("build");
        assert!(index.find("过期问题").is_none());
    }

    #[test]
    fn disabled_entry_skipped() {
        let mut e = entry("e1", "禁用问题", "a", "exact");
        e.enabled = false;
        let index = QaIndex::build(&snapshot(vec![e])).expect("build");
        assert!(index.find("禁用问题").is_none());
    }

    #[test]
    fn decide_consume_gating() {
        assert_eq!(
            decide_consume("allow", false, "full", true),
            ConsumeDecision::Reply
        );
        assert_eq!(
            decide_consume("block", false, "full", true),
            ConsumeDecision::Suppressed("deny")
        );
        assert_eq!(
            decide_consume("deny", false, "full", true),
            ConsumeDecision::Suppressed("deny")
        );
        assert_eq!(
            decide_consume("captcha", false, "full", true),
            ConsumeDecision::Suppressed("captcha")
        );
        assert_eq!(
            decide_consume("review", false, "full", true),
            ConsumeDecision::Suppressed("review")
        );
        assert_eq!(
            decide_consume("allow", true, "full", true),
            ConsumeDecision::Suppressed("degraded")
        );
        assert_eq!(
            decide_consume("allow", false, "dry_run", true),
            ConsumeDecision::DryRunCandidate
        );
        assert_eq!(
            decide_consume("allow", false, "canary", true),
            ConsumeDecision::Reply
        );
        assert_eq!(
            decide_consume("allow", false, "canary", false),
            ConsumeDecision::Skip
        );
        assert_eq!(
            decide_consume("allow", false, "unknown", true),
            ConsumeDecision::Skip
        );
    }

    #[test]
    fn file_fallback_load() {
        let dir = std::env::temp_dir().join("virbius-qa-test");
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("qa.json");
        let snap = snapshot(vec![entry("file1", "文件兜底问题", "文件回答", "exact")]);
        std::fs::write(&path, serde_json::to_string(&snap).unwrap()).unwrap();

        let checker = QaChecker {
            cache: RwLock::new(None),
            checked_at: RwLock::new(None),
            file_path: path,
            redis_url: "redis://127.0.0.1:1".into(),
            tenant: "default".into(),
            pointer_prefix: "virbius:config:qa".into(),
            blob_prefix: "virbius:artifacts:qa".into(),
            ack_prefix: "virbius:ack:qa".into(),
            poll_sec: 60,
            instance_id: "test-node".into(),
        };
        let cand = checker
            .match_candidate(Some("文件兜底问题"))
            .expect("candidate");
        assert_eq!(cand.entry_id, "file1");
        assert_eq!(cand.answer, "文件回答");
        assert_eq!(cand.mode, "exact");
        assert_eq!(cand.revision, 7);
    }
}
