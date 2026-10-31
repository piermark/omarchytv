"""LAN HTTP bridge for OmarchyTV on LG webOS (`omarchy-tv webos-serve`).

Since LG webOS TVs run a sandboxed Chromium web app without Android's mutual-TLS daemon or ADB,
this lightweight stdlib HTTP server exposes live Omarchy PC stats, active theme/palette, user themes,
AI credit balances, and desktop wallpaper over LAN HTTP with CORS enabled:
  GET /v1/status (or /status.json) -> JSON {host, kernel, cpu, ram, uptimeSec, theme, palette, ai, deploys, nowPlaying, hasWallpaper}
  GET /v1/themes                   -> JSON {"themes": [...]}
  GET /v1/wallpaper                -> active desktop wallpaper image (webp/png/jpg)
  GET /v1/grokbot                  -> JSON {available, bot, roster, dispatch, version}: @tcballard's Grok Bot
                                      Desktop Companion v0.1.0 as omarchy-link's bridge sees it (no secrets)
  POST /v1/grokbot/ask             -> {text, bot?} with header X-Omarchy-Token: the ask code printed when the
                                      bridge starts (~/.config/omarchy-tv/webos-bridge.token, mode 0600).
                                      Answers like omarchy-link's grokbot_answer {bot, text, mode, links, note?}.
  POST /v1/grokbot/sync            -> same token; runs his bin/grokbot-sync and returns the new roster.
"""
from __future__ import annotations

import json
import os
import platform
import shutil
import socket
import subprocess
import time
import hmac
import secrets
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer, ThreadingHTTPServer
from pathlib import Path

from . import themes, ui

OMARCHY_CURRENT = Path.home() / ".config/omarchy/current"
AI_CFG = Path.home() / ".config/omarchy-link/ai.json"
LINK_CFG = Path.home() / ".config/omarchy-link/config.json"

_last_cpu: tuple[int, int] | None = None

TOKEN_FILE = Path.home() / ".config/omarchy-tv/webos-bridge.token"
ASK_GAP_S = 3.0            # one question every 3 seconds, like the Android link
_ask_lock = threading.Lock()
_last_ask = 0.0


