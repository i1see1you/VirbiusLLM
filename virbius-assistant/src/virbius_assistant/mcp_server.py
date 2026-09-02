import json
import logging
import os
import uuid
from multiprocessing import Process
from typing import Any, Optional

import httpx
from fastmcp import FastMCP

from virbius_assistant import db
from virbius_assistant.config import settings

logger = logging.getLogger(__name__)

mcp = FastMCP("virbius-agent-mcp")


# ─── 查询类工具 ─────────────────────────────────────────────

@mcp.tool()
def check_query_scope(time_start: str, time_end: str) -> str:
    """检查查询时间范围是否超过 365 天需要人工确认。

    Args:
        time_start: 开始时间 ISO-8601
        time_end: 结束时间 ISO-8601
    """
    from datetime import datetime

    start = datetime.fromisoformat(time_start.replace("Z", "+00:00"))
    end = datetime.fromisoformat(time_end.replace("Z", "+00:00"))
    days = (end - start).days

    if days > 365:
        return json.dumps({
            "needs_confirmation": True,
            "message": "查询时间范围较长查询时间会比较慢,确认执行吗?",
            "time_range_days": days,
        }, ensure_ascii=False)
    return json.dumps({
        "needs_confirmation": False,
        "time_range_days": days,
    }, ensure_ascii=False)


@mcp.tool()
def query_audit_events(
    tenant_id: str,
    entity_type: str,
    entity_value: str,
    time_start: Optional[str] = None,
    time_end: Optional[str] = None,
    limit: int = 100,
) -> str:
    """查询 user/device 在指定时间段的审计事件并聚合统计。

    返回 total_events、risk_score_stats（min/max/avg）、rule_hit_ranking、action_distribution、samples。

    Args:
        tenant_id: 租户 ID
        entity_type: 实体类型 user 或 device
        entity_value: 用户ID或设备ID
        time_start: 开始时间 ISO-8601（可选，默认近7天）
        time_end: 结束时间 ISO-8601（可选）
        limit: 返回最大采样数，默认 100
    """
    return json.dumps(db.query_audit_events(
        tenant_id, entity_type, entity_value, time_start, time_end, limit
    ), ensure_ascii=False)


@mcp.tool()
def get_entity_profile(tenant_id: str, entity_type: str, entity_value: str) -> str:
    """获取 user/device 的当前风险画像（历史风险记录、是否已被封禁等）。

    Args:
        tenant_id: 租户 ID
        entity_type: 实体类型 user 或 device
        entity_value: 用户ID或设备ID
    """
    return json.dumps(db.get_entity_profile(tenant_id, entity_type, entity_value), ensure_ascii=False)


# ─── 处置类工具 ─────────────────────────────────────────────

def _call_control(method: str, path: str, json_data: Optional[dict] = None) -> dict:
    url = f"{settings.control_base_url}{path}"
    try:
        resp = httpx.request(method, url, json=json_data, timeout=10)
        resp.raise_for_status()
        return resp.json()
    except Exception as e:
        logger.error("Control API call failed: %s %s - %s", method, path, e)
        return {"status": "error", "message": str(e)}


@mcp.tool()
def block_entity(tenant_id: str, entity_type: str, entity_value: str,
                  duration_seconds: Optional[int] = None) -> str:
    """将 user/device 加入 deny 黑名单，后续请求直接拦截 HTTP 403。

    Args:
        tenant_id: 租户 ID
        entity_type: 实体类型 user 或 device
        entity_value: 用户ID或设备ID
        duration_seconds: 封禁时长（秒），不传或 0 表示永久
    """
    action_id = "act_" + uuid.uuid4().hex[:12]
    list_name = "agent_deny"

    _create_list_if_missing(tenant_id, list_name, "user_id", "Agent auto-block list")

    result = _call_control("POST",
        f"/api/v1/admin/tenants/{tenant_id}/access-lists/{list_name}/entries",
        {"value": entity_value, "remark": f"agent_block:{entity_type}:{entity_value}"}
    )

    status = "success" if result.get("code") == 0 else "failed"
    db.save_action(tenant_id, action_id, entity_type, entity_value,
                   "block", "auto", status)

    return json.dumps({"action_id": action_id, "status": status}, ensure_ascii=False)


