"""LAN HTTP bridge for OmarchyTV on LG webOS (`omarchy-tv webos-serve`).

Since LG webOS TVs run a sandboxed Chromium web app without Android's mutual-TLS daemon or ADB,
this lightweight stdlib HTTP server exposes live Omarchy PC stats, active theme/palette, user themes,
AI credit balances, and desktop wallpaper over LAN HTTP with CORS enabled:
  GET /v1/status (or /status.json) -> JSON {host, kernel, cpu, ram, uptimeSec, theme, palette, ai, deploys, nowPlaying, hasWallpaper}
  GET /v1/themes                   -> JSON {"themes": [...]}
  GET /v1/wallpaper                -> active desktop wallpaper image (webp/png/jpg)
  GET /v1/grokbot                  -> JSON {available, bot, roster, dispatch, version}: @tcballard's Grok Bot
                                      Desktop (Agent Desktop) v0.2.0 as omarchy-link's bridge sees it (no secrets)
  POST /v1/grokbot/ask             -> {text, bot?} with header X-Omarchy-Token: the ask code printed when the
                                      bridge starts (~/.config/omarchy-tv/webos-bridge.token, mode 0600).
                                      Answers like omarchy-link's grokbot_answer {bot, text, mode, links, note?}.
  POST /v1/grokbot/sync            -> same token; runs his bin/grokbot-sync and returns the new roster.
  POST /v1/ai/providers            -> same token; {providers:[{id,name,kind,model}], default}: the AI agents signed
                                      in on the PC (Claude, Codex, Grok, Grok Bot, Hermes, Gemini, ollama, APIs),
                                      exactly the list Android's Grok Bot TUI gets over omarchy-link.
  POST /v1/ai/ask                  -> same token; {text, provider?, session?, lang?} -> {text, provider, model, session}
  POST /v1/theme/set               -> same token; {name}: reverse theme sync, the TV picked a theme and the PC follows
                                      (omarchy-theme-set, installed themes only, themeFromTv in omarchy-link's config)
                                      -> {result: ok|same|unknown|busy|failed}
  POST /v1/update/tv               -> same token; {action: check|install, platform: webos|tizen, version, want?}:
                                      "Update via PC" (tvupdate.py): newest official release for that TV, checked,
                                      installed with ares-install / your Samsung profile + tizen install
                                      (updateFromTv in omarchy-link's config) -> {current, latest} | {result}
  POST /v1/power                   -> same token; {state: standby|on}: TV and PC power together (omarchy-link
                                      power.py: suspend after powerGraceS, cancelled when the TV comes back)
  POST /v1/living                  -> same token; {notes, github, x: off|notify|api}: the Living screensaver asks
                                      while it runs (every few seconds) -> {github, notes, x, xMode, xError?}
                                      (omarchy-link living.py; notifications only when asked, never stored)
  GET /v1/media/list?path=<root/sub>  -> same token as a header; Cinemarchy browses the PC's own folders (XDG
                                   Videos, Music, Pictures, Download, ~/Movies; omarchy-link media.py), read-only;
                                   each file carries a signed "url" (and "subUrl" for a film.srt/.vtt next to it).
  GET /v1/media/f/<sig>/<path>     -> the file, with Range (seek), for the TV's own player; sig = HMAC of the path
                                   with the ask code, so only URLs from an authorised listing work.
  GET /v1/media/s/<sig>/<path>     -> the subtitle as WebVTT (an .srt converted on the fly).
  GET /v1/status also carries "liftoff": a running/just finished build or update (omarchy-link liftoff) or null.
"""
from __future__ import annotations

import json
import re
import urllib.parse
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


def _link_theme():
    try:
        from omarchy_link import theme   # noqa: PLC0415 (optional: omarchy-link may not be installed)
        return theme
    except Exception:
        return None


def _link_mod(name: str):
    try:
        return __import__(f"omarchy_link.{name}", fromlist=[name])   # optional: omarchy-link may not be installed
    except Exception:
        return None


def _liftoff_public():
    lo = _link_mod("liftoff")
    try:
        return lo.public(lo.read_state()) if lo else None
    except Exception:
        return None


class _NoTracker:
    """Without omarchy-link the update still runs; there is just no countdown to show."""

    def __init__(self, *a):
        pass

    def stage(self, *a, **k):
        pass

    def done(self, *a, **k):
        pass


