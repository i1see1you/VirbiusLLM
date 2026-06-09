use flexi_logger::{Age, Cleanup, Criterion, Duplicate, FileSpec, Logger, Naming};

const DEFAULT_LOG_DIR: &str = "/tmp/virbius/logs";
const DEFAULT_MAX_FILE_SIZE: &str = "100MB";
const DEFAULT_MAX_HISTORY_DAYS: u32 = 30;

pub fn init() -> Result<(), flexi_logger::FlexiLoggerError> {
    let log_dir = std::env::var("VIRBIUS_LOG_DIR").unwrap_or_else(|_| DEFAULT_LOG_DIR.into());
    let max_bytes = parse_data_size(
        std::env::var("VIRBIUS_LOG_MAX_FILE_SIZE")
            .unwrap_or_else(|_| DEFAULT_MAX_FILE_SIZE.into())
            .as_str(),
    );
    let max_history_days: u32 = std::env::var("VIRBIUS_LOG_MAX_HISTORY_DAYS")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(DEFAULT_MAX_HISTORY_DAYS);
    let max_archive_files: usize = std::env::var("VIRBIUS_LOG_MAX_ARCHIVE_FILES")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(max_history_days as usize);

    std::fs::create_dir_all(&log_dir).ok();

    Logger::try_with_env_or_str("info")?
        .log_to_file(
            FileSpec::default()
                .directory(&log_dir)
                .basename("agent")
                .suffix("log"),
        )
        .duplicate_to_stderr(Duplicate::Info)
        .rotate(
            Criterion::AgeOrSize(Age::Day, max_bytes),
            Naming::Timestamps,
            Cleanup::KeepCompressedFiles(max_archive_files),
        )
        .start()?;
    Ok(())
}

fn parse_data_size(raw: &str) -> u64 {
    let s = raw.trim();
    if s.is_empty() {
        return 100 * 1024 * 1024;
    }
    let upper = s.to_ascii_uppercase();
    let split_at = upper
        .find(|c: char| !c.is_ascii_digit())
        .unwrap_or(upper.len());
    let (num, unit) = upper.split_at(split_at);
    let n: u64 = num.parse().unwrap_or(100);
    match unit.trim_start_matches('_') {
        "" | "B" => n,
        "K" | "KB" | "KIB" => n * 1024,
        "M" | "MB" | "MIB" => n * 1024 * 1024,
        "G" | "GB" | "GIB" => n * 1024 * 1024 * 1024,
        _ => n * 1024 * 1024,
    }
}

#[cfg(test)]
mod tests {
    use super::parse_data_size;

    #[test]
    fn parses_common_size_units() {
        assert_eq!(parse_data_size("100MB"), 100 * 1024 * 1024);
        assert_eq!(parse_data_size("1G"), 1024 * 1024 * 1024);
        assert_eq!(parse_data_size("512K"), 512 * 1024);
    }
}
