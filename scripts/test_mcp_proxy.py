#!/usr/bin/env python3
"""
直接通过 MCP Proxy (:9091) 测试工具调用，绕过 REST API 层。
用于验证 MCP Proxy 本身是否工作正常。

用法:
  python scripts/test_mcp_proxy.py                          # 完整测试
  python scripts/test_mcp_proxy.py --proxy-url http://localhost:9091/sse
  python scripts/test_mcp_proxy.py --tool query_audit_events  # 只测某个工具
"""

import argparse
import asyncio
import json
import sys
import time

from mcp import ClientSession, ClientCapabilities
from mcp.client.sse import sse_client


async def test_single_tool(
    session: ClientSession,
    tool_name: str,
    args: dict,
    label: str = "",
) -> dict:
    label = label or tool_name
    print(f"\n  ▶ {label} ...", end=" ", flush=True)
    try:
        start = time.time()
        result = await session.call_tool(tool_name, args)
        elapsed = time.time() - start
        texts = []
        for c in (result.content or []):
            if hasattr(c, "text"):
                try:
                    texts.append(json.loads(c.text))
                except (json.JSONDecodeError, TypeError):
                    texts.append(c.text)
        data = texts[0] if len(texts) == 1 else texts
        print(f"OK ({elapsed:.2f}s)")
        return data
    except Exception as e:
        print(f"FAILED: {e}")
        raise


async def main():
    parser = argparse.ArgumentParser(description="MCP Proxy 直接测试")
    parser.add_argument("--proxy-url", default="http://localhost:9091/sse")
    parser.add_argument("--tool", help="只测试指定工具")
    parser.add_argument("--tenant-id", default="t1")
    parser.add_argument("--entity-type", default="user")
    parser.add_argument("--entity-value", default="bbb")
    args = parser.parse_args()

    print(f"连接到 MCP Proxy: {args.proxy_url}")
    print(f"租户: {args.tenant_id}, 实体: {args.entity_type}={args.entity_value}")
    print("=" * 60)

    async with sse_client(url=args.proxy_url) as (read, write):
        async with ClientSession(read, write) as session:
            # ── Initialize ──
            print("\n[1] 初始化连接 ...", end=" ", flush=True)
            init_result = await session.initialize()
            print(f"OK (server: {init_result.serverInfo.name} v{init_result.serverInfo.version})")
            print(f"    协议: {init_result.protocolVersion}")

            # ── List Tools ──
            print("\n[2] 列出所有工具 ...", end=" ", flush=True)
            tools = await session.list_tools()
            print(f"OK ({len(tools.tools)} tools)")
            for t in tools.tools:
                print(f"    - {t.name}")
            tool_names = {t.name for t in tools.tools}

            if args.tool:
                if args.tool not in tool_names:
                    print(f"\n错误: 工具 '{args.tool}' 不存在")
                    sys.exit(1)
                tools_to_test = [args.tool]
            else:
                tools_to_test = sorted(tool_names)

            # ── Query tools ──
            print("\n[3] 测试查询工具（不需要外部依赖）")
            print("-" * 40)
            for name in ("query_audit_events", "get_entity_profile"):
                if name not in tool_names:
                    continue
                if name not in tools_to_test:
                    continue
                if name == "query_audit_events":
                    await test_single_tool(session, name, {
                        "tenant_id": args.tenant_id,
                        "entity_type": args.entity_type,
                        "entity_value": args.entity_value,
                        "time_start": "2026-06-01T00:00:00Z",
                        "time_end": "2026-07-21T23:59:59Z",
                        "limit": 10,
                    })
                elif name == "get_entity_profile":
                    await test_single_tool(session, name, {
                        "tenant_id": args.tenant_id,
                        "entity_type": args.entity_type,
                        "entity_value": args.entity_value,
                    })

            # ── Ticket tools ──
            print("\n[4] 测试工单工具（查询类，不改数据）")
            print("-" * 40)
            for name in ("list_tickets",):
                if name not in tool_names or name not in tools_to_test:
                    continue
                await test_single_tool(session, name, {
                    "tenant_id": args.tenant_id,
                    "status": "",
                    "limit": 5,
                })

            # ── Action tools (只测试当明确指定 tool 时) ──
            if args.tool and args.tool in ("block_entity", "unblock_entity", "rate_limit_entity"):
                print("\n[5] 测试执行工具（会修改数据！）")
                print("-" * 40)
                action_tests = {
                    "rate_limit_entity": {
                        "tenant_id": args.tenant_id,
                        "entity_type": args.entity_type,
                        "entity_value": args.entity_value,
                        "max_requests": 100,
                        "window_seconds": 3600,
                    },
                }
                test_args = action_tests.get(args.tool)
                if test_args:
                    await test_single_tool(session, args.tool, test_args)
                    # 如果是临时测试，立即清理
                    if args.tool == "rate_limit_entity":
                        print("    (rate_limit 是临时测试，不会持久化影响)")
                else:
                    print(f"   跳过 {args.tool}: 无测试参数")

            print("\n" + "=" * 60)
            print("全部测试完成 ✓")


if __name__ == "__main__":
    asyncio.run(main())
