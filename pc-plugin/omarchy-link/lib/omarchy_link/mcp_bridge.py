"""v2.21 `omarchy-link mcp`: the TV's own MCP server (POST /v1/mcp on the TV) on stdio, for desktop agents.

    claude mcp add omarchytv -- omarchy-link mcp            # every tool
    claude mcp add omarchytv -- omarchy-link mcp --scope notify

Each JSON-RPC line from stdin goes to the TV over the paired, signed (and, with python-cryptography, encrypted)
link; the answer comes back on stdout. No token to copy: the pairing is the credential. --scope limits the tools
here, before anything reaches the TV (read < notify < control, the same scopes as the TV's app tokens).
"""

from __future__ import annotations

import json
import sys

SCOPES = ("read", "notify", "control")
TOOL_SCOPE = {
    "tv_status": "read", "tv_get_state": "read", "tv_get_theme": "read", "tv_list_themes": "read",
    "tv_list_apps": "read", "tv_ai_status": "read", "tv_cinemarchy": "read", "tv_radio_now": "read",
    "tv_notify": "notify", "tv_ask_user": "notify", "tv_ask_result": "notify",
    "tv_set_theme": "control", "tv_send_key": "control", "tv_launch_app": "control", "tv_screensaver": "control",
    "tv_show_page": "control", "tv_close_page": "control", "tv_diagnostics": "control", "tv_radio": "control",
    "tv_open": "control", "tv_publish_page": "control", "tv_remove_page": "control",
}


def allowed(tool: str, scope: str) -> bool:
    need = TOOL_SCOPE.get(tool, "control")   # a tool this version does not know needs control
    return SCOPES.index(scope) >= SCOPES.index(need)


def handle(req: dict, client, scope: str = "control") -> dict | None:
    method = req.get("method", "")
    rid = req.get("id")
    if method == "tools/call":
        name = ((req.get("params") or {}).get("name")) or ""
        if not allowed(name, scope):
            return {"jsonrpc": "2.0", "id": rid, "error": {"code": -32601, "message": f"Not allowed here (scope {scope}): {name}"}}
    if method.startswith("notifications/") and "id" not in req:
        return None
    timeout = 130.0 if method == "tools/call" else 15.0   # tv_ask_user can wait up to 110 s
    try:
        resp = client.request("POST", "/v1/mcp", req, timeout=timeout)
    except Exception as e:  # the TV is away or refused: an MCP error, never a crash of the agent's session
        return {"jsonrpc": "2.0", "id": rid, "error": {"code": -32000, "message": f"OmarchyTV: {e}"}}
    if method == "tools/list" and isinstance(resp.get("result"), dict):
        tools = resp["result"].get("tools") or []
        resp["result"]["tools"] = [t for t in tools if allowed(t.get("name", ""), scope)]
    return resp


def serve(client_factory, scope: str = "control", stdin=None, stdout=None) -> int:
    stdin, stdout = stdin or sys.stdin, stdout or sys.stdout
    client = None
    for line in stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except ValueError:
            continue
        if not isinstance(req, dict):
            continue
        if client is None:
            client = client_factory()
        resp = handle(req, client, scope)
        if resp is not None:
            stdout.write(json.dumps(resp) + "\n")
            stdout.flush()
    return 0
