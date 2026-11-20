"""OmarchyTV Model Context Protocol (MCP) server for Grok Bot, Claude, Hermes & Cursor.

Supports:
- stdio JSON-RPC 2.0 (`omarchy-tv mcp --stdio`) for local desktop agents
- HTTP JSON-RPC 2.0 (`omarchy-tv mcp --http [--port 8990] [--tunnel | --tailscale]`) with mandatory
  256-bit Bearer token authentication (~/.config/omarchy-tv/mcp-token, mode 0600)
  and optional `cloudflared` tunnel so cloud-hosted Grok Bot can control the TV safely.
- v2.21 `--tailscale`: listen on this PC's tailnet address only and answer only tailnet peers
  (100.64.0.0/10, fd7a:115c:a1e0::/48), still with the Bearer token: an agent on another box of your
  tailnet (Hermes, Claude Code on a server) reaches the TV without opening anything to the internet.

Security:
- Strict tool whitelist (TV status, themes, remote keys, apps, screensaver, AI status).
- Never exposes shell execution or local filesystem access on the PC.
"""

from __future__ import annotations

from . import VERSION

import hmac
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from . import themes

TOKEN_FILE = Path(os.environ.get("XDG_CONFIG_HOME") or (Path.home() / ".config")) / "omarchy-tv" / "mcp-token"

