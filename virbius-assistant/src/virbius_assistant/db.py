import json
import sqlite3
from contextlib import contextmanager
from typing import Any, Optional

from virbius_assistant.config import settings


@contextmanager
def get_db():
    conn = sqlite3.connect(settings.db_path)
    conn.row_factory = sqlite3.Row
    try:
        yield conn
    finally:
        conn.close()


def query_audit_events(
    tenant_id: str,
    entity_type: str,
    entity_value: str,
    time_start: Optional[str] = None,
    time_end: Optional[str] = None,
    limit: int = 100,
) -> dict[str, Any]:
    entity_col = "user_id" if entity_type == "user" else "device_id"
    params: list[Any] = [tenant_id, entity_value]
    time_clause = ""
    if time_start:
        time_clause += " AND intercepted_at >= ?"
        params.append(time_start)
    if time_end:
        time_clause += " AND intercepted_at <= ?"
        params.append(time_end)

    with get_db() as conn:
        total = conn.execute(
            f"SELECT COUNT(*) FROM tb_audit_events WHERE tenant_id=? AND {entity_col}=? {time_clause}",
            params
        ).fetchone()[0]

        stats_row = conn.execute(
            f"SELECT COALESCE(MIN(max_risk_score),0) as min_risk,"
            f" COALESCE(MAX(max_risk_score),0) as max_risk,"
            f" COALESCE(AVG(max_risk_score),0) as avg_risk"
            f" FROM tb_audit_events WHERE tenant_id=? AND {entity_col}=? {time_clause}",
            params
        ).fetchone()
        stats = dict(stats_row) if stats_row else {}

        action_dist = [
            dict(r) for r in conn.execute(
                f"SELECT effective_action as action, COUNT(*) as cnt"
                f" FROM tb_audit_events WHERE tenant_id=? AND {entity_col}=? {time_clause}"
                f" GROUP BY effective_action ORDER BY cnt DESC",
                params
            ).fetchall()
        ]

        rule_ranking = [
            dict(r) for r in conn.execute(
                f"SELECT rule_id, COUNT(*) as cnt, MAX(max_risk_score) as max_risk"
                f" FROM tb_audit_events WHERE tenant_id=? AND {entity_col}=? {time_clause}"
                f" AND effective_action != 'allow'"
                f" GROUP BY rule_id ORDER BY cnt DESC LIMIT 10",
                params
            ).fetchall()
        ]

        samples = [
            dict(r) for r in conn.execute(
                f"SELECT trace_id, scene, layer, rule_id, reason_code,"
                f" effective_action, max_risk_score, intercepted_at"
                f" FROM tb_audit_events WHERE tenant_id=? AND {entity_col}=? {time_clause}"
                f" ORDER BY max_risk_score DESC, intercepted_at DESC LIMIT ?",
                params + [limit]
            ).fetchall()
        ]

    return {
        "total_events": total,
        "risk_score_stats": {
            "min": stats.get("min_risk", 0),
            "max": stats.get("max_risk", 0),
            "avg": stats.get("avg_risk", 0),
        },
        "action_distribution": action_dist,
        "rule_hit_ranking": rule_ranking,
        "samples": samples,
    }


def get_entity_profile(tenant_id: str, entity_type: str, entity_value: str) -> dict[str, Any]:
    with get_db() as conn:
        row = conn.execute(
            "SELECT * FROM tb_entity_risk_profile"
            " WHERE tenant_id=? AND entity_type=? AND entity_value=?",
            (tenant_id, entity_type, entity_value)
        ).fetchone()
    if row:
        return dict(row)
    return {"risk_score": 0, "risk_level": "unknown", "total_events": 0}


def upsert_profile(tenant_id: str, entity_type: str, entity_value: str,
                    risk_score: int, risk_level: str, total_events: int, tags: list[str]) -> None:
    tags_json = json.dumps(tags, ensure_ascii=False)
    with get_db() as conn:
        conn.execute(
            "INSERT INTO tb_entity_risk_profile"
            " (tenant_id, entity_type, entity_value, risk_score, risk_level, total_events,"
            "  tags_json, last_seen_at, updated_at)"
            " VALUES (?, ?, ?, ?, ?, ?, ?, datetime('now'), datetime('now'))"
            " ON CONFLICT(tenant_id, entity_type, entity_value) DO UPDATE SET"
            "  risk_score=excluded.risk_score, risk_level=excluded.risk_level,"
            "  total_events=excluded.total_events, tags_json=excluded.tags_json,"
            "  last_seen_at=excluded.last_seen_at, updated_at=excluded.updated_at",
            (tenant_id, entity_type, entity_value, risk_score, risk_level, total_events, tags_json)
        )
        conn.commit()


