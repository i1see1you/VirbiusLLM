use crate::enforce::RuleHit;
use crate::policy_engine::{hit_from_cumulative_block, resolve_value, CumulativeRuleBlock};
use chrono::{DateTime, NaiveTime, TimeZone, Utc};
use chrono_tz::Tz;
use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

type Slot = i64;
type Count = i64;
type Key = String;

const MAX_WINDOW_MINUTES: i32 = 10080;

static MEMORY: Mutex<Option<HashMap<Key, HashMap<Slot, Count>>>> = Mutex::new(None);

pub fn collect_hits(
    tenant_id: &str,
    rules: &[CumulativeRuleBlock],
    content: &str,
    user_id: Option<&str>,
    device_id: Option<&str>,
    client_ip: Option<&str>,
    session_id: Option<&str>,
    vars: &HashMap<String, String>,
) -> Vec<RuleHit> {
    let mut hits = Vec::new();
    for rule in rules {
        let value = match resolve_value(
            &rule.dimension,
            rule.value_source.as_ref(),
            content,
            user_id,
            device_id,
            client_ip,
            session_id,
            vars,
        ) {
            Some(v) => v,
            None => continue,
        };
        let Some(w_min) = window_minutes(rule) else {
            continue;
        };
        let g = granularity_minutes(w_min, &rule.window_kind);
        let end_slot = current_slot(g);
        let key = redis_key(tenant_id, &rule.cumulative_name, &value);
        ingest_key(&key, end_slot);
        let count = read_key(&key, rule, w_min, g, end_slot);
        if exceeded(count, rule.threshold, rule.compare_op.as_deref()) {
            hits.push(hit_from_cumulative_block(rule));
        }
    }
    hits
}

/// Aligns with `CumulativeWindow.windowMinutes`.
fn window_minutes(rule: &CumulativeRuleBlock) -> Option<i32> {
    if is_calendar_day(&rule.window_kind) {
        return Some(1440);
    }
    if let Some(m) = rule.window_minutes {
        if m > 0 {
            return Some(m.min(MAX_WINDOW_MINUTES));
        }
    }
    if let Some(h) = rule.window_hours {
        if h > 0 {
            return Some((h * 60).min(MAX_WINDOW_MINUTES));
        }
    }
    None
}

fn is_calendar_day(window_kind: &str) -> bool {
    window_kind.eq_ignore_ascii_case("calendar_day")
}

/// Aligns with `CumulativeWindow.granularityMinutes`.
fn granularity_minutes(w_minutes: i32, window_kind: &str) -> i32 {
    if is_calendar_day(window_kind) || w_minutes >= 1440 {
        10
    } else {
        1
    }
}

fn bucket_count(w_minutes: i32, granularity_min: i32) -> i64 {
    ((w_minutes + granularity_min - 1) / granularity_min) as i64
}

fn now_utc() -> DateTime<Utc> {
    Utc::now()
}

fn epoch_secs() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs() as i64
}

/// Aligns with `CumulativeWindow.currentSlot`.
fn current_slot(granularity_min: i32) -> Slot {
    epoch_secs() / (granularity_min as i64 * 60)
}

/// Aligns with `CumulativeWindow.startSlotCalendarDay`.
fn start_slot_calendar_day(now: DateTime<Utc>, zone: Tz, granularity_min: i32) -> Slot {
    let local = now.with_timezone(&zone);
    let date = local.date_naive();
    let start_naive = date.and_time(NaiveTime::from_hms_opt(0, 0, 0).unwrap());
    let start = zone
        .from_local_datetime(&start_naive)
        .single()
        .unwrap_or_else(|| zone.from_utc_datetime(&start_naive.and_utc().naive_utc()));
    start.timestamp() / (granularity_min as i64 * 60)
}

fn parse_zone(tz: Option<&String>) -> Tz {
    tz.and_then(|s| s.parse::<Tz>().ok())
        .unwrap_or(chrono_tz::UTC)
}

fn redis_key(tenant_id: &str, cumulative_name: &str, value: &str) -> Key {
    format!(
        "virbius:cum:{}:{}:{}",
        tenant_id,
        cumulative_name,
        encode_redis_key_segment(value)
    )
}

fn encode_redis_key_segment(value: &str) -> String {
    value.replace(':', "%3A").replace(' ', "%20")
}

fn ingest_key(key: &str, slot: Slot) {
    let mut guard = MEMORY.lock().unwrap();
    let store = guard.get_or_insert_with(HashMap::new);
    let buckets = store.entry(key.to_string()).or_default();
    *buckets.entry(slot).or_insert(0) += 1;
}

/// Aligns with `CounterStore.read` slot range.
fn read_key(key: &str, rule: &CumulativeRuleBlock, w_min: i32, g: i32, end_slot: Slot) -> Count {
    let start_slot = if is_calendar_day(&rule.window_kind) {
        let zone = parse_zone(rule.timezone.as_ref());
        start_slot_calendar_day(now_utc(), zone, g)
    } else {
        let buckets = bucket_count(w_min, g);
        end_slot - buckets + 1
    };
    let guard = MEMORY.lock().unwrap();
    let Some(store) = guard.as_ref() else {
        return 0;
    };
    let Some(buckets) = store.get(key) else {
        return 0;
    };
    if start_slot > end_slot {
        return 0;
    }
    (start_slot..=end_slot)
        .map(|s| buckets.get(&s).copied().unwrap_or(0))
        .sum()
}

fn exceeded(count: Count, threshold: i32, op: Option<&str>) -> bool {
    match op.unwrap_or("gte").to_ascii_lowercase().as_str() {
        "gt" => count > threshold as Count,
        "eq" => count == threshold as Count,
        "lte" => count <= threshold as Count,
        "lt" => count < threshold as Count,
        _ => count >= threshold as Count,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rule(window_kind: &str, minutes: Option<i32>, hours: Option<i32>, tz: Option<&str>) -> CumulativeRuleBlock {
        CumulativeRuleBlock {
            window_kind: window_kind.into(),
            window_minutes: minutes,
            window_hours: hours,
            timezone: tz.map(|s| s.into()),
            ..Default::default()
        }
    }

    #[test]
    fn window_minutes_calendar_day_is_1440() {
        assert_eq!(window_minutes(&rule("calendar_day", Some(60), None, None)), Some(1440));
    }

    #[test]
    fn window_minutes_rolling_caps() {
        assert_eq!(window_minutes(&rule("rolling", Some(90), None, None)), Some(90));
        assert_eq!(window_minutes(&rule("rolling", None, Some(2), None)), Some(120));
    }

    #[test]
    fn granularity_calendar_day_is_10() {
        assert_eq!(granularity_minutes(60, "calendar_day"), 10);
        assert_eq!(granularity_minutes(60, "rolling"), 1);
        assert_eq!(granularity_minutes(1440, "rolling"), 10);
    }

    #[test]
    fn start_slot_calendar_day_not_after_end() {
        let g = 10;
        let end = current_slot(g);
        let start = start_slot_calendar_day(now_utc(), chrono_tz::Asia::Shanghai, g);
        assert!(start <= end);
    }
}
