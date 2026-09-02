import logging
from functools import partial
from typing import Any, Literal, Optional

from langgraph.checkpoint.memory import MemorySaver
from langgraph.constants import END, START
from langgraph.graph import StateGraph

from virbius_assistant.agent.nodes import (
    aggregate_signals as _aggregate_signals,
    analyze_behavior as _analyze_behavior,
    assess_risk as _assess_risk,
    auto_act as _auto_act,
    confirm_save_report as _confirm_save_report,
    confirm_time_range as _confirm_time_range,
    generate_report as _generate_report,
    human_in_the_loop as _human_in_the_loop,
    parse_query as _parse_query,
)
from virbius_assistant.agent.state import AgentState
from virbius_assistant.mcp_client import McpClient

logger = logging.getLogger(__name__)


def route_action(state: AgentState) -> Literal["auto_act", "human_in_the_loop", "generate_report"]:
    plan = state.get("action_plan")
    if not plan:
        return "generate_report"
    s = plan.get("status")
    if s == "executing":
        return "auto_act"
    if s == "pending_review":
        return "human_in_the_loop"
    return "generate_report"


def build_graph(mcp: McpClient) -> StateGraph:
    parse_query = partial(_parse_query, mcp=mcp)
    confirm_time_range = partial(_confirm_time_range, mcp=mcp)
    aggregate_signals = partial(_aggregate_signals, mcp=mcp)
    analyze_behavior = partial(_analyze_behavior, mcp=mcp)
    assess_risk = partial(_assess_risk, mcp=mcp)
    auto_act = partial(_auto_act, mcp=mcp)
    human_in_the_loop = partial(_human_in_the_loop, mcp=mcp)
    generate_report = partial(_generate_report, mcp=mcp)
    confirm_save_report = partial(_confirm_save_report, mcp=mcp)

    builder = StateGraph(AgentState)

    builder.add_node("parse_query", parse_query)
    builder.add_node("confirm_time_range", confirm_time_range)
    builder.add_node("aggregate_signals", aggregate_signals)
    builder.add_node("analyze_behavior", analyze_behavior)
    builder.add_node("assess_risk", assess_risk)
    builder.add_node("auto_act", auto_act)
    builder.add_node("human_in_the_loop", human_in_the_loop)
    builder.add_node("generate_report", generate_report)
    builder.add_node("confirm_save_report", confirm_save_report)

    builder.add_edge(START, "parse_query")
    builder.add_edge("parse_query", "confirm_time_range")
    builder.add_edge("confirm_time_range", "aggregate_signals")
    builder.add_edge("aggregate_signals", "analyze_behavior")
    builder.add_edge("analyze_behavior", "assess_risk")
    builder.add_conditional_edges("assess_risk", route_action, {
        "auto_act": "auto_act",
        "human_in_the_loop": "human_in_the_loop",
        "generate_report": "generate_report",
    })
    builder.add_edge("auto_act", "generate_report")
    builder.add_edge("human_in_the_loop", "generate_report")
    builder.add_edge("generate_report", "confirm_save_report")
    builder.add_edge("confirm_save_report", END)

    return builder


class AgentExecutor:
    def __init__(self, mcp_client: McpClient):
        self.mcp_client = mcp_client
        self.graph = build_graph(mcp_client).compile(checkpointer=MemorySaver())

    async def run(
        self,
        tenant_id: str,
        entity_type: str = "user",
        entity_value: str = "",
        time_start: Optional[str] = None,
        time_end: Optional[str] = None,
        natural_query: Optional[str] = None,
        mode: str = "auto",
        thread_id: Optional[str] = None,
    ) -> dict[str, Any]:
        import uuid

        config = {"configurable": {"thread_id": thread_id or uuid.uuid4().hex}}

        initial: AgentState = {
            "tenant_id": tenant_id,
            "query": {
                "entity_type": entity_type,
                "entity_value": entity_value,
                "time_start": time_start,
                "time_end": time_end,
                "natural_query": natural_query,
                "mode": mode,
            },
            "error": None,
            "interrupted": False,
            "aggregated_signal": None,
            "llm_analysis": None,
            "action_plan": None,
            "assessment_id": None,
            "ticket_id": None,
            "report": None,
            "pending_confirmation": None,
            "messages": [],
        }

        async for event in self.graph.astream_events(
            initial, config, version="v2"
        ):
            if event.get("event") == "on_chain_end":
                node = event.get("name", "")
                data = event.get("data", {}).get("output", {})
                if data:
                    logger.debug("Node %s done: %s", node, data)

        return await self._extract_result(config)

    async def resume(
        self,
        thread_id: str,
        confirmed: bool = True,
    ) -> dict[str, Any]:
        """恢复因时间范围确认而中断的 Agent 执行。"""
        from langgraph.types import Command

        config = {"configurable": {"thread_id": thread_id}}

        async for event in self.graph.astream_events(
            Command(resume=confirmed), config, version="v2"
        ):
            if event.get("event") == "on_chain_end":
                node = event.get("name", "")
                data = event.get("data", {}).get("output", {})
                if data:
                    logger.debug("Node %s done: %s", node, data)

        return await self._extract_result(config)

    async def _extract_result(self, config: dict) -> dict[str, Any]:
        """从 graph state 提取最终结果，检测 interrupt。"""
        final = await self.graph.aget_state(config)
        v = final.values

        # 检测 interrupt（时间范围确认 / 报告保存确认）
        if final.next:
            for task in final.tasks:
                if task.interrupts:
                    interrupt_data = task.interrupts[0].value
                    return {
                        "assessment_id": v.get("assessment_id"),
                        "entity_type": v.get("query", {}).get("entity_type"),
                        "entity_value": v.get("query", {}).get("entity_value"),
                        "conclusion": "pending_confirmation",
                        "summary": v.get("report", "") or "",
                        "error": None,
                        "ticket_id": v.get("ticket_id"),
                        "pending_confirmation": interrupt_data,
                        "thread_id": config["configurable"]["thread_id"],
                    }

        analysis = v.get("llm_analysis") or {}
        plan = v.get("action_plan") or {}
        sig = v.get("aggregated_signal") or {}
        challenge = sig.get("_challenge", False)
        return {
            "assessment_id": v.get("assessment_id"),
            "entity_type": v.get("query", {}).get("entity_type"),
            "entity_value": v.get("query", {}).get("entity_value"),
            "conclusion": analysis.get("conclusion", "unknown"),
            "confidence": analysis.get("confidence", 0),
            "summary": v.get("report", ""),
            "reasoning": analysis.get("reasoning", ""),
            "risk_patterns": analysis.get("risk_patterns", []),
            "recommended_action": analysis.get("recommended_action", "none"),
            "auto_executed": plan.get("status") == "executed",
            "ticket_id": v.get("ticket_id"),
            "error": v.get("error"),
            "challenge_id": sig.get("challenge_id") if challenge else None,
            "challenge_rule_id": sig.get("rule_id") if challenge else None,
            "thread_id": config["configurable"]["thread_id"],
        }
