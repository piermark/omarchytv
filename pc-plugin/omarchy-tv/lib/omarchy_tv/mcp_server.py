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
                       "dashboard). BACK closes it.",
        "inputSchema": {"type": "object", "properties": {"url": {"type": "string"}}, "required": ["url"]},
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
        "name": "tv_diagnostics",
        "description": "Diagnostics for troubleshooting: launcher version, device, network (LAN, Tailscale), Link, "
                       "recent log lines. No secrets.",
        "inputSchema": {"type": "object", "properties": {}},
    },
)

# v2.21 scopes, as on the TV: read < notify < control. A server reachable from the internet (--tunnel,
# --cloudflare) defaults to notify: an agent in the cloud can read and talk to you, not drive the TV.
SCOPES = ("read", "notify", "control")
TOOL_SCOPE = {
    "tv_status": "read", "tv_list_themes": "read", "tv_list_apps": "read", "tv_get_theme": "read", "tv_get_state": "read",
    "tv_radio_now": "read", "tv_radio": "control", "tv_open": "control",
    "tv_notify": "notify", "tv_ask_user": "notify", "tv_ask_result": "notify",
    "tv_set_theme": "control", "tv_push_theme": "control", "tv_send_key": "control", "tv_launch_app": "control",
    "tv_screensaver": "control", "tv_show_page": "control", "tv_close_page": "control", "tv_diagnostics": "control",
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


def handle_rpc(req: dict, tv_client, scope: str = "control") -> dict | None:
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
        level = args.get("level") if args.get("level") in ("info", "warn", "alert") else "info"
        return c.request("POST", "/v1/notify", {"text": text[:200], "title": str(args.get("title") or "")[:60], "level": level,
                                                 "from": str(args.get("from") or "AI agent")[:40]})
    if name == "tv_get_state":
        return c.request("GET", "/v1/state")
    if name == "tv_ask_user":
        q = str(args.get("question", "")).strip()
        if not q:
            raise ValueError("question is required")
        r = c.request("POST", "/v1/ask", {"question": q[:200], "choices": [str(x)[:24] for x in (args.get("choices") or [])][:4],
                                         "timeout": max(10, min(300, int(args.get("timeout") or 120))),
                                         "from": str(args.get("from") or "AI agent")[:40]})
        return _await_ask(c, r, max(0, min(90, int(args.get("wait", 60) or 0))))
    if name == "tv_ask_result":
        aid = str(args.get("id", ""))
        if not re.fullmatch(r"[0-9a-f]{16}", aid):
            raise ValueError("bad id")
        return c.request("GET", f"/v1/ask/{aid}?wait={max(0, min(25, int(args.get('wait', 0) or 0)))}", timeout=35)
    if name == "tv_show_page":
        return c.request("POST", "/v1/page", {"url": str(args.get("url", ""))})
    if name == "tv_close_page":
        return c.request("DELETE", "/v1/page")
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


def serve_stdio(tv_client_factory, scope: str = "control") -> int:
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except ValueError:
            continue
        resp = handle_rpc(req, tv_client_factory(), scope)
        if resp is not None:
            sys.stdout.write(json.dumps(resp) + "\n")
            sys.stdout.flush()
    return 0


def create_http_server(tv_client_factory, token: str, host: str = "127.0.0.1", port: int = 8990,
                       tailnet_only: bool = False, scope: str = "control", require_access: bool = False,
                       verifier=None) -> ThreadingHTTPServer:
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
            resp = handle_rpc(req, tv_client_factory(), scope)
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
               access_team: str | None = None, access_aud: str | None = None) -> int:
    token = ensure_token()
    bind = "127.0.0.1"
    if tailscale:
        bind = tailnet_ip()
        if not bind:
            print("tailscale: no tailnet address on this PC (sudo tailscale up)", file=sys.stderr, flush=True)
            return 1
        tunnel, cloudflare = False, None   # one or the other: the tailnet never needs a public tunnel
    public = bool(tunnel or cloudflare)
    scope = default_scope(public, scope)
    verifier = None
    if cloudflare and (access_team or access_aud):
        from .cf_access import AccessVerifier
        try:
            import cryptography  # noqa: F401
        except ImportError:
            print("--access-team/--access-aud need python-cryptography (pacman -S python-cryptography)", file=sys.stderr)
            return 1
        verifier = AccessVerifier(access_team or "", access_aud or "")
    srv = create_http_server(tv_client_factory, token, bind, port, tailnet_only=tailscale, scope=scope,
                             require_access=bool(cloudflare and (access or verifier)), verifier=verifier)
    actual_port = srv.server_address[1]
    print(f"OmarchyTV MCP HTTP server listening on http://{bind}:{actual_port}/mcp"
          + (" (tailnet peers only)" if tailscale else "") + f", tools: {scope}", flush=True)
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