ALLOWED_TOOLS = (
    {
        "name": "tv_status",
        "description": "Read OmarchyTV status, active theme, capabilities, and foreground app.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "tv_list_themes",
        "description": "List bundled MIT Omarchy themes and custom themes installed on the TV.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "tv_set_theme",
        "description": "Switch the active Omarchy theme on the TV.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "name": {"type": "string", "description": "Theme slug (e.g. tokyo-night, jade-horse, hermarchy)"},
                "bg": {"type": "integer", "description": "Optional background index (default 0)"},
            },
            "required": ["name"],
        },
    },
    {
        "name": "tv_push_theme",
        "description": "Push an Omarchy theme from ~/.config/omarchy/themes/<name> on the PC to the TV.",
        "inputSchema": {
            "type": "object",
            "properties": {"name": {"type": "string", "description": "Theme slug"}},
            "required": ["name"],
        },
    },
    {
        "name": "tv_send_key",
        "description": "Send a remote key to the TV (home, back, up, down, left, right, select, play_pause, volume_up, volume_down, mute).",
        "inputSchema": {
            "type": "object",
            "properties": {"key": {"type": "string", "description": "Remote key name"}},
            "required": ["key"],
        },
    },
    {
        "name": "tv_list_apps",
        "description": "List installed Android TV apps on the TV.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "tv_launch_app",
        "description": "Launch an Android TV app by package name.",
        "inputSchema": {
            "type": "object",
            "properties": {"package": {"type": "string", "description": "Android package name"}},
            "required": ["package"],
        },
    },
    {
        "name": "tv_get_theme",
        "description": "Read the TV's current theme: background, foreground, accent, accent2, the full colors.toml "
                       "palette and the wallpaper URL (schema omarchytv-theme/1). Use it to style pages and dashboards.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "tv_notify",
        "description": "Show a short message on the TV over whatever is playing, e.g. 'build finished' or "
                       "'I need your answer'. Needs agent alerts on in the TV's settings.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "text": {"type": "string", "description": "One line, at most 200 characters"},
                "from": {"type": "string", "description": "Who is talking, e.g. Claude Code (at most 40 characters)"},
                "title": {"type": "string", "description": "Optional title, at most 60 characters"},
                "level": {"type": "string", "enum": ["info", "warn", "alert"], "description": "info waits while a film is on; warn and alert show at once"},
                "speak": {"type": "boolean", "description": "Also read it aloud on the TV"},
            },
            "required": ["text"],
        },
    },
    {
        "name": "tv_screensaver",
        "description": "Set or reset custom ASCII screensaver text on the TV.",
        "inputSchema": {
            "type": "object",
            "properties": {"text": {"type": "string", "description": "ASCII text (empty string resets to default)"}},
        },
    },
    {
        "name": "tv_get_state",
        "description": "What is on the TV now: screen (home, app, screensaver, off), the app, the theme, the music.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "tv_ask_user",
        "description": "Ask the person in front of the TV a question; they pick an answer with the remote. "
                       "Waits for the answer up to 'wait' seconds, then use tv_ask_result.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "question": {"type": "string", "description": "One line, at most 200 characters"},
                "choices": {"type": "array", "items": {"type": "string"}, "description": "Up to 4 short answers (default Yes / No)"},
                "timeout": {"type": "integer", "description": "Seconds the question stays on the TV (10-300, default 120)"},
                "wait": {"type": "integer", "description": "Seconds to wait here for the answer (0-90, default 60)"},
                "from": {"type": "string", "description": "Who asks, e.g. Claude Code"},
                "speak": {"type": "boolean", "description": "Also read the question aloud (silent when the TV's tones and voice are off)"},
                "detail": {"type": "string", "description": "The full text to read before answering, e.g. the whole command (2000 characters, 40 lines), scrollable on the TV"},
            },
            "required": ["question"],
        },
    },
    {
        "name": "tv_ask_result",
        "description": "The state of a question from tv_ask_user: pending, answered (with the answer) or expired.",
        "inputSchema": {"type": "object", "properties": {"id": {"type": "string"}, "wait": {"type": "integer", "description": "0-25 s"}},
                        "required": ["id"]},
    },
    {
        "name": "tv_show_page",
        "description": "Open a web page of the LAN or tailnet full screen on the TV (http:// and an IP address, e.g. a "
                       "dashboard), or up to 4 tiled like Hyprland windows (Android TV). BACK closes it.",
        "inputSchema": {"type": "object", "properties": {"url": {"type": "string"},
                                                         "urls": {"type": "array", "items": {"type": "string"}}}},
    },
    {
        "name": "tv_close_page",
        "description": "Close the page opened with tv_show_page.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "tv_radio_now",
        "description": "What Radiomarchy (Omarchy Radio on the TV) is playing.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "tv_radio",
        "description": "Control Radiomarchy: play (resume or start the playlist), pause, next, previous, stop.",
        "inputSchema": {"type": "object", "properties": {"action": {"type": "string", "enum": ["play", "pause", "next", "previous", "stop"]}},
                        "required": ["action"]},
    },
    {
        "name": "tv_open",
        "description": "Open a part of OmarchyTV on the TV: home, radiomarchy, cinemarchy, arcade or office.",
        "inputSchema": {"type": "object", "properties": {"target": {"type": "string", "enum": ["home", "radiomarchy", "cinemarchy", "arcade", "office"]}},
                        "required": ["target"]},
    },
    {
        "name": "tv_list_pages",
        "description": "The agent pages published as tiles on the TV's Home.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "tv_publish_page",
        "description": "Publish (or update) a LAN / tailnet page as a tile on the TV's Home: id, name, url, optional "
                       "one-letter icon and badge count. At most 16.",
        "inputSchema": {"type": "object", "properties": {
            "id": {"type": "string", "description": "a-z, 0-9, dashes"}, "name": {"type": "string"},
            "url": {"type": "string", "description": "http://<LAN or 100.x address>[:port]/path"},
            "icon": {"type": "string"}, "badge": {"type": "integer"}}, "required": ["id", "name", "url"]},
    },
    {
        "name": "tv_page_badge",
        "description": "Set the badge count of a published page (0 hides it), e.g. new replies.",
        "inputSchema": {"type": "object", "properties": {"id": {"type": "string"}, "badge": {"type": "integer"}}, "required": ["id", "badge"]},
    },
    {
        "name": "tv_remove_page",
        "description": "Remove a published page from the TV's Home.",
        "inputSchema": {"type": "object", "properties": {"id": {"type": "string"}}, "required": ["id"]},
    },
    # v2.22 #157: positioned, typed widgets on the Home (12 x 6 cells per page); same names and schemas as the TV's MCP
    {
        "name": "tv_widget_types",
        "description": "The Home widget types, the shape of each one's data, the grid (12 x 6 cells per page) and the limits.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "tv_widget_list",
        "description": "The widgets on the TV's Home (one page, or every page) with position and size, and the free cells.",
        "inputSchema": {"type": "object", "properties": {"page": {"type": "integer", "description": "Page, 1 = the Home"}}},
    },
    {
        "name": "tv_widget_create",
        "description": "Create a widget on the Home. type: lines, stat, text, image, chart, page, shortcuts, "
                       "homeassistant.scenes, homeassistant.entities, radio, qr (see tv_widget_types). Without x/y it takes the "
                       "first free slot; an occupied area answers cell_taken with free slots, unless push is true.",
        "inputSchema": {"type": "object", "properties": {
            "id": {"type": "string"}, "type": {"type": "string"}, "page": {"type": "integer"},
            "x": {"type": "integer"}, "y": {"type": "integer"}, "w": {"type": "integer"}, "h": {"type": "integer"},
            "title": {"type": "string"}, "icon": {"type": "string"}, "ttl": {"type": "integer"},
            "data": {"type": "object"}, "push": {"type": "boolean"}}, "required": ["id", "type"]},
    },
    {
        "name": "tv_widget_update",
        "description": "Change a widget's content (title, icon, ttl, data). Needs only the notify scope.",
        "inputSchema": {"type": "object", "properties": {
            "id": {"type": "string"}, "title": {"type": "string"}, "icon": {"type": "string"},
            "ttl": {"type": "integer"}, "data": {"type": "object"}}, "required": ["id"]},
    },
    {
        "name": "tv_widget_move",
        "description": "Move or resize a widget (page, x, y, w, h). cell_taken with free slots if the area is not free, "
                       "unless push is true.",
        "inputSchema": {"type": "object", "properties": {
            "id": {"type": "string"}, "page": {"type": "integer"}, "x": {"type": "integer"}, "y": {"type": "integer"},
            "w": {"type": "integer"}, "h": {"type": "integer"}, "push": {"type": "boolean"}}, "required": ["id"]},
    },
    {
        "name": "tv_widget_delete",
        "description": "Remove a widget from the Home.",
        "inputSchema": {"type": "object", "properties": {"id": {"type": "string"}}, "required": ["id"]},
    },
    {
        "name": "tv_diagnostics",
        "description": "Diagnostics for troubleshooting: launcher version, device, network (LAN, Tailscale), Link, "
                       "recent log lines. No secrets.",
        "inputSchema": {"type": "object", "properties": {}},
    },
)

