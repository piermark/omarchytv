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
    "tv_list_pages": "read", "tv_page_badge": "notify",
    "tv_notify": "notify", "tv_ask_user": "notify", "tv_ask_result": "notify",
    "tv_set_theme": "control", "tv_send_key": "control", "tv_launch_app": "control", "tv_screensaver": "control",
    "tv_show_page": "control", "tv_close_page": "control", "tv_diagnostics": "control", "tv_radio": "control",
    "tv_open": "control", "tv_publish_page": "control", "tv_remove_page": "control",
    "tv_room_post": "notify", "tv_remind": "notify",
    # v2.22 #157 Home widgets: content updates need notify, everything that changes the layout control
    "tv_widget_types": "read", "tv_widget_list": "read", "tv_widget_update": "notify",
    "tv_widget_create": "control", "tv_widget_move": "control", "tv_widget_delete": "control",
    # v2.22 #158 / #159 Home widget pages
    "tv_page_list": "read", "tv_page_current": "read", "tv_page_create": "control", "tv_page_rename": "control",
    "tv_page_reorder": "control", "tv_page_delete": "control", "tv_page_show": "control",
    # v2.22 #160 Home Assistant through the TV (the token stays on the TV)
    "tv_ha_status": "read", "tv_ha_list": "read", "tv_ha_run": "control",
    # v2.22 #162 the radio's stations and sleep timer
    "tv_radio_stations": "read", "tv_radio_station_add": "control", "tv_radio_station_remove": "control",
    "tv_radio_sleep": "control",
    "tv_skill_qr": "control",   # v2.22 #161
    "tv_settings_get": "read", "tv_settings_set": "control",   # v2.22 #163
}

# v2.21 tools this PC answers itself (they push to the TV with the link it already has)
LOCAL_TOOLS = [
    {"name": "tv_room_post", "description": "Post a short line to a room pane on the TV Home (e.g. room 'hermes' for a "
     "cron report). The pane keeps the last 5 lines; at most 3 rooms; a room quiet for a day leaves the Home.",
     "inputSchema": {"type": "object", "properties": {
         "room": {"type": "string", "description": "a-z, 0-9 and -, up to 24 characters"},
         "text": {"type": "string", "description": "up to 64 characters"},
         "from": {"type": "string", "description": "who posts, up to 16 characters"}},
         "required": ["room", "text"]}},
    {"name": "tv_remind", "description": "Speak a reminder on the TV later (a systemd user timer on this PC). "
     "when: 90s, 10m, 1h30m, 45 (minutes) or HH:MM.",
     "inputSchema": {"type": "object", "properties": {
         "when": {"type": "string"}, "text": {"type": "string", "description": "up to 160 characters"}},
         "required": ["when", "text"]}},
]


def _local(name: str, args: dict, client, exe: str | None = None) -> dict:
    if name == "tv_room_post":
        from . import config, panes, rooms
        r = rooms.post(args.get("room", ""), args.get("text", ""), args.get("from", ""))
        client.put_widgets(panes.home_widgets(config.load_config()))
        return {"ok": True, "lines": len(r["msgs"])}
    from . import remind
    import os
    from pathlib import Path
    exe = exe or os.environ.get("OMARCHY_LINK_BIN") or str(Path(__file__).resolve().parents[2] / "bin" / "omarchy-link")
    return remind.schedule(exe, str(args.get("when", "")), str(args.get("text", "")))


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
    if method == "tools/call":
        params = req.get("params") or {}
        name = params.get("name") or ""
        if any(t["name"] == name for t in LOCAL_TOOLS):
            try:
                out = _local(name, params.get("arguments") or {}, client)
                return {"jsonrpc": "2.0", "id": rid, "result": {"content": [{"type": "text", "text": json.dumps(out)}]}}
            except Exception as e:   # bad room name, no systemd, TV away: a tool error the agent can read
                return {"jsonrpc": "2.0", "id": rid, "result": {
                    "content": [{"type": "text", "text": json.dumps({"error": str(e)[:200]})}], "isError": True}}
    timeout = 130.0 if method == "tools/call" else 15.0   # tv_ask_user can wait up to 110 s
    try:
        resp = client.request("POST", "/v1/mcp", req, timeout=timeout)
    except Exception as e:  # the TV is away or refused: an MCP error, never a crash of the agent's session
        return {"jsonrpc": "2.0", "id": rid, "error": {"code": -32000, "message": f"OmarchyTV: {e}"}}
    if method == "tools/list" and isinstance(resp.get("result"), dict):
        tools = (resp["result"].get("tools") or []) + LOCAL_TOOLS
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
