use regex::Regex;

pub fn built_in_pattern(entity_type: &str) -> Option<&'static str> {
    match entity_type {
        "idcard_cn" => Some(
            r"(?x)
            \b
            [1-9]\d{5}
            (?:19|20)\d{2}
            (?:0[1-9]|1[0-2])
            (?:0[1-9]|[12]\d|3[01])
            \d{3}[\dXx]
            \b",
        ),
        "phone_cn" => Some(r"\b1[3-9]\d{9}\b"),
        "email" => Some(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b"),
        "bank_card_cn" => Some(r"\b(?:\d[ -]*?){13,19}\b"),
        _ => None,
    }
}

pub fn compile_entity_regex(entity_type: &str, custom_pattern: Option<&str>) -> Option<Regex> {
    let pat = if entity_type == "custom_regex" {
        custom_pattern?
    } else {
        built_in_pattern(entity_type)?
    };
    Regex::new(pat).ok()
}

pub fn normalize_bank_card(raw: &str) -> String {
    raw.chars().filter(|c| c.is_ascii_digit()).collect()
}

pub fn luhn_valid(digits: &str) -> bool {
    if digits.len() < 13 || digits.len() > 19 {
        return false;
    }
    if !digits.chars().all(|c| c.is_ascii_digit()) {
        return false;
    }
    let mut sum = 0u32;
    let mut alt = false;
    for ch in digits.chars().rev() {
        let mut n = ch.to_digit(10).unwrap_or(0);
        if alt {
            n *= 2;
            if n > 9 {
                n -= 9;
            }
        }
        sum += n;
        alt = !alt;
    }
    sum % 10 == 0
}

pub fn default_mask_prefix(entity_type: &str) -> &'static str {
    match entity_type {
        "idcard_cn" => "VIRBIUS_IDCARD_CN",
        "phone_cn" => "VIRBIUS_PHONE_CN",
        "email" => "VIRBIUS_EMAIL",
        "bank_card_cn" => "VIRBIUS_BANK_CARD",
        _ => "VIRBIUS_CUSTOM",
    }
}

pub fn mask_template_for(entity_type: &str, custom: Option<&str>) -> String {
    if let Some(t) = custom.filter(|s| !s.is_empty()) {
        return t.to_string();
    }
    format!("{{{{{}_{{seq}}}}}}", default_mask_prefix(entity_type))
}