# v2.21 scopes, as on the TV: read < notify < control. A server reachable from the internet (--tunnel,
# --cloudflare) defaults to notify: an agent in the cloud can read and talk to you, not drive the TV.
SCOPES = ("read", "notify", "control")
WIDGET_CREATE_KEYS = ("id", "type", "page", "x", "y", "w", "h", "title", "icon", "ttl", "data", "push")
TOOL_SCOPE = {
    "tv_status": "read", "tv_list_themes": "read", "tv_list_apps": "read", "tv_get_theme": "read", "tv_get_state": "read",
    "tv_radio_now": "read", "tv_radio": "control", "tv_open": "control",
    "tv_list_pages": "read", "tv_page_badge": "notify", "tv_publish_page": "control", "tv_remove_page": "control",
    "tv_notify": "notify", "tv_ask_user": "notify", "tv_ask_result": "notify",
    "tv_set_theme": "control", "tv_push_theme": "control", "tv_send_key": "control", "tv_launch_app": "control",
    "tv_screensaver": "control", "tv_show_page": "control", "tv_close_page": "control", "tv_diagnostics": "control",
    "tv_widget_types": "read", "tv_widget_list": "read", "tv_widget_update": "notify",
    "tv_widget_create": "control", "tv_widget_move": "control", "tv_widget_delete": "control",
}


