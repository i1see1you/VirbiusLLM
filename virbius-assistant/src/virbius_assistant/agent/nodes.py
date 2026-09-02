import json
import logging
import re
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any

from langchain_core.messages import HumanMessage
from langchain_ollama import ChatOllama
from langgraph.types import interrupt

from virbius_assistant.agent.prompts import (
    BEHAVIOR_ANALYZER_PROMPT,
    QUERY_PARSER_PROMPT,
    REPORT_FORMATTER_PROMPT,
)
from virbius_assistant.agent.state import AgentState
from virbius_assistant.config import settings
from virbius_assistant.mcp_client import McpClient

logger = logging.getLogger(__name__)

_ENTITY_PATTERNS = [
    (r'(?:用户|用户\s*)[：:]\s*(\S+)', "user"),
    (r'(?:设备|设备\s*)[：:]\s*(\S+)', "device"),
    (r'\buser\s*[：:]\s*(\S+)', "user"),
    (r'\bdevice\s*[：:]\s*(\S+)', "device"),
    (r'[用户]{2}\s+(\S+)', "user"),
    (r'设备\s+(\S+)', "device"),
]

_TIME_PATTERNS = [
    (r'近一个月|近1个月|过去30天', 30),
    (r'近一周|近1周|近7天|最近7天', 7),
    (r'近三天|近3天|最近3天', 3),
    (r'近一天|近1天|昨天', 1),
    (r'今天', 0),
    (r'近(\d+)天', lambda m: int(m.group(1))),
    (r'近(\d+)\s*个月', lambda m: int(m.group(1)) * 30),
]


def _extract_entity_from_text(text: str) -> tuple[str, str]:
    for pattern, entity_type in _ENTITY_PATTERNS:
        m = re.search(pattern, text, re.IGNORECASE)
        if m:
            return entity_type, m.group(1).strip().rstrip("，。,.")
    return "", ""


def _extract_time_from_text(text: str) -> tuple[str | None, str | None]:
    now = datetime.now(timezone.utc)
    for pattern, days in _TIME_PATTERNS:
        m = re.search(pattern, text)
        if m:
            delta_days = days(m) if callable(days) else days
            start = (now - timedelta(days=delta_days)).isoformat()
            return start, now.isoformat()
    return None, None


async def parse_query(state: AgentState, mcp: McpClient) -> dict[str, Any]:
    query = state["query"]
    natural = query.get("natural_query")
    if not natural:
        return {"query": query}

    llm = ChatOllama(model=settings.llm_model, base_url=settings.llm_base_url, temperature=0)
    prompt = QUERY_PARSER_PROMPT.format(
        natural_query=natural,
        current_time=datetime.now(timezone.utc).isoformat(),
    )
    try:
        resp = await llm.ainvoke([HumanMessage(content=prompt)])
        parsed = json.loads(resp.content.strip().removeprefix("```json").removesuffix("```").strip())
        query["entity_type"] = parsed.get("entity_type", query["entity_type"])
        query["entity_value"] = parsed.get("entity_value", query["entity_value"])
        now = datetime.now(timezone.utc)
        query["time_start"] = parsed.get("time_start") or (now - timedelta(days=7)).isoformat()
        query["time_end"] = parsed.get("time_end") or now.isoformat()
        if not parsed.get("entity_value"):
            entity_type, entity_value = _extract_entity_from_text(natural)
            if entity_value:
                query["entity_type"] = entity_type
                query["entity_value"] = entity_value
    except Exception as e:
        logger.warning("Query parse failed: %s", e)
        entity_type, entity_value = _extract_entity_from_text(natural)
        if entity_value:
            query["entity_type"] = entity_type
            query["entity_value"] = entity_value
        time_start, time_end = _extract_time_from_text(natural)
        query.setdefault("time_start", time_start or (datetime.now(timezone.utc) - timedelta(days=7)).isoformat())
        query.setdefault("time_end", time_end or datetime.now(timezone.utc).isoformat())
    return {"query": query}


async def confirm_time_range(state: AgentState, mcp: McpClient) -> dict[str, Any]:
    """检查查询时间范围，超过 365 天时中断等待人工确认。"""
    query = state["query"]
    time_start = query.get("time_start")
    time_end = query.get("time_end")

    if not time_start or not time_end:
        return {}

    try:
        start = datetime.fromisoformat(time_start.replace("Z", "+00:00"))
        end = datetime.fromisoformat(time_end.replace("Z", "+00:00"))
    except Exception:
        return {}

    days = (end - start).days
    if days <= 365:
        return {}

    confirmation = interrupt({
        "type": "time_range_confirmation",
        "message": "查询时间范围较长查询时间会比较慢,确认执行吗?",
        "time_range_days": days,
        "time_start": time_start,
        "time_end": time_end,
    })

    if not confirmation:
        return {"error": "用户取消了查询"}

    return {}