_living_obj = None
_living_lock = threading.Lock()


def _living_collector():
    """omarchy-link's Living feed collector for this bridge process (lazy: nothing runs until a TV asks)."""
    global _living_obj
    mod = _link_mod("living")
    if not mod:
        return None
    with _living_lock:
        if _living_obj is None:
            _living_obj = mod.Living(link_cfg, lambda m: ui.say(m))
        return _living_obj


def _tracker(kind: str, name: str):
    lo = _link_mod("liftoff")
    return lo.Tracker(kind, name, 90) if lo else _NoTracker()


def _grokbot():
    try:
        from omarchy_link import grokbot   # noqa: PLC0415 (optional: omarchy-link may not be installed)
        return grokbot
    except Exception:
        return None


_assistant_obj = None
_assistant_lock = threading.Lock()


def _assistant():
    """omarchy-link's Assistant (the same agent runner Android's TUI uses), or None if omarchy-link is missing."""
    global _assistant_obj
    with _assistant_lock:
        if _assistant_obj is None:
            try:
                from omarchy_link import assistant   # noqa: PLC0415 (optional)
                _assistant_obj = (assistant, assistant.Assistant())
            except Exception:
                return None
        return _assistant_obj


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
        return {"available": False, "reason": "Agent Desktop (Grok Bot Desktop Companion) is not installed on this PC"}
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
        "liftoff": _liftoff_public(),
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
        if path not in ("/v1/grokbot/ask", "/v1/grokbot/sync", "/v1/ai/providers", "/v1/ai/ask", "/v1/theme/set",
                        "/v1/update/tv", "/v1/power", "/v1/living"):
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
        if path.startswith("/v1/ai/"):
            self._ai(path, body)
            return
        if path == "/v1/theme/set":
            self._theme_set(body)
            return
        if path == "/v1/update/tv":
            self._update_tv(body)
            return
        if path == "/v1/power":
            self._power(body)
            return
        if path == "/v1/living":
            self._living(body)
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

    def _theme_set(self, body: dict) -> None:
        th = _link_theme()
        if not th:
            self._send_json({"error": "not_installed", "message": "omarchy-link is not installed"}, 503)
            return
        if link_cfg().get("themeFromTv", True) is False:
            self._send_json({"error": "disabled", "message": "themeFromTv is off on the PC"}, 403)
            return
        res = th.set_theme(body.get("name"))
        code = {"ok": 200, "same": 200, "unknown": 404, "busy": 429}.get(res, 502)
        self._send_json({"result": res}, code)

    def _update_tv(self, body: dict) -> None:
        from . import tvupdate   # noqa: PLC0415
        cfg = link_cfg()
        if cfg.get("updateFromTv", True) is False:
            self._send_json({"error": "disabled", "message": "updateFromTv is off on the PC"}, 403)
            return
        plat, cur = str(body.get("platform", "")), str(body.get("version", ""))[:32]
        try:
            if body.get("action") == "install":
                res = tvupdate.start(plat, cur, str(body.get("want", ""))[:32], self.client_address[0], cfg, _tracker)
                self._send_json({"result": res}, 409 if res == "busy" else 200)
            else:
                self._send_json(tvupdate.check(plat, cur))
        except tvupdate.UpdateError as e:
            bad = e.code in ("bad_platform", "bad_request", "changed")
            self._send_json({"error": e.code, "message": str(e)[:200]}, 400 if bad else 502)

    def _living(self, body: dict) -> None:
        lv = _living_collector()
        if not lv:
            self._send_json({"error": "not_installed", "message": "omarchy-link is not installed"}, 503)
            return
        if link_cfg().get("livingToTv", True) is False:
            self._send_json({"error": "disabled", "message": "livingToTv is off on the PC"}, 403)
            return
        lv.set_prefs(body)
        lv.touch()
        self._send_json(lv.payload())

    def _power(self, body: dict) -> None:
        pw = _link_mod("power")
        if not pw:
            self._send_json({"error": "not_installed", "message": "omarchy-link is not installed"}, 503)
            return
        self._send_json({"result": pw.tv_power(body.get("state"), link_cfg(), lambda m: ui.say(m))})

    def _ai(self, path: str, body: dict) -> None:
        a = _assistant()
        if not a:
            self._send_json({"error": "not_installed", "message": "omarchy-link is not installed"}, 503)
            return
        mod, asst = a
        if link_cfg().get("assistant", True) is False:
            self._send_json({"error": "disabled", "message": "the assistant is off on the PC"}, 403)
            return
        try:
            if path == "/v1/ai/providers":
                self._send_json(asst.listing())
                return
            prov = body.get("provider")
            res = asst.ask(str(body.get("text") or "")[:2000], provider=str(prov) if prov else None,
                           conversation=str(body["session"]) if body.get("session") else None,
                           lang=str(body["lang"]) if body.get("lang") else None)
            self._send_json({"text": res["text"], "provider": res["provider"], "model": res.get("model"),
                             "session": res["conversationId"]})
        except ValueError as e:
            self._send_json({"error": "bad_request", "message": str(e)[:160]}, 400)
        except mod.NoProvider as e:
            self._send_json({"error": "no_provider", "message": str(e)[:200]}, 404)
        except mod.RateLimited as e:
            self._send_json({"error": "busy", "message": str(e)[:200]}, 429)
        except Exception as e:   # ProviderError, timeouts: the TV shows the message
            self._send_json({"error": "provider_error", "message": str(e)[:200]}, 502)

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
        if path == "/v1/media/list":
            self._media_list()
            return
        if path.startswith("/v1/media/f/") or path.startswith("/v1/media/s/"):
            self._media_file(path)
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


    # ---- Cinemarchy: the PC's media folders (read-only) ----
    def _media_list(self) -> None:
        m = _link_mod("media")
        if not m:
            self._send_json({"error": "not_installed", "message": "omarchy-link is not installed"}, 503)
            return
        if not self._authorized():
            self._send_json({"error": "unauthorized"}, 401)
            return
        q = urllib.parse.parse_qs(self.path.split("?", 1)[1] if "?" in self.path else "")
        res = m.listing((q.get("path") or [""])[0])
        key = bridge_token()
        for f in res.get("files", []):
            f["url"] = f"/v1/media/f/{m.sign(key, f['path'])}/{urllib.parse.quote(f['path'])}"
            if f.get("sub"):
                f["subUrl"] = f"/v1/media/s/{m.sign(key, f['sub'])}/{urllib.parse.quote(f['sub'])}"
        self._send_json(res)

    def _media_file(self, path: str) -> None:
        m = _link_mod("media")
        parts = path.split("/", 5)   # ["", "v1", "media", "f|s", sig, rel]
        if not m or len(parts) < 6:
            self._send_json({"error": "not found"}, 404)
            return
        sub, sig, rel = parts[3] == "s", parts[4], urllib.parse.unquote(parts[5])
        if not m.verify(bridge_token(), rel, sig):
            self._send_json({"error": "forbidden"}, 403)
            return
        p = m.resolve(rel)
        if p is None or not p.is_file():
            self._send_json({"error": "not found"}, 404)
            return
        if sub:
            text = p.read_text(encoding="utf-8", errors="replace")[:2_000_000]
            raw = (m.srt_to_vtt(text) if p.suffix.lower() == ".srt" else text).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/vtt; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(raw)
            return
        size = p.stat().st_size
        start, end = 0, size - 1
        rng = re.match(r"bytes=(\d*)-(\d*)$", self.headers.get("Range") or "")
        if rng and (rng.group(1) or rng.group(2)):
            if rng.group(1):
                start = int(rng.group(1))
                end = min(size - 1, int(rng.group(2))) if rng.group(2) else size - 1
            else:
                start = max(0, size - int(rng.group(2)))
            if start > end or start >= size:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.end_headers()
                return
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        else:
            self.send_response(200)
        self.send_header("Content-Type", m.mime_of(p.name))
        self.send_header("Content-Length", str(end - start + 1))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Access-Control-Allow-Origin", "*")   # lets Cinemarchy's ASCII mode read the frames
        self.end_headers()
        if self.command == "HEAD":
            return
        try:
            with p.open("rb") as fh:
                fh.seek(start)
                left = end - start + 1
                while left > 0:
                    chunk = fh.read(min(256 * 1024, left))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    left -= len(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_HEAD(self):
        path = self.path.split("?", 1)[0]
        if path.startswith("/v1/media/f/"):
            self._media_file(path)
        else:
            self.send_response(404)
            self.end_headers()


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