def allowed(tool: str, scope: str) -> bool:
    need = TOOL_SCOPE.get(tool)
    return need is not None and SCOPES.index(scope) >= SCOPES.index(need)


def ensure_token(path: Path | None = None) -> str:
    p = path or TOKEN_FILE
    if p.is_file():
        tok = p.read_text(encoding="utf-8").strip()
        if len(tok) >= 32:
            os.chmod(p, 0o600)
            return tok
    p.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    tok = secrets.token_urlsafe(32)
    p.write_text(tok + "\n", encoding="utf-8")
    os.chmod(p, 0o600)
    return tok


LEVELS = ("info", "warn", "alert")


def default_max_level(public: bool, given: str | None) -> str:
    """v2.22 the loudest tv_notify level this server passes on: alert locally, warn through the internet."""
    if given in LEVELS:
        return given
    return "warn" if public else "alert"


def handle_rpc(req: dict, tv_client, scope: str = "control", max_level: str = "alert") -> dict | None:
    """Process one MCP JSON-RPC 2.0 request dict and return response dict (or None for notifications)."""
    method = req.get("method", "")
    req_id = req.get("id")
    params = req.get("params") if isinstance(req.get("params"), dict) else {}

    if method.startswith("notifications/") and "id" not in req:
        return None   # a notification gets no answer (HTTP: 202, no body)
    if method == "notifications/initialized":
        return {"jsonrpc": "2.0", "id": req_id, "result": {}}
    if method == "initialize":
        return {
            "jsonrpc": "2.0",
            "id": req_id,
            "result": {
                "protocolVersion": "2024-11-05",
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "omarchy-tv-mcp", "version": VERSION},
            },
        }
    if method == "ping":
        return {"jsonrpc": "2.0", "id": req_id, "result": {}}
    if method == "tools/list":
        return {"jsonrpc": "2.0", "id": req_id, "result": {"tools": [t for t in ALLOWED_TOOLS if allowed(t["name"], scope)]}}
    if method == "tools/call":
        name = params.get("name", "")
        args = params.get("arguments") if isinstance(params.get("arguments"), dict) else {}
        if name not in {t["name"] for t in ALLOWED_TOOLS}:
            return {"jsonrpc": "2.0", "id": req_id, "error": {"code": -32601, "message": f"Unknown tool: {name}"}}
        if not allowed(name, scope):
            return {"jsonrpc": "2.0", "id": req_id,
                    "error": {"code": -32601, "message": f"Not allowed on this server (scope {scope}): {name}"}}
        try:
            out = _call_tool(name, args, tv_client, max_level)
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {"content": [{"type": "text", "text": json.dumps(out)}], "isError": False},
            }
        except Exception as e:
            return {
                "jsonrpc": "2.0",
                "id": req_id,
                "result": {"content": [{"type": "text", "text": json.dumps({"error": str(e)})}], "isError": True},
            }
    return {"jsonrpc": "2.0", "id": req_id, "error": {"code": -32601, "message": f"Method not found: {method}"}}


