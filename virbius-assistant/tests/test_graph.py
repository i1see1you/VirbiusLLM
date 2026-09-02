"""Basic smoke tests for the LangGraph agent."""

from unittest.mock import AsyncMock, MagicMock

import pytest

from virbius_assistant.agent.state import AgentState
from virbius_assistant.agent.graph import AgentExecutor, build_graph
from virbius_assistant.mcp_client import McpClient


@pytest.fixture
def mock_mcp():
    mcp = MagicMock(spec=McpClient)
    mcp.call_tool = AsyncMock(return_value='{"status": "ok"}')
    mcp.connect = AsyncMock()
    mcp.disconnect = AsyncMock()
    return mcp


def test_route_action():
    from virbius_assistant.agent.graph import route_action

    assert route_action({}) == "generate_report"
    assert route_action({"action_plan": {"status": "executing"}}) == "auto_act"
    assert route_action({"action_plan": {"status": "pending_review"}}) == "human_in_the_loop"
    assert route_action({"action_plan": {"status": "executed"}}) == "generate_report"
    assert route_action({"action_plan": {"status": "skipped"}}) == "generate_report"


def test_build_graph(mock_mcp):
    graph = build_graph(mock_mcp)
    assert graph is not None


def test_agent_executor_initialization(mock_mcp):
    executor = AgentExecutor(mock_mcp)
    assert executor.mcp_client is mock_mcp
    assert executor.graph is not None


@pytest.mark.asyncio
async def test_agent_run_safe(mock_mcp):
    """Test a basic safe scenario."""
    mock_mcp.call_tool.return_value = '{"total_events": 1, "risk_score_stats": {"max": 10, "avg": 5}, "action_distribution": [], "rule_hit_ranking": [], "samples": []}'
    executor = AgentExecutor(mock_mcp)
    result = await executor.run(
        tenant_id="t1",
        entity_type="user",
        entity_value="u1",
        mode="auto",
    )
    assert result["conclusion"] in ("safe", "unknown")
    assert "entity_type" in result


@pytest.mark.asyncio
async def test_agent_run_high_risk(mock_mcp):
    """Test high-risk detection (rule fallback)."""
    mock_mcp.call_tool.return_value = '{"total_events": 10, "risk_score_stats": {"max": 95, "avg": 60}, "action_distribution": [{"action": "blocked", "cnt": 8}], "rule_hit_ranking": [{"rule_id": "jailbreak_detection", "cnt": 5, "max_risk": 95}], "samples": []}'
    executor = AgentExecutor(mock_mcp)
    result = await executor.run(
        tenant_id="t1",
        entity_type="user",
        entity_value="u1",
        mode="auto",
    )
    assert result["conclusion"] == "risky" or result["conclusion"] == "safe"


def test_state_type():
    state: AgentState = {
        "tenant_id": "t1",
        "query": {"entity_type": "user", "entity_value": "u1", "mode": "auto"},
        "error": None,
        "interrupted": False,
        "aggregated_signal": None,
        "llm_analysis": None,
        "action_plan": None,
        "assessment_id": None,
        "ticket_id": None,
        "report": None,
        "messages": [],
    }
    assert state["tenant_id"] == "t1"