async def aggregate_signals(state: AgentState, mcp: McpClient) -> dict[str, Any]:
    q = state["query"]
    if not q.get("entity_value"):
        logger.warning("entity_value 为空，跳过查询")
        return {}
    try:
        raw = await mcp.call_tool("query_audit_events", {
            "tenant_id": state["tenant_id"],
            "entity_type": q["entity_type"],
            "entity_value": q["entity_value"],
            "time_start": q.get("time_start"),
            "time_end": q.get("time_end"),
            "limit": settings.max_sample_signals,
        })
        data = json.loads(raw) if isinstance(raw, str) else raw
        return {"aggregated_signal": data}
    except Exception as e:
        # Detect MCP challenge_required error (JSON-RPC code -32011)
        challenge_data = _extract_challenge(e)
        if challenge_data:
            logger.info("challenge required: %s", challenge_data.get("challenge_id"))
            return {"aggregated_signal": {"_challenge": True, **challenge_data}}
        logger.warning("aggregate_signals 查询失败: %s", e)
        return {"aggregated_signal": {"_error": True, "error": str(e)}}


def _extract_challenge(exc: Exception) -> dict[str, Any] | None:
    """Extract challenge details from an MCP error (code -32011)."""
    try:
        from mcp.shared.exceptions import McpError
        if isinstance(exc, McpError) and exc.error.code == -32011:
            data = exc.error.data
            if isinstance(data, str):
                data = json.loads(data)
            return data if isinstance(data, dict) else {"raw": str(data)}
    except Exception:
        pass
    # Fallback: check string representation for challenge indicators
    exc_str = str(exc)
    if "challenge_required" in exc_str or "-32011" in exc_str:
        return {"reason": exc_str}
    return None


async def analyze_behavior(state: AgentState, mcp: McpClient) -> dict[str, Any]:
    sig = state.get("aggregated_signal")
    if not sig:
        return {"llm_analysis": {
            "conclusion": "safe", "confidence": 0.95,
            "reasoning": "无审计数据，默认安全。",
            "risk_patterns": [], "recommended_action": "none",
        }}
    # If a challenge was triggered, skip normal analysis and return challenge info
    if sig.get("_challenge"):
        challenge_id = sig.get("challenge_id", "")
        rule_id = sig.get("rule_id", "")
        reason = sig.get("reason", "challenge_required")
        return {"llm_analysis": {
            "conclusion": "challenge_required", "confidence": 1.0,
            "reasoning": f"工具调用被规则 {rule_id} 触发 challenge，等待人工审批。challenge_id={challenge_id}",
            "risk_patterns": [rule_id] if rule_id else [],
            "recommended_action": "review",
        }}
    # If the tool call failed (non-challenge error), return error info
    if sig.get("_error"):
        return {"llm_analysis": {
            "conclusion": "error", "confidence": 1.0,
            "reasoning": f"工具调用失败: {sig.get('error', 'unknown')}",
            "risk_patterns": [], "recommended_action": "none",
        }}
    if sig.get("total_events", 0) < settings.risk_score_threshold:
        return {"llm_analysis": {
            "conclusion": "safe", "confidence": 0.95,
            "reasoning": f"总事件 {sig.get('total_events',0)} 低于阈值 {settings.risk_score_threshold}，无显著风险。",
            "risk_patterns": [], "recommended_action": "none",
        }}

    q = state["query"]
    rule_hits = "\n".join(
        f"- {r['rule_id']}: {r['cnt']}次 最高风险{r['max_risk']}"
        for r in sig.get("rule_hit_ranking", [])
    ) or "无"
    samples = json.dumps(sig.get("samples", [])[:10], ensure_ascii=False, indent=2)
    profile = json.dumps(
        await mcp.call_tool("get_entity_profile", {
            "tenant_id": state["tenant_id"],
            "entity_type": q["entity_type"],
            "entity_value": q["entity_value"],
        }) or {}, ensure_ascii=False
    )

    prompt = BEHAVIOR_ANALYZER_PROMPT.format(
        entity_type=q["entity_type"], entity_value=q["entity_value"],
        time_range=f"{q.get('time_start')} ~ {q.get('time_end')}",
        total_events=sig["total_events"],
        max_risk=sig.get("risk_score_stats", {}).get("max", 0),
        avg_risk=sig.get("risk_score_stats", {}).get("avg", 0),
        action_distribution=json.dumps(sig.get("action_distribution", []), ensure_ascii=False),
        rule_hits=rule_hits,
        samples=samples,
        profile_history=profile,
    )

    try:
        llm = ChatOllama(model=settings.llm_model, base_url=settings.llm_base_url, temperature=0)
        resp = await llm.ainvoke([HumanMessage(content=prompt)])
        parsed = json.loads(resp.content.strip().removeprefix("```json").removesuffix("```").strip())
        return {"llm_analysis": {
            "conclusion": parsed.get("conclusion", "uncertain"),
            "confidence": float(parsed.get("confidence", 0.5)),
            "reasoning": parsed.get("reasoning", ""),
            "risk_patterns": parsed.get("risk_patterns", []),
            "recommended_action": parsed.get("recommended_action", "review"),
        }}
    except Exception as e:
        logger.warning("LLM analysis failed, fallback to rule: %s", e)
        return {"llm_analysis": _rule_fallback(sig)}


