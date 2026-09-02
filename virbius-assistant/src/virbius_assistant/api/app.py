import logging
from contextlib import asynccontextmanager
from typing import Optional

from fastapi import FastAPI, HTTPException, Request

from virbius_assistant.agent.graph import AgentExecutor
from virbius_assistant.api.schemas import QueryRequest, QueryResponse, ResumeRequest, TicketActionRequest
from virbius_assistant.config import settings
from virbius_assistant.mcp_client import McpClient

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    import asyncio

    mcp_port = getattr(app.state, "mcp_port", 9091)
    client = McpClient(
        server_url=f"http://localhost:{mcp_port}/sse",
        app_id=settings.agent_app_id,
        tenant_id=settings.agent_tenant_id,
        license_jwt=settings.agent_license_jwt,
    )
    for attempt in range(5):
        try:
            await client.connect()
            app.state.mcp_client = client
            from virbius_assistant.agent.graph import AgentExecutor
            app.state.agent_executor = AgentExecutor(client)
            logger.info("Connected to FastMCP Server (attempt %d)", attempt + 1)
            break
        except Exception as e:
            logger.warning("FastMCP connect attempt %d/5 failed: %s", attempt + 1, e)
            if attempt < 4:
                await asyncio.sleep(1)
            else:
                app.state.mcp_client = None
                logger.error("FastMCP unavailable after 5 retries")
    yield
    if app.state.mcp_client:
        await app.state.mcp_client.disconnect()


def create_app(mcp_port: int = 9091) -> FastAPI:
    app = FastAPI(title="VirbiusLLM Agent Assistant", version="0.1.0", lifespan=lifespan)
    app.state.mcp_port = mcp_port

    from fastapi.middleware.cors import CORSMiddleware
    app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_credentials=True,
                       allow_methods=["*"], allow_headers=["*"])

    @app.get("/health")
    async def health():
        return {"status": "ok", "service": "virbius-assistant"}

    @app.post("/api/v1/tenants/{tenant_id}/agent/query", response_model=QueryResponse)
    async def query_risk(tenant_id: str, req: QueryRequest, request: Request):
        if not req.natural_query and (not req.entity_type or not req.entity_value):
            raise HTTPException(400, "需要 natural_query 或 entity_type + entity_value")
        executor = request.app.state.agent_executor
        if not executor:
            raise HTTPException(503, "Agent 未就绪")

        result = await executor.run(
            tenant_id=tenant_id,
            entity_type=req.entity_type or "user",
            entity_value=req.entity_value or "",
            time_start=req.time_range_start,
            time_end=req.time_range_end,
            natural_query=req.natural_query,
            mode=req.mode,
        )
        return QueryResponse(**result)

    @app.post("/api/v1/tenants/{tenant_id}/agent/query/{thread_id}/resume")
    async def resume_query(tenant_id: str, thread_id: str, req: ResumeRequest, request: Request):
        """恢复因时间范围确认而中断的 Agent 执行。"""
        executor = request.app.state.agent_executor
        if not executor:
            raise HTTPException(503, "Agent 未就绪")

        result = await executor.resume(
            thread_id=thread_id,
            confirmed=req.confirmed,
        )
        return QueryResponse(**result)

    @app.get("/api/v1/tenants/{tenant_id}/agent/tickets")
    async def list_tickets(tenant_id: str, request: Request,
                            status: Optional[str] = None, limit: int = 50):
        from virbius_assistant import db
        return db.list_tickets(tenant_id, status, limit)

    @app.get("/api/v1/tenants/{tenant_id}/agent/tickets/{ticket_id}")
    async def get_ticket(tenant_id: str, ticket_id: str, request: Request):
        from virbius_assistant import db
        t = db.get_ticket(tenant_id, ticket_id)
        if not t:
            raise HTTPException(404)
        return t

    @app.post("/api/v1/tenants/{tenant_id}/agent/tickets/{ticket_id}/approve")
    async def approve_ticket(tenant_id: str, ticket_id: str, req: TicketActionRequest, request: Request):
        mcp = request.app.state.mcp_client
        if not mcp:
            raise HTTPException(503, "FastMCP 未连接")
        raw = await mcp.call_tool("approve_ticket", {
            "tenant_id": tenant_id, "ticket_id": ticket_id,
            "operator": req.operator, "comment": req.comment,
        })
        import json
        return json.loads(raw) if isinstance(raw, str) else raw

    @app.post("/api/v1/tenants/{tenant_id}/agent/tickets/{ticket_id}/reject")
    async def reject_ticket(tenant_id: str, ticket_id: str, req: TicketActionRequest, request: Request):
        mcp = request.app.state.mcp_client
        if not mcp:
            raise HTTPException(503, "FastMCP 未连接")
        raw = await mcp.call_tool("reject_ticket", {
            "tenant_id": tenant_id, "ticket_id": ticket_id,
            "operator": req.operator, "comment": req.comment,
        })
        import json
        return json.loads(raw) if isinstance(raw, str) else raw

    @app.get("/api/v1/tenants/{tenant_id}/agent/assessments/{assessment_id}")
    async def get_assessment(tenant_id: str, assessment_id: str):
        import sqlite3
        from virbius_assistant.db import get_db
        with get_db() as conn:
            row = conn.execute(
                "SELECT * FROM tb_agent_assessments WHERE tenant_id=? AND assessment_id=?",
                (tenant_id, assessment_id)
            ).fetchone()
        if not row:
            raise HTTPException(404)
        return dict(row)

    return app


def run_api(port: int = 9090, mcp_port: int = 9091):
    import uvicorn
    app = create_app(mcp_port=mcp_port)
    uvicorn.run(app, host="0.0.0.0", port=port)