@mcp.tool()
def unblock_entity(tenant_id: str, entity_type: str, entity_value: str) -> str:
    """将 user/device 从 deny 黑名单中移除。

    Args:
        tenant_id: 租户 ID
        entity_type: 实体类型 user 或 device
        entity_value: 用户ID或设备ID
    """
    action_id = "act_" + uuid.uuid4().hex[:12]
    result = _call_control("DELETE",
        f"/api/v1/admin/tenants/{tenant_id}/access-lists/agent_deny/entries/{entity_value}"
    )
    status = "success" if result.get("code") == 0 else "failed"
    db.save_action(tenant_id, action_id, entity_type, entity_value,
                   "unblock", "auto", status)
    return json.dumps({"action_id": action_id, "status": status}, ensure_ascii=False)


@mcp.tool()
def rate_limit_entity(tenant_id: str, entity_type: str, entity_value: str,
                       max_requests: int = 100, window_seconds: int = 3600) -> str:
    """对 user/device 配置请求频率限制。

    Args:
        tenant_id: 租户 ID
        entity_type: 实体类型 user 或 device
        entity_value: 用户ID或设备ID
        max_requests: 窗口内最大请求数
        window_seconds: 时间窗口（秒）
    """
    action_id = "act_" + uuid.uuid4().hex[:12]
    detail = {"entity": entity_value, "max_requests": max_requests, "window_seconds": window_seconds}

    _call_control("POST",
        f"/api/v1/admin/tenants/{tenant_id}/cumulatives",
        {"key": f"rate_limit:{entity_type}:{entity_value}", "max": max_requests}
    )

    db.save_action(tenant_id, action_id, entity_type, entity_value,
                   "rate_limit", "auto", "success", detail_json=json.dumps(detail))
    return json.dumps({"action_id": action_id, "status": "success"}, ensure_ascii=False)


# ─── 复核类工具 ─────────────────────────────────────────────

@mcp.tool()
def create_review_ticket(tenant_id: str, assessment_id: str, entity_type: str,
                          entity_value: str, summary: str, evidence_json: str,
                          recommended_action: Optional[str] = None,
                          priority: str = "medium") -> str:
    """创建人工复核工单，进入 HIL 审批流程。工单创建后状态为 pending。

    Args:
        tenant_id: 租户 ID
        assessment_id: 关联评估ID（如 asmt_20260725_120000_abc123）
        entity_type: 实体类型
        entity_value: 实体值
        summary: 问题摘要
        evidence_json: 证据链 JSON（包含 total_events、max_risk、rule_hits 等分析结果）
        recommended_action: 推荐处置 block/rate_limit/captcha/review
        priority: 优先级 low/medium/high/critical
    """
    ticket_id = db.create_ticket(tenant_id, assessment_id, entity_type, entity_value,
                                  summary, evidence_json, recommended_action, priority)
    return json.dumps({"ticket_id": ticket_id, "status": "pending"})


@mcp.tool()
def get_ticket(tenant_id: str, ticket_id: str) -> str:
    """获取复核工单详情。"""
    ticket = db.get_ticket(tenant_id, ticket_id)
    return json.dumps(ticket or {"error": "not found"}, ensure_ascii=False)


@mcp.tool()
def list_tickets(tenant_id: str, status: Optional[str] = None, limit: int = 50) -> str:
    """列出复核工单，可按状态过滤。"""
    tickets = db.list_tickets(tenant_id, status, limit)
    return json.dumps(tickets, ensure_ascii=False)


@mcp.tool()
def approve_ticket(tenant_id: str, ticket_id: str, operator: str, comment: str = "") -> str:
    """审批通过工单，自动执行推荐的处置动作（如 block 会自动封禁实体）。

    Args:
        tenant_id: 租户 ID
        ticket_id: 工单ID
        operator: 运营人员
        comment: 审批意见
    """
    ticket = db.get_ticket(tenant_id, ticket_id)
    if not ticket:
        return json.dumps({"error": "ticket not found"})
    if ticket.get("status") != "pending":
        return json.dumps({"error": f"ticket already {ticket['status']}"})

    action = ticket.get("recommended_action")
    action_result = {}
    if action == "block":
        action_result = json.loads(block_entity(tenant_id, ticket["entity_type"],
                                  ticket["entity_value"], 604800))
    elif action in ("captcha", "rate_limit"):
        action_result = {"status": "skipped", "note": f"action {action} - manual execution needed"}

    db.update_ticket(tenant_id, ticket_id, "approved", operator, comment, action)
    return json.dumps({"ticket_id": ticket_id, "status": "approved", "action_result": action_result})