def _call_tool(name: str, args: dict, c, max_level: str = "alert") -> dict:
    if name == "tv_status":
        return c.status()
    if name == "tv_list_themes":
        return themes.list_tv(c)
    if name == "tv_set_theme":
        tname = str(args.get("name", "")).strip()
        bg = int(args.get("bg", 0) or 0)
        return themes.select(c, tname, bg)
    if name == "tv_push_theme":
        tname = str(args.get("name", "")).strip()
        found = [th for th in themes.user_themes() if th.name == tname]
        if not found:
            raise ValueError(f"theme not found in ~/.config/omarchy/themes: {tname}")
        return themes.push(c, found[0])
    if name == "tv_send_key":
        return c.key(str(args.get("key", "")).strip())
    if name == "tv_list_apps":
        return c.apps()
    if name == "tv_launch_app":
        return c.launch(str(args.get("package", "")).strip())
    if name == "tv_get_theme":
        return _tv_theme(c)
    if name == "tv_notify":
        text = str(args.get("text", "")).strip()
        if not text:
            raise ValueError("text is required")
        level = args.get("level") if args.get("level") in LEVELS else "info"
        speak = args.get("speak") is True
        capped = LEVELS.index(level) > LEVELS.index(max_level)   # v2.22: the owner's rule, as the TV does for tokens
        if capped:
            level, speak = max_level, False
        out = c.request("POST", "/v1/notify", {"text": text[:200], "title": str(args.get("title") or "")[:60], "level": level,
                                                "speak": speak, "from": str(args.get("from") or "AI agent")[:40]})
        if capped and isinstance(out, dict):
            out = dict(out, capped=level)
        return out
    if name == "tv_get_state":
        return c.request("GET", "/v1/state")
    if name == "tv_ask_user":
        q = str(args.get("question", "")).strip()
        if not q:
            raise ValueError("question is required")
        r = c.request("POST", "/v1/ask", {"question": q[:200], "choices": [str(x)[:24] for x in (args.get("choices") or [])][:4],
                                         "timeout": max(10, min(300, int(args.get("timeout") or 120))),
                                         "from": str(args.get("from") or "AI agent")[:40],
                                         **({"speak": True} if args.get("speak") is True else {}),
                                         **({"detail": str(args["detail"])[:2000]} if args.get("detail") else {})})
        return _await_ask(c, r, max(0, min(90, int(args.get("wait", 60) or 0))))
    if name == "tv_ask_result":
        aid = str(args.get("id", ""))
        if not re.fullmatch(r"[0-9a-f]{16}", aid):
            raise ValueError("bad id")
        return c.request("GET", f"/v1/ask/{aid}?wait={max(0, min(25, int(args.get('wait', 0) or 0)))}", timeout=35)
    if name == "tv_show_page":
        urls = [str(u) for u in (args.get("urls") or [])][:4]
        return c.request("POST", "/v1/page", {"urls": urls} if urls else {"url": str(args.get("url", ""))})
    if name == "tv_close_page":
        return c.request("DELETE", "/v1/page")
    if name in ("tv_publish_page", "tv_page_badge", "tv_remove_page"):
        pid = str(args.get("id", ""))
        if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,39}", pid):
            raise ValueError("id: a-z, 0-9, dashes")
        if name == "tv_publish_page":
            return c.request("PUT", f"/v1/pages/{pid}", {"name": str(args.get("name", ""))[:32], "url": str(args.get("url", "")),
                                                         "icon": str(args.get("icon", ""))[:2], "badge": int(args.get("badge") or 0)})
        if name == "tv_page_badge":
            return c.request("POST", f"/v1/pages/{pid}/badge", {"badge": int(args.get("badge") or 0)})
        return c.request("DELETE", f"/v1/pages/{pid}")
    if name == "tv_list_pages":
        return c.request("GET", "/v1/pages")
    if name.startswith("tv_widget_"):   # v2.22 #157: the TV checks and places everything
        from urllib.parse import quote
        wid = quote(str(args.get("id", "")), safe="")
        if name == "tv_widget_types":
            return c.request("GET", "/v1/widgets/types")
        if name == "tv_widget_list":
            page = args.get("page")
            return c.request("GET", "/v1/widgets/list" + (f"?page={int(page)}" if isinstance(page, int) else ""))
        if name == "tv_widget_create":
            return c.request("POST", "/v1/widgets/item", {k: v for k, v in args.items() if k in WIDGET_CREATE_KEYS})
        if name == "tv_widget_delete":
            return c.request("DELETE", f"/v1/widgets/item/{wid}")
        keys = ("title", "icon", "ttl", "data") if name == "tv_widget_update" else ("page", "x", "y", "w", "h", "push")
        body = {k: v for k, v in args.items() if k in keys}
        if not body:
            raise ValueError("nothing to change")
        return c.request("PATCH", f"/v1/widgets/item/{wid}", body)
    if name == "tv_radio_now":
        return c.request("GET", "/v1/radio")
    if name == "tv_radio":
        return c.request("POST", "/v1/radio", {"action": str(args.get("action", ""))})
    if name == "tv_open":
        return c.request("POST", "/v1/open", {"target": str(args.get("target", ""))})
    if name == "tv_diagnostics":
        return c.request("GET", "/v1/diagnostics")
    if name == "tv_screensaver":
        return c.request("PUT", "/v1/screensaver", {"text": str(args.get("text", ""))})
    raise ValueError(f"unsupported tool: {name}")