def _rule_fallback(sig: dict) -> dict:
    max_risk = sig.get("risk_score_stats", {}).get("max", 0)
    total = sig.get("total_events", 0)
    for r in sig.get("rule_hit_ranking", []):
        if "jailbreak" in r.get("rule_id", ""):
            return {"conclusion": "risky", "confidence": 0.8,
                    "reasoning": f"越狱规则命中{r['cnt']}次", "risk_patterns": ["jailbreak"],
                    "recommended_action": "block"}
    if max_risk >= 80 and total >= 5:
        return {"conclusion": "risky", "confidence": 0.7,
                "reasoning": f"高风险事件{total}次", "risk_patterns": ["high_risk"],
                "recommended_action": "block"}
    if max_risk >= 50 and total >= 20:
        return {"conclusion": "uncertain", "confidence": 0.6,
                "reasoning": f"中等风险{total}次", "risk_patterns": ["elevated"],
                "recommended_action": "review"}
    return {"conclusion": "safe", "confidence": 0.9,
            "reasoning": "无明显风险", "risk_patterns": [], "recommended_action": "none"}


async def assess_risk(state: AgentState, mcp: McpClient) -> dict[str, Any]:
    analysis = state.get("llm_analysis")
    if not analysis:
        return {"error": "no analysis"}
    c, conf = analysis["conclusion"], analysis["confidence"]
    mode = state["query"].get("mode", "auto")
    aid = f"asmt_{datetime.now().strftime('%Y%m%d_%H%M%S')}_{uuid.uuid4().hex[:6]}"
    result = {"assessment_id": aid}

    if c == "safe" or c == "challenge_required" or c == "error":
        result["action_plan"] = {"action_type": None, "status": "skipped"}
    elif c == "risky" and conf >= settings.confidence_threshold and mode == "auto":
        result["action_plan"] = {"action_type": analysis["recommended_action"], "status": "executing"}
    else:
        result["action_plan"] = {"action_type": analysis["recommended_action"], "status": "pending_review"}
    return result


async def auto_act(state: AgentState, mcp: McpClient) -> dict[str, Any]:
    plan = state.get("action_plan")
    if not plan or plan.get("status") != "executing":
        return {}
    q = state["query"]
    at = plan["action_type"]
    try:
        if at == "block":
            await mcp.call_tool("block_entity", {
                "tenant_id": state["tenant_id"], "entity_type": q["entity_type"],
                "entity_value": q["entity_value"], "duration_seconds": 604800,
            })
        elif at == "rate_limit":
            await mcp.call_tool("rate_limit_entity", {
                "tenant_id": state["tenant_id"], "entity_type": q["entity_type"],
                "entity_value": q["entity_value"], "max_requests": 100, "window_seconds": 3600,
            })
        plan["status"] = "executed"
    except Exception as e:
        logger.error("Auto action failed: %s", e)
        plan["status"] = "failed"
    return {"action_plan": plan}


