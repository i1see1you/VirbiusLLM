#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TraceIdSource {
    Client,
    SdkGenerated,
}

impl TraceIdSource {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Client => "client",
            Self::SdkGenerated => "sdk",
        }
    }
}

pub fn generate_trace_id() -> String {
    uuid::Uuid::new_v4().to_string()
}

pub fn valid_trace_id(id: &str) -> bool {
    let len = id.len();
    if !(16..=128).contains(&len) {
        return false;
    }
    uuid::Uuid::parse_str(id).is_ok() || is_ulid(id)
}

fn is_ulid(id: &str) -> bool {
    id.len() == 26
        && id
            .chars()
            .all(|c| matches!(c, '0'..='9' | 'A'..='H' | 'J'..='N' | 'P'..='T' | 'V'..='Z'))
}

/// Auto-generate when missing; validate UUID v4 / ULID when provided.
pub fn resolve_trace_id(provided: Option<&str>) -> Result<(String, TraceIdSource), TraceIdError> {
    match provided.map(str::trim).filter(|s| !s.is_empty()) {
        None => Ok((generate_trace_id(), TraceIdSource::SdkGenerated)),
        Some(id) if valid_trace_id(id) => Ok((id.to_string(), TraceIdSource::Client)),
        Some(_) => Err(TraceIdError),
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TraceIdError;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generates_uuid_v4() {
        let id = generate_trace_id();
        assert!(valid_trace_id(&id));
    }

    #[test]
    fn rejects_invalid_trace_id() {
        assert!(resolve_trace_id(Some("bad")).is_err());
    }
}