def _await_ask(c, r: dict, wait: int) -> dict:
    """Long-poll the TV (25 s at a time) until the answer, the deadline or `wait` seconds."""
    import time
    end = time.time() + wait
    while r.get("state") == "pending" and time.time() < end:
        step = int(max(0, min(25, end - time.time())))
        r = c.request("GET", f"/v1/ask/{r['id']}?wait={step}", timeout=step + 10)
        if step == 0:
            break
    return r


def _tv_theme(c) -> dict:
    """GET /v1/theme on the TV: read-only and unsigned (the TV answers it to any LAN app)."""
    import http.client
    host, port = getattr(c, "host", None), getattr(c, "port", 47800)
    if not host:
        return c.request("GET", "/v1/theme")
    conn = http.client.HTTPConnection(host, port, timeout=5)
    try:
        conn.request("GET", "/v1/theme")
        r = conn.getresponse()
        data = json.loads(r.read(1 << 20) or b"{}")
        if r.status != 200:
            raise ValueError(f"TV answered {r.status}: {data.get('error', '')}")
        return data
    finally:
        conn.close()


TAILNET = ("100.64.0.0/10", "fd7a:115c:a1e0::/48")


def is_tailnet(addr: str) -> bool:
    import ipaddress
    try:
        ip = ipaddress.ip_address(addr.split("%", 1)[0])
    except ValueError:
        return False
    if getattr(ip, "ipv4_mapped", None):
        ip = ip.ipv4_mapped
    return any(ip in ipaddress.ip_network(n) for n in TAILNET if ipaddress.ip_network(n).version == ip.version)


def tailnet_ip(run=subprocess.run) -> str | None:
    """This PC's Tailscale IPv4 (`tailscale ip -4`), or None."""
    if not shutil.which("tailscale"):
        return None
    try:
        r = run(["tailscale", "ip", "-4"], capture_output=True, text=True, timeout=4)
    except (OSError, subprocess.SubprocessError):
        return None
    for line in (r.stdout or "").split():
        if is_tailnet(line.strip()):
            return line.strip()
    return None


class LazyClient:
    """The TV client, made on the first tool call: initialize and tools/list work before pairing, and a factory that
    prints (\"TV not paired\") or exits never writes on the MCP stdout nor kills the server."""

    def __init__(self, factory):
        self._factory, self._c = factory, None

    def _get(self):
        if self._c is None:
            import contextlib
            try:
                with contextlib.redirect_stdout(sys.stderr):
                    self._c = self._factory()
            except SystemExit:
                raise RuntimeError("the TV is not paired: run omarchy-link pair (or omarchy-tv pair)") from None
        return self._c

    def request(self, *a, **kw):
        return self._get().request(*a, **kw)

    def __getattr__(self, name):
        if name.startswith("_"):
            raise AttributeError(name)
        return getattr(self._get(), name)


