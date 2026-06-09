use regex::Regex;
use std::sync::LazyLock;

static UUID_V4: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
        .expect("uuid v4 regex")
});

pub fn valid(id: &str) -> bool {
    id.len() == 36 && UUID_V4.is_match(id)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_v4() {
        assert!(valid("550e8400-e29b-41d4-a716-446655440000"));
    }

    #[test]
    fn rejects_ulid_and_v1() {
        assert!(!valid("01ARZ3NDEKTSV4RRFFQ69G5FAV"));
        assert!(!valid("6ba7b810-9dad-11d1-80b4-00c04fd413c1"));
    }
}
