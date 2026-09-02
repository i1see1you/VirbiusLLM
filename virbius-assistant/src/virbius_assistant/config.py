import os
from pathlib import Path


def _find_db() -> str:
    candidates = [
        os.environ.get("VIRBIUS_DB_PATH"),
        str(Path(__file__).parents[3] / "data" / "virbius-control.db"),
        "/tmp/virbius/data/virbius-control.db",
    ]
    for c in candidates:
        if c and Path(c).exists():
            return c
    return candidates[1] or ""


class Settings:
    db_path: str = _find_db()
    control_base_url: str = os.environ.get("VIRBIUS_CONTROL_URL", "http://localhost:8080")

    llm_model: str = os.environ.get("VIRBIUS_LLM_MODEL", "virbiusguard:latest")
    llm_base_url: str = os.environ.get("VIRBIUS_LLM_BASE_URL", "http://localhost:11434")
    llm_timeout: int = int(os.environ.get("VIRBIUS_LLM_TIMEOUT", "60"))

    confidence_threshold: float = float(os.environ.get("VIRBIUS_CONFIDENCE_THRESHOLD", "0.85"))
    risk_score_threshold: int = int(os.environ.get("VIRBIUS_RISK_SCORE_THRESHOLD", "60"))
    max_sample_signals: int = int(os.environ.get("VIRBIUS_MAX_SAMPLES", "200"))

    review_default_priority: str = "medium"
    review_ticket_ttl_hours: int = 24

    api_host: str = os.environ.get("VIRBIUS_API_HOST", "0.0.0.0")
    mcp_host: str = os.environ.get("VIRBIUS_MCP_HOST", "0.0.0.0")

    agent_app_id: str = os.environ.get("VIRBIUS_AGENT_APP_ID", "agent1")
    agent_tenant_id: str = os.environ.get("VIRBIUS_AGENT_TENANT_ID", "default")
    agent_license_jwt: str = os.environ.get("VIRBIUS_AGENT_LICENSE_JWT", "")

    report_dir: str = os.environ.get(
        "VIRBIUS_REPORT_DIR",
        str(Path(__file__).parents[3] / "data" / "reports"),
    )


settings = Settings()
