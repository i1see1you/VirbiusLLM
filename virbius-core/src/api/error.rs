use thiserror::Error;

#[derive(Debug, Error, PartialEq, Eq)]
pub enum VirbiusError {
    #[error("content must not be empty")]
    EmptyContent,
    #[error("invalid trace_id; use UUID v4 or ULID")]
    InvalidTraceId,
}
