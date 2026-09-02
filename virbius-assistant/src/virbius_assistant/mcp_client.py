"""MCP Client — SSE to FastMCP Server."""

import json
import logging
import uuid
from contextlib import AsyncExitStack
from typing import Any

from mcp import ClientSession
from mcp.client.sse import sse_client
from mcp.shared.exceptions import McpError
from mcp.types import (
    ClientCapabilities,
    ClientNotification,
    ClientRequest,
    Implementation,
    InitializeRequest,
    InitializeRequestParams,
    InitializeResult,
    InitializedNotification,
    LATEST_PROTOCOL_VERSION,
    RequestParams,
)

logger = logging.getLogger(__name__)


class _AuthClientSession(ClientSession):
    def __init__(self, read, write, session_id: str, app_id: str, tenant_id: str, license_jwt: str, **kwargs):
        super().__init__(read, write, **kwargs)
        self._auth_session_id = session_id
        self._auth_app_id = app_id
        self._auth_tenant_id = tenant_id
        self._auth_license_jwt = license_jwt

    async def initialize(self) -> InitializeResult:
        params = InitializeRequestParams(
            protocolVersion=LATEST_PROTOCOL_VERSION,
            capabilities=ClientCapabilities(),
            clientInfo=Implementation(name="virbius-assistant", version="0.1.0"),
        )
        meta = {"session_id": self._auth_session_id}
        if self._auth_app_id:
            meta["app_id"] = self._auth_app_id
        if self._auth_tenant_id:
            meta["tenant_id"] = self._auth_tenant_id
        if self._auth_license_jwt:
            meta["license_jwt"] = self._auth_license_jwt
        params.meta = RequestParams.Meta(**meta)

        result = await self.send_request(
            ClientRequest(InitializeRequest(params=params)),
            InitializeResult,
        )

        if result.protocolVersion not in (
            LATEST_PROTOCOL_VERSION,
            "2024-11-05",
        ):
            raise RuntimeError(
                f"Unsupported protocol version from the server: {result.protocolVersion}"
            )

        self._server_capabilities = result.capabilities

        await self.send_notification(ClientNotification(InitializedNotification()))

        return result


class McpClient:
    """MCP Client using standard SSE transport with optional agent credentials."""

    def __init__(
        self,
        server_url: str = "http://localhost:9091/sse",
        app_id: str = "",
        tenant_id: str = "",
        license_jwt: str = "",
    ):
        self._server_url = server_url
        self._app_id = app_id
        self._tenant_id = tenant_id
        self._license_jwt = license_jwt
        self._session_id = f"sess_{uuid.uuid4().hex[:12]}"
        self._stack = AsyncExitStack()
        self._session: ClientSession | None = None

    async def connect(self):
        try:
            ctx = sse_client(url=self._server_url)
            read, write = await self._stack.enter_async_context(ctx)
            self._session = await self._stack.enter_async_context(
                _AuthClientSession(
                    read, write,
                    session_id=self._session_id,
                    app_id=self._app_id,
                    tenant_id=self._tenant_id,
                    license_jwt=self._license_jwt,
                )
            )
            await self._session.initialize()
            logger.info(
                "MCP client connected to %s (app_id=%s, tenant_id=%s, session_id=%s)",
                self._server_url, self._app_id, self._tenant_id, self._session_id,
            )
        except Exception as e:
            logger.warning("MCP client SSE connect failed: %s", e)
            await self._stack.aclose()
            raise

    async def disconnect(self):
        if self._session:
            await self._stack.aclose()
            self._session = None

    async def _reconnect(self) -> None:
        """Close the existing SSE connection and establish a new one with initialize."""
        logger.info("Reconnecting MCP client to %s (session_id=%s) ...", self._server_url, self._session_id)
        await self._stack.aclose()
        self._stack = AsyncExitStack()
        ctx = sse_client(url=self._server_url)
        read, write = await self._stack.enter_async_context(ctx)
        self._session = await self._stack.enter_async_context(
            _AuthClientSession(
                read, write,
                session_id=self._session_id,
                app_id=self._app_id,
                tenant_id=self._tenant_id,
                license_jwt=self._license_jwt,
            )
        )
        await self._session.initialize()
        logger.info("MCP client reconnected to %s", self._server_url)

    async def _with_reconnect(self, fn, *args, **kwargs):
        """Call fn; on session-not-initialized error, reconnect once and retry."""
        try:
            return await fn(*args, **kwargs)
        except McpError as e:
            if e.error.code == -32600:
                logger.warning("Session expired, reconnecting and retrying...")
                await self._reconnect()
                return await fn(*args, **kwargs)
            raise

    async def call_tool(self, name: str, args: dict[str, Any] | None = None) -> Any:
        if not self._session:
            raise RuntimeError("MCP client not connected")

        async def _call():
            result = await self._session.call_tool(name, args or {})
            texts = []
            if result.content:
                for c in result.content:
                    if hasattr(c, "text"):
                        text = c.text
                        try:
                            texts.append(json.loads(text))
                        except (json.JSONDecodeError, TypeError):
                            texts.append(text)
            return texts[0] if len(texts) == 1 else texts

        return await self._with_reconnect(_call)

    async def list_tools(self) -> list[dict]:
        if not self._session:
            return []

        async def _list():
            result = await self._session.list_tools()
            return [{"name": t.name, "description": t.description} for t in result.tools]

        return await self._with_reconnect(_list)
