import argparse
import asyncio

from virbius_assistant.api.app import run_api


def main():
    parser = argparse.ArgumentParser(description="VirbiusLLM Agent Assistant")
    parser.add_argument("--mode", choices=["api", "mcp", "all"], default="all",
                        help="启动模式: api=REST, mcp=FastMCP, all=两者")
    parser.add_argument("--mcp-port", type=int, default=9091, help="FastMCP SSE 端口")
    parser.add_argument("--api-port", type=int, default=9090, help="REST API 端口")
    args = parser.parse_args()

    if args.mode == "api":
        run_api(port=args.api_port)
    elif args.mode == "mcp":
        from virbius_assistant.mcp_server import run_mcp
        run_mcp(transport="sse", port=args.mcp_port)
    else:
        run_all(args)


def run_all(args):
    import multiprocessing
    from virbius_assistant.mcp_server import start_mcp_process

    mcp_proc = start_mcp_process(port=args.mcp_port)
    try:
        run_api(port=args.api_port, mcp_port=args.mcp_port)
    finally:
        mcp_proc.terminate()
        mcp_proc.join()


if __name__ == "__main__":
    main()