async def human_in_the_loop(state: AgentState, mcp: McpClient) -> dict[str, Any]:
    plan = state.get("action_plan")
    if not plan or plan.get("status") != "pending_review":
        return {}
    q, analysis, sig = state["query"], state.get("llm_analysis", {}), state.get("aggregated_signal", {})
    evidence = {
        "entity": f"{q['entity_type']}:{q['entity_value']}",
        "time_range": f"{q.get('time_start')} ~ {q.get('time_end')}",
        "total_events": sig.get("total_events", 0),
        "max_risk": sig.get("risk_score_stats", {}).get("max", 0),
        "conclusion": analysis.get("conclusion"), "confidence": analysis.get("confidence"),
        "reasoning": analysis.get("reasoning"), "recommended_action": analysis.get("recommended_action"),
    }
    priority = "high" if (analysis.get("confidence", 1) < 0.5) else "medium"
    summary = f"{q['entity_type']}={q['entity_value']} 结论={analysis.get('conclusion')} 置信度={analysis.get('confidence',0):.2f}"
    try:
        raw = await mcp.call_tool("create_review_ticket", {
            "tenant_id": state["tenant_id"], "assessment_id": state.get("assessment_id", ""),
            "entity_type": q["entity_type"], "entity_value": q["entity_value"],
            "summary": summary, "evidence_json": json.dumps(evidence, ensure_ascii=False),
            "recommended_action": analysis.get("recommended_action"), "priority": priority,
        })
        ticket = json.loads(raw) if isinstance(raw, str) else raw
        return {"ticket_id": ticket.get("ticket_id"), "interrupted": True}
    except Exception as e:
        return {"error": f"create ticket failed: {e}"}


async def generate_report(state: AgentState, mcp: McpClient) -> dict[str, Any]:
    analysis = state.get("llm_analysis", {})
    plan = state.get("action_plan", {})
    q = state["query"]
    action_summary = "无处置"
    if plan.get("status") == "executed":
        action_summary = f"已自动执行 {plan['action_type']}"
    elif plan.get("status") == "pending_review":
        action_summary = f"等待人工复核（工单: {state.get('ticket_id','?')}）"
    elif plan.get("status") == "skipped":
        action_summary = "无风险，跳过处置"

    asmt_id = state.get("assessment_id", "")
    q_text = q.get("natural_query", f"{q['entity_type']}={q['entity_value']}")
    db_data = {
        "tenant_id": state["tenant_id"], "assessment_id": asmt_id,
        "entity_type": q["entity_type"], "entity_value": q["entity_value"],
        "query_text": q_text, "conclusion": analysis.get("conclusion", "unknown"),
        "confidence": analysis.get("confidence", 0),
        "llm_reasoning": analysis.get("reasoning", ""),
        "rule_signals_json": json.dumps(state.get("aggregated_signal", {}), ensure_ascii=False),
        "recommended_action": analysis.get("recommended_action"),
        "auto_executed": plan.get("status") == "executed",
        "ticket_id": state.get("ticket_id"),
    }
    try:
        from virbius_assistant import db
        db.save_assessment(**db_data)
    except Exception as e:
        logger.warning("Failed to save assessment: %s", e)

    prompt = REPORT_FORMATTER_PROMPT.format(
        conclusion=analysis.get("conclusion", "unknown"),
        confidence=analysis.get("confidence", 0),
        reasoning=analysis.get("reasoning", "无"),
        patterns=", ".join(analysis.get("risk_patterns", [])),
        action=analysis.get("recommended_action", "none"),
        action_summary=action_summary,
        ticket=state.get("ticket_id", "无"),
    )
    try:
        llm = ChatOllama(model=settings.llm_model, base_url=settings.llm_base_url, temperature=0)
        resp = await llm.ainvoke([HumanMessage(content=prompt)])
        report = resp.content.strip()
    except Exception:
        report = (f"实体 {q['entity_type']}={q['entity_value']} 分析完毕。"
                  f"结论: {analysis.get('conclusion','?')} 置信度: {analysis.get('confidence',0):.0%}。{action_summary}。")
    return {"report": report}


async def confirm_save_report(state: AgentState, mcp: McpClient) -> dict[str, Any]:
    """询问用户是否将报告保存到本地文件，确认后调用 save_report_to_file。"""
    report = state.get("report", "")
    if not report:
        return {}

    confirmation = interrupt({
        "type": "report_save_confirmation",
        "message": "是否需要将生成的安全评估报告写到本地文件?",
        "assessment_id": state.get("assessment_id", ""),
    })

    if not confirmation:
        return {}

    q = state["query"]
    analysis = state.get("llm_analysis", {})
    try:
        await mcp.call_tool("save_report_to_file", {
            "assessment_id": state.get("assessment_id", ""),
            "tenant_id": state["tenant_id"],
            "entity_type": q["entity_type"],
            "entity_value": q["entity_value"],
            "report_content": report,
            "conclusion": analysis.get("conclusion", "unknown"),
            "confidence": analysis.get("confidence", 0),
            "ticket_id": state.get("ticket_id"),
        })
    except Exception as e:
        logger.warning("Failed to save report to file: %s", e)
        return {"error": f"save report failed: {e}"}

    return {}