def save_assessment(tenant_id: str, assessment_id: str, entity_type: str, entity_value: str,
                     query_text: Optional[str], conclusion: str, confidence: float,
                     llm_reasoning: Optional[str], rule_signals_json: Optional[str],
                     recommended_action: Optional[str], auto_executed: bool,
                     ticket_id: Optional[str] = None) -> None:
    with get_db() as conn:
        conn.execute(
            "INSERT INTO tb_agent_assessments"
            " (assessment_id, tenant_id, entity_type, entity_value, query_text,"
            "  conclusion, confidence, llm_reasoning, rule_signals_json,"
            "  recommended_action, auto_executed, ticket_id, created_at)"
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, datetime('now'))",
            (assessment_id, tenant_id, entity_type, entity_value, query_text,
             conclusion, confidence, llm_reasoning, rule_signals_json,
             recommended_action, 1 if auto_executed else 0, ticket_id)
        )
        conn.commit()


def create_ticket(tenant_id: str, assessment_id: str, entity_type: str, entity_value: str,
                   summary: str, evidence_json: str, recommended_action: Optional[str],
                   priority: str = "medium") -> str:
    import uuid
    ticket_id = "tkt_" + uuid.uuid4().hex[:12]
    with get_db() as conn:
        conn.execute(
            "INSERT INTO tb_agent_review_tickets"
            " (ticket_id, tenant_id, assessment_id, entity_type, entity_value,"
            "  status, priority, summary, evidence_json, recommended_action, created_at, updated_at)"
            " VALUES (?, ?, ?, ?, ?, 'pending', ?, ?, ?, ?, datetime('now'), datetime('now'))",
            (ticket_id, tenant_id, assessment_id, entity_type, entity_value,
             priority, summary, evidence_json, recommended_action)
        )
        conn.execute(
            "UPDATE tb_agent_assessments SET ticket_id=? WHERE tenant_id=? AND assessment_id=?",
            (ticket_id, tenant_id, assessment_id)
        )
        conn.commit()
    return ticket_id


def get_ticket(tenant_id: str, ticket_id: str) -> dict[str, Any] | None:
    with get_db() as conn:
        row = conn.execute(
            "SELECT * FROM tb_agent_review_tickets WHERE tenant_id=? AND ticket_id=?",
            (tenant_id, ticket_id)
        ).fetchone()
    return dict(row) if row else None


def list_tickets(tenant_id: str, status: Optional[str] = None, limit: int = 50) -> list[dict]:
    with get_db() as conn:
        if status:
            rows = conn.execute(
                "SELECT ticket_id, assessment_id, entity_type, entity_value, status, priority,"
                " summary, recommended_action, operator, created_at, updated_at"
                " FROM tb_agent_review_tickets"
                " WHERE tenant_id=? AND status=? ORDER BY created_at DESC LIMIT ?",
                (tenant_id, status, limit)
            ).fetchall()
        else:
            rows = conn.execute(
                "SELECT ticket_id, assessment_id, entity_type, entity_value, status, priority,"
                " summary, recommended_action, operator, created_at, updated_at"
                " FROM tb_agent_review_tickets"
                " WHERE tenant_id=? ORDER BY created_at DESC LIMIT ?",
                (tenant_id, limit)
            ).fetchall()
    return [dict(r) for r in rows]


def update_ticket(tenant_id: str, ticket_id: str, status: str,
                   operator: Optional[str], comment: Optional[str],
                   executed_action: Optional[str] = None) -> None:
    with get_db() as conn:
        conn.execute(
            "UPDATE tb_agent_review_tickets"
            " SET status=?, operator=?, comment=?, executed_action=?," 
            " responded_at=datetime('now'), updated_at=datetime('now')"
            " WHERE tenant_id=? AND ticket_id=?",
            (status, operator, comment, executed_action, tenant_id, ticket_id)
        )
        conn.commit()


def save_action(tenant_id: str, action_id: str, entity_type: str, entity_value: str,
                 action_type: str, trigger: str, status: str,
                 assessment_id: Optional[str] = None, ticket_id: Optional[str] = None,
                 detail_json: Optional[str] = None) -> None:
    with get_db() as conn:
        conn.execute(
            "INSERT INTO tb_agent_actions"
            " (action_id, tenant_id, entity_type, entity_value, action_type, trigger,"
            "  assessment_id, ticket_id, action_detail_json, status, executed_at, created_at)"
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, datetime('now'), datetime('now'))",
            (action_id, tenant_id, entity_type, entity_value, action_type, trigger,
             assessment_id, ticket_id, detail_json, status)
        )
        conn.commit()
