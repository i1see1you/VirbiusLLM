from typing import Annotated, Literal, Optional, TypedDict

from langgraph.graph.message import add_messages


class QueryIntent(TypedDict):
    entity_type: Literal["user", "device"]
    entity_value: str
    time_start: Optional[str]
    time_end: Optional[str]
    natural_query: Optional[str]
    mode: Literal["auto", "force_review", "dry_run"]


class AggregatedSignal(TypedDict):
    total_events: int
    max_risk_score: int
    avg_risk_score: float
    action_distribution: list
    rule_hit_ranking: list
    samples: list


class LLMAnalysis(TypedDict):
    conclusion: Literal["safe", "risky", "uncertain"]
    confidence: float
    reasoning: str
    risk_patterns: list[str]
    recommended_action: Literal["none", "block", "captcha", "rate_limit", "review"]


class ActionPlan(TypedDict):
    action_type: Optional[str]
    status: str


class AgentState(TypedDict):
    tenant_id: str
    query: QueryIntent
    error: Optional[str]
    interrupted: bool

    aggregated_signal: Optional[AggregatedSignal]
    llm_analysis: Optional[LLMAnalysis]
    action_plan: Optional[ActionPlan]

    assessment_id: Optional[str]
    ticket_id: Optional[str]
    report: Optional[str]

    pending_confirmation: Optional[dict]

    messages: Annotated[list, add_messages]