def serve_stdio(tv_client_factory, scope: str = "control", max_level: str = "alert") -> int:
    lazy = LazyClient(tv_client_factory)
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except ValueError:
            continue
        resp = handle_rpc(req, lazy, scope, max_level)
        if resp is not None:
            sys.stdout.write(json.dumps(resp) + "\n")
            sys.stdout.flush()
    return 0


def create_http_server(tv_client_factory, token: str, host: str = "127.0.0.1", port: int = 8990,
                       tailnet_only: bool = False, scope: str = "control", require_access: bool = False,
                       verifier=None, max_level: str = "alert") -> ThreadingHTTPServer:
    expected = f"Bearer {token}"

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_POST(self):
            if tailnet_only and not is_tailnet(self.client_address[0]):
                self._send(403, {"error": "not_tailnet"})
                return
            if self.path not in ("/mcp", "/v1/mcp", "/"):
                self._send(404, {"error": "not_found"})
                return
            jwt = self.headers.get("Cf-Access-Jwt-Assertion", "")
            if verifier is not None:
                why = verifier.check(jwt) if jwt else "missing"
                if why:
                    self._send(403, {"error": "access_denied", "message": f"Cloudflare Access token: {why}"})
                    return
            if require_access and not self.headers.get("Cf-Access-Jwt-Assertion"):
                # v2.21 --cloudflare --access: the origin is 127.0.0.1, only cloudflared reaches it, and Cloudflare adds
                # this header only after Access let the caller in. No header: Access is not in front of this tunnel.
                self._send(403, {"error": "access_required", "message": "put Cloudflare Access in front of this tunnel"})
                return
            auth = self.headers.get("Authorization", "").strip()
            if not hmac.compare_digest(auth, expected):
                self._send(401, {"error": "unauthorized", "message": "missing or invalid Bearer token"})
                return
            length = int(self.headers.get("Content-Length", "0") or "0")
            if length <= 0 or length > 256 * 1024:
                self._send(400, {"error": "bad_request"})
                return
            try:
                req = json.loads(self.rfile.read(length).decode("utf-8"))
            except ValueError:
                self._send(400, {"error": "invalid_json"})
                return
            resp = handle_rpc(req, LazyClient(tv_client_factory), scope, max_level)
            if resp is None:   # a notification: accepted, no body (MCP streamable HTTP)
                self.send_response(202)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            self._send(200, resp)

        def do_GET(self):   # no server-initiated stream here: JSON 405, not the stdlib's 501 HTML page
            self._send(405, {"error": "method_not_allowed", "message": "POST JSON-RPC to /mcp"}, allow="POST")

        do_DELETE = do_PUT = do_GET

        def _send(self, code: int, obj: dict, allow: str | None = None):
            data = json.dumps(obj).encode("utf-8")
            self.send_response(code)
            if allow:
                self.send_header("Allow", allow)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

    return ThreadingHTTPServer((host, port), Handler)


def tunnel_argv(cf: str, port: int, name: str | None) -> list:
    """Quick tunnel (a random trycloudflare.com URL) or, v2.21, a named tunnel on your own domain."""
    if name:
        return [cf, "tunnel", "--no-autoupdate", "run", "--url", f"http://127.0.0.1:{port}", name]
    return [cf, "tunnel", "--no-autoupdate", "--url", f"http://127.0.0.1:{port}"]


def default_scope(public: bool, scope: str | None) -> str:
    """Local and tailnet: control. Through the internet (a tunnel): notify unless asked."""
    if scope in SCOPES:
        return scope
    return "notify" if public else "control"