def bridge_token() -> str:
    """The ask code the TV must send (X-Omarchy-Token). Created once, 8 characters, file mode 0600."""
    try:
        t = TOKEN_FILE.read_text(encoding="utf-8").strip()
        if len(t) >= 8:
            return t
    except OSError:
        pass
    t = "".join(secrets.choice("ABCDEFGHJKLMNPQRSTUVWXYZ23456789") for _ in range(8))
    TOKEN_FILE.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(str(TOKEN_FILE), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write(t + "\n")
    return t


def _grokbot():
    try:
        from omarchy_link import grokbot   # noqa: PLC0415 (optional: omarchy-link may not be installed)
        return grokbot
    except Exception:
        return None


def link_cfg() -> dict:
    try:
        d = json.loads(LINK_CFG.read_text(encoding="utf-8"))
        return d if isinstance(d, dict) else {}
    except (OSError, ValueError):
        return {}


def build_grokbot_payload() -> dict:
    g = _grokbot()
    if not g:
        return {"available": False, "reason": "omarchy-link is not installed"}
    cfg = link_cfg()
    cur = g.current(cfg)
    if not cur:
        return {"available": False, "reason": "the Grok Bot Desktop Companion is not installed on this PC"}
    roster = []
    for b in g.roster(cfg)[:40]:
        c = g.color_name(b.get("avatarColor"))
        roster.append({"id": g.plain(b.get("id"), 80), "name": g.plain(b.get("name"), 40),
                       "shortName": g.plain(b.get("shortName") or b.get("name"), 24),
                       "avatarShape": g.avatar_shape(b.get("avatarShape")), "color": c, "fill": g.PALETTE[c]})
    return {"available": True, "bot": cur, "roster": roster, "dispatch": cur.get("dispatch"),
            "version": cur.get("version"), "source": g.UPSTREAM, "ref": g.UPSTREAM_REF}


def cpu_percent() -> int:
    global _last_cpu
    try:
        for _ in range(2 if _last_cpu is None else 1):
            f = [int(x) for x in Path("/proc/stat").read_text().split("\n", 1)[0].split()[1:]]
            idle, total = f[3] + (f[4] if len(f) > 4 else 0), sum(f)
            prev, _last_cpu = _last_cpu, (idle, total)
            if prev and total > prev[1]:
                return max(0, min(100, round(100 * (1 - (idle - prev[0]) / (total - prev[1])))))
            time.sleep(0.05)
    except (OSError, ValueError):
        pass
    return 0


def ram_percent() -> int:
    try:
        m = {l.split(":")[0]: int(l.split()[1]) for l in Path("/proc/meminfo").read_text().splitlines() if ":" in l}
        return max(0, min(100, round(100 * (1 - m["MemAvailable"] / m["MemTotal"]))))
    except (OSError, KeyError, ValueError, ZeroDivisionError, IndexError):
        return 0


def uptime_seconds() -> int:
    try:
        return int(float(Path("/proc/uptime").read_text().split()[0]))
    except (OSError, ValueError, IndexError):
        return 0


def current_theme_name() -> str:
    for p in (OMARCHY_CURRENT / "theme.name", OMARCHY_CURRENT / "theme"):
        try:
            if p.is_symlink():
                return Path(os.readlink(p)).name
            if p.is_file():
                return p.read_text().strip() or "tokyo-night"
        except OSError:
            pass
    return "tokyo-night"


def current_wallpaper_path() -> Path | None:
    bg = OMARCHY_CURRENT / "background"
    try:
        real = Path(os.path.realpath(bg))
        if real.is_file():
            return real
    except OSError:
        pass
    return None


def now_playing() -> dict | None:
    if not shutil.which("playerctl"):
        return None
    try:
        r = subprocess.run(["playerctl", "metadata", "--format", "{{status}}\t{{artist}}\t{{title}}"],
                           capture_output=True, text=True, timeout=2)
        parts = r.stdout.strip().split("\t")
        if r.returncode == 0 and len(parts) >= 3 and parts[0] == "Playing" and parts[2]:
            return {"artist": parts[1][:64], "title": parts[2][:96]}
    except (OSError, subprocess.SubprocessError):
        pass
    return None


def read_ai_credits() -> dict:
    try:
        if AI_CFG.is_file():
            data = json.loads(AI_CFG.read_text())
            if isinstance(data, dict):
                return data
    except (OSError, ValueError):
        pass
    return {"claude": "24.80", "openai": "51.20", "xai": "110.00"}


def ai_meter() -> list:
    """Rows from omarchy-link. A manual ai.json wins inside collect().

    With no file, the collector mirrors the Omarchy Agents bar
    (~/.local/state/omarchy/agents/usage/). Old TV builds ignore this list.
    """
    try:
        from omarchy_link.ai import collect
        data = collect()
        rows = data.get("ai") if isinstance(data, dict) else None
        return rows if isinstance(rows, list) else []
    except Exception:
        return []


def build_status_payload() -> dict:
    ai = read_ai_credits()
    wp = current_wallpaper_path()
    return {
        "version": "2.11.0-testing",
        "host": socket.gethostname(),
        "kernel": f"Linux {platform.release()}",
        "cpu": cpu_percent(),
        "ram": ram_percent(),
        "uptimeSec": uptime_seconds(),
        "theme": current_theme_name(),
        "palette": ui.colors(),
        "claude": str(ai.get("claude", "24.80")),
        "openai": str(ai.get("openai", "51.20")),
        "xai": str(ai.get("xai", "110.00")),
        "ai": ai_meter(),
        "nowPlaying": now_playing(),
        "hasWallpaper": wp is not None,
        "deploy": "omarchytv-webos 2.11.0-testing ✓",
    }


def build_themes_payload() -> dict:
    out = []
    for t in themes.user_themes(include_bundled=True):
        try:
            out.append({"name": themes.tv_name(t.name), "theme": themes.theme_json(t), "backgrounds": len(t.backgrounds)})
        except Exception:
            pass
    return {"themes": out}


class WebOSHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def _send_json(self, data: dict, status: int = 200):
        raw = json.dumps(data).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, X-Omarchy-Token")   # Chrome 38 has no "*"
        self.end_headers()
        self.wfile.write(raw)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, X-Omarchy-Token")   # Chrome 38 has no "*"
        self.end_headers()

    def _authorized(self) -> bool:
        got = (self.headers.get("X-Omarchy-Token") or "").strip().upper()
        return bool(got) and hmac.compare_digest(got.encode(), bridge_token().upper().encode())

    def do_POST(self):
        global _last_ask
        path = self.path.split("?", 1)[0]
        if path not in ("/v1/grokbot/ask", "/v1/grokbot/sync"):
            self._send_json({"error": "not found"}, 404)
            return
        if not self._authorized():
            self._send_json({"error": "unauthorized", "message": "wrong or missing ask code"}, 401)
            return
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            n = -1
        if n < 0 or n > 8192:
            self._send_json({"error": "bad_request"}, 400)
            return
        try:
            body = json.loads(self.rfile.read(n).decode("utf-8") or "{}") if n else {}
        except (ValueError, UnicodeDecodeError):
            body = None
        if not isinstance(body, dict):
            self._send_json({"error": "bad_request"}, 400)
            return
        g = _grokbot()
        if not g:
            self._send_json({"error": "not_installed", "message": "omarchy-link is not installed"}, 503)
            return
        with _ask_lock:
            now = time.monotonic()
            if now - _last_ask < ASK_GAP_S:
                self._send_json({"error": "busy", "message": "one question every 3 seconds"}, 429)
                return
            _last_ask = now
        cfg = link_cfg()
        try:
            if path == "/v1/grokbot/sync":
                res = g.sync_roster(cfg)
                self._send_json({**res, **build_grokbot_payload()})
            else:
                bot = body.get("bot")
                self._send_json(g.ask(cfg, body.get("text"), str(bot) if bot else None))
        except g.GrokbotError as e:
            self._send_json({"error": e.code, "message": str(e)[:200]}, 400 if e.code in ("empty", "unknown_bot") else 502)

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path in ("/v1/status", "/status.json", "/"):
            self._send_json(build_status_payload())
            return
        if path == "/v1/grokbot":
            self._send_json(build_grokbot_payload())
            return
        if path == "/v1/themes":
            self._send_json(build_themes_payload())
            return
        if path == "/v1/wallpaper":
            wp = current_wallpaper_path()
            if not wp:
                self._send_json({"error": "no wallpaper configured"}, 404)
                return
            data = wp.read_bytes()
            ext = wp.suffix.lower()
            ctype = "image/webp" if ext == ".webp" else ("image/png" if ext == ".png" else "image/jpeg")
            self.send_response(200)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(data)
            return
        self._send_json({"error": "not found"}, 404)


def serve(bind: str = "0.0.0.0", port: int = 8765, once: bool = False) -> int:
    srv = ThreadingHTTPServer((bind, port), WebOSHandler)   # an ask can take minutes; status keeps answering
    ui.say(f"Grok Bot ask code for the TV (Settings > Grok Bot companion > PC ask code): {bridge_token()}")
    ui.say(f"OmarchyTV webOS bridge attivo su http://{bind}:{port}/v1/status (CTRL+C per fermare)")
    try:
        if once:
            srv.handle_request()
        else:
            srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        srv.server_close()
    return 0
