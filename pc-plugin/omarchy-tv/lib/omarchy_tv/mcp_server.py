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
)


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


def handle_rpc(req: dict, tv_client) -> dict | None:
    """Process one MCP JSON-RPC 2.0 request dict and return response dict (or None for notifications)."""
    method = req.get("method", "")
    req_id = req.get("id")
    params = req.get("params") if isinstance(req.get("params"), dict) else {}

    if method == "notifications/initialized":
        return None if "id" not in req else {"jsonrpc": "2.0", "id": req_id, "result": {}}
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
        return {"jsonrpc": "2.0", "id": req_id, "result": {"tools": list(ALLOWED_TOOLS)}}
    if method == "tools/call":
        name = params.get("name", "")
        args = params.get("arguments") if isinstance(params.get("arguments"), dict) else {}
        if name not in {t["name"] for t in ALLOWED_TOOLS}:
            return {"jsonrpc": "2.0", "id": req_id, "error": {"code": -32601, "message": f"Unknown tool: {name}"}}
        try:
            out = _call_tool(name, args, tv_client)
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


def _call_tool(name: str, args: dict, c) -> dict:
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
        return c.request("POST", "/v1/notify", {"text": text[:200], "from": str(args.get("from") or "AI agent")[:40]})
    if name == "tv_screensaver":
        return c.request("PUT", "/v1/screensaver", {"text": str(args.get("text", ""))})
    raise ValueError(f"unsupported tool: {name}")


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


def serve_stdio(tv_client_factory) -> int:
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except ValueError:
            continue
        resp = handle_rpc(req, tv_client_factory())
        if resp is not None:
            sys.stdout.write(json.dumps(resp) + "\n")
            sys.stdout.flush()
    return 0


def create_http_server(tv_client_factory, token: str, host: str = "127.0.0.1", port: int = 8990,
                       tailnet_only: bool = False) -> ThreadingHTTPServer:
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
            resp = handle_rpc(req, tv_client_factory())
            self._send(200, resp or {"jsonrpc": "2.0", "id": None, "result": {}})

        def _send(self, code: int, obj: dict):
            data = json.dumps(obj).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

    return ThreadingHTTPServer((host, port), Handler)


def serve_http(tv_client_factory, port: int = 8990, tunnel: bool = False, tailscale: bool = False) -> int:
    token = ensure_token()
    bind = "127.0.0.1"
    if tailscale:
        bind = tailnet_ip()
        if not bind:
            print("tailscale: no tailnet address on this PC (sudo tailscale up)", file=sys.stderr, flush=True)
            return 1
        tunnel = False   # one or the other: the tailnet never needs a public tunnel
    srv = create_http_server(tv_client_factory, token, bind, port, tailnet_only=tailscale)
    actual_port = srv.server_address[1]
    print(f"OmarchyTV MCP HTTP server listening on http://{bind}:{actual_port}/mcp"
          + (" (tailnet peers only)" if tailscale else ""), flush=True)
    print(f"Authorization: Bearer {token}", flush=True)
    proc = None
    if tunnel:
        cf = shutil.which("cloudflared")
        if not cf:
            print("cloudflared not found on PATH; running local HTTP only.", file=sys.stderr, flush=True)
        else:
            proc = subprocess.Popen(
                [cf, "tunnel", "--no-autoupdate", "--url", f"http://127.0.0.1:{actual_port}"],
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True
            )
            def _watch():
                for line in proc.stdout:
                    m = re.search(r"https://[a-zA-Z0-9.-]+\.trycloudflare\.com", line)
                    if m:
                        print(f"Grok Bot MCP Tunnel URL: {m.group(0)}/mcp", flush=True)
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