def serve_http(tv_client_factory, port: int = 8990, tunnel: bool = False, tailscale: bool = False,
               cloudflare: str | None = None, scope: str | None = None, access: bool = False,
               access_team: str | None = None, access_aud: str | None = None, max_level: str | None = None) -> int:
    token = ensure_token()
    bind = "127.0.0.1"
    if tailscale:
        bind = tailnet_ip()
        if not bind:
            print("tailscale: no tailnet address on this PC (sudo tailscale up)", file=sys.stderr, flush=True)
            return 1
        if tunnel or cloudflare:
            print("--tailscale: ignoring --tunnel/--cloudflare (the tailnet never needs a public tunnel)",
                  file=sys.stderr, flush=True)
        tunnel, cloudflare = False, None   # one or the other
    public = bool(tunnel or cloudflare)
    scope = default_scope(public, scope)
    max_level = default_max_level(public, max_level)
    verifier = None
    if cloudflare and (access_team or access_aud):
        from .cf_access import AccessVerifier
        try:
            import cryptography  # noqa: F401
        except ImportError:
            print("--access-team/--access-aud need python-cryptography (pacman -S python-cryptography)", file=sys.stderr)
            return 1
        verifier = AccessVerifier(access_team or "", access_aud or "")
    if cloudflare and not (access or verifier):
        print("warning: --cloudflare without Access: anyone with the URL and the bearer token reaches this server. "
              "Put a Cloudflare Access policy in front and add --access-team/--access-aud (docs/CLOUDFLARE.md).",
              file=sys.stderr, flush=True)
    srv = create_http_server(tv_client_factory, token, bind, port, tailnet_only=tailscale, scope=scope,
                             require_access=bool(cloudflare and (access or verifier)), verifier=verifier,
                             max_level=max_level)
    actual_port = srv.server_address[1]
    print(f"OmarchyTV MCP HTTP server listening on http://{bind}:{actual_port}/mcp"
          + (" (tailnet peers only)" if tailscale else "") + f", tools: {scope}"
          + (f", notifications at most {max_level}" if max_level != "alert" else ""), flush=True)
    print(f"Authorization: Bearer {token}", flush=True)
    proc = None
    if public:
        cf = shutil.which("cloudflared")
        if not cf:
            print("cloudflared not found on PATH; running local HTTP only.", file=sys.stderr, flush=True)
        else:
            proc = subprocess.Popen(tunnel_argv(cf, actual_port, cloudflare),
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            if cloudflare:
                print(f"Cloudflare tunnel '{cloudflare}' -> this server. Its hostname is the one you routed to it "
                      "(cloudflared tunnel route dns). See docs/CLOUDFLARE.md." + (
                          " Access JWT verified (team, audience, signature)." if verifier else
                          " Access header required." if access else
                          " Tip: put Cloudflare Access in front and add --access-team/--access-aud."), flush=True)

            def _watch():
                for line in proc.stdout:
                    m = re.search(r"https://[a-zA-Z0-9.-]+\.trycloudflare\.com", line)
                    if m:
                        print(f"MCP tunnel URL: {m.group(0)}/mcp", flush=True)
            threading.Thread(target=_watch, daemon=True).start()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        srv.shutdown()
        if proc:
            proc.terminate()
    return 0


# ---- v2.21 setup helpers: omarchy-tv cloudflare setup | service ----------------------------------------------------

def setup_commands(name: str, hostname: str) -> list:
    """The cloudflared commands that make a named tunnel and route a hostname to it (run once)."""
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9-]{0,62}", name or ""):
        raise ValueError("tunnel name: letters, digits and dashes")
    if not re.fullmatch(r"(?=.{4,253}$)([a-z0-9-]{1,63}\.)+[a-z]{2,63}", hostname or ""):
        raise ValueError("hostname: e.g. tv-mcp.example.com")
    return [["cloudflared", "tunnel", "create", name], ["cloudflared", "tunnel", "route", "dns", name, hostname]]


def service_unit(argv: list) -> str:
    """A systemd user unit that keeps `omarchy-tv mcp …` running (and restarts it)."""
    import shlex
    return ("[Unit]\nDescription=OmarchyTV MCP server\nAfter=network-online.target\nWants=network-online.target\n\n"
            "[Service]\nExecStart=" + " ".join(shlex.quote(a) for a in argv) + "\nRestart=on-failure\nRestartSec=10\n\n"
            "[Install]\nWantedBy=default.target\n")
