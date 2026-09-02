from typing import Literal, Optional

from pydantic import BaseModel, Field


class QueryRequest(BaseModel):
    entity_type: Optional[Literal["user", "device"]] = None
    entity_value: Optional[str] = None
    time_range_start: Optional[str] = None
    time_range_end: Optional[str] = None
    natural_query: Optional[str] = None
    mode: Literal["auto", "force_review", "dry_run"] = "auto"


class QueryResponse(BaseModel):
    assessment_id: Optional[str] = None
    entity_type: Optional[str] = None
    entity_value: Optional[str] = None
    conclusion: str = "unknown"
    confidence: float = 0
    summary: str = ""
    reasoning: str = ""
    risk_patterns: list[str] = []
    recommended_action: str = "none"
    auto_executed: bool = False
    ticket_id: Optional[str] = None
    error: Optional[str] = None
    challenge_id: Optional[str] = None
    challenge_rule_id: Optional[str] = None
    thread_id: Optional[str] = None
    pending_confirmation: Optional[dict] = None


class ResumeRequest(BaseModel):
    confirmed: bool = True


class TicketActionRequest(BaseModel):
    operator: str
    comment: str = ""
