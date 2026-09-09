"""Owned local protocol fixture; never a production application asset."""
import anyio
from pathlib import Path
import subprocess
import sys
from mcp.server import MCPServer

server = MCPServer("Hermes Android lifecycle fixture", log_level="ERROR")


@server.tool()
async def echo(text: str) -> str:
    return "fixture: " + text


@server.tool()
async def wait_until_cancelled(started_path: str) -> str:
    Path(started_path).write_text("running", encoding="utf-8")
    await anyio.sleep_forever()
    return "unreachable"


@server.tool()
async def spawn_detached_child(pid_path: str) -> str:
    child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(90)"], start_new_session=True)
    Path(pid_path).write_text(str(child.pid), encoding="utf-8")
    return "owned descendant started"


if __name__ == "__main__":
    server.run(transport="stdio")
