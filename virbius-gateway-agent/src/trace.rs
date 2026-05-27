use regex::Regex;
use std::sync::LazyLock;

static UUID_V4: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
        .expect("uuid regex")
});

static ULID: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"^[0-9A-HJKMNP-TV-Z]{26}$").expect("ulid regex")
});

pub fn valid(id: &str) -> bool {
    let len = id.len();
    if !(16..=128).contains(&len) {
        return false;
    }
    UUID_V4.is_match(id) || ULID.is_match(id)
}