@mcp.tool()
def reject_ticket(tenant_id: str, ticket_id: str, operator: str, comment: str = "") -> str:
    """驳回复核工单，不执行处置。

    Args:
        tenant_id: 租户 ID
        ticket_id: 工单ID
        operator: 运营人员
        comment: 驳回理由
    """
    db.update_ticket(tenant_id, ticket_id, "rejected", operator, comment)
    return json.dumps({"ticket_id": ticket_id, "status": "rejected"})


# ─── 报告类工具 ─────────────────────────────────────────────

@mcp.tool()
def save_report_to_file(
    assessment_id: str,
    tenant_id: str,
    entity_type: str,
    entity_value: str,
    report_content: str,
    conclusion: str = "unknown",
    confidence: float = 0.0,
    ticket_id: Optional[str] = None,
) -> str:
    """将安全评估报告保存到本地文件。调用前必须先询问用户是否保存，确认后方可调用。

    Args:
        assessment_id: 评估ID（如 asmt_20260725_140000_abc123）
        tenant_id: 租户 ID
        entity_type: 实体类型 user 或 device
        entity_value: 用户ID或设备ID
        report_content: 报告全文内容（你刚才生成给用户看的报告内容）
        conclusion: 风险结论 safe/risky/uncertain
        confidence: 置信度 0-1
        ticket_id: 关联工单ID（可选，无工单则不传）
    """
    from datetime import datetime, timezone
    from pathlib import Path

    report_dir = Path(settings.report_dir)
    report_dir.mkdir(parents=True, exist_ok=True)

    safe_id = assessment_id.replace("/", "_").replace(" ", "_")
    filename = f"report_{safe_id}.md"
    filepath = report_dir / filename

    now = datetime.now(timezone.utc).isoformat()
    header = (
        f"<!-- VirbiusLLM Security Assessment Report -->\n"
        f"<!-- assessment_id: {assessment_id} -->\n"
        f"<!-- tenant_id: {tenant_id} -->\n"
        f"<!-- entity: {entity_type}={entity_value} -->\n"
        f"<!-- conclusion: {conclusion} -->\n"
        f"<!-- confidence: {confidence} -->\n"
        f"<!-- ticket_id: {ticket_id or 'N/A'} -->\n"
        f"<!-- generated_at: {now} -->\n\n"
    )

    content = f"# 安全评估报告\n\n> 实体: `{entity_type}={entity_value}` | 结论: **{conclusion}** | 置信度: {confidence:.0%} | 工单: {ticket_id or '无'}\n\n---\n\n{report_content}\n"

    filepath.write_text(header + content, encoding="utf-8")
    logger.info("Report saved to %s", filepath)

    return json.dumps({
        "status": "success",
        "filepath": str(filepath),
        "filename": filename,
    }, ensure_ascii=False)


def _create_list_if_missing(tenant_id: str, list_name: str, dimension: str, remark: str) -> None:
    _call_control("PUT",
        f"/api/v1/admin/tenants/{tenant_id}/access-lists/{list_name}",
        {"dimension": dimension, "remark": remark}
    )


def run_mcp(transport: str = "sse", port: int = 9091) -> None:
    logger.info("Starting FastMCP Server on port %d (transport=%s)", port, transport)
    mcp.run(transport=transport, host=settings.mcp_host, port=port)


def start_mcp_process(port: int = 9091) -> Process:
    proc = Process(target=run_mcp, args=("sse", port), daemon=True)
    proc.start()
    logger.info("FastMCP Server process started (PID=%d, port=%d)", proc.pid, port)
    return proc


if __name__ == "__main__":
    run_mcp()
