#!/usr/bin/env python3
"""Mock OmarchyTV launcher: the TV side of docs/PROTOCOL.md, for tests and demos.

    python3 mock/mock_tv.py --data /tmp/mocktv --code 123456 [--mdns] [--simulate]

It keeps the same split the real launcher must keep: secrets (paired PCs and
their tokens) in a private "internal" file, everything displayable in
omarchytv.json. Stdlib only.
"""

from __future__ import annotations

import argparse
import base64
import queue
import re
import hashlib
import hmac
import ipaddress
import json
import os
import secrets
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lib"))
from omarchy_link import crypto, mdns, ws  # noqa: E402

JSON_MAX = 64 * 1024
JPEG_MAX = 8 * 1024 * 1024
SKEW = 120
KEYS = {"home", "back", "up", "down", "left", "right", "select", "play_pause", "play", "pause", "stop",
        "next", "previous", "rewind", "fast_forward", "volume_up", "volume_down", "mute"}
CAPS = ["theme", "wallpaper", "widgets", "ai", "apps", "launch",
        "key:home", "key:back", "key:play_pause", "key:play", "key:pause", "key:stop", "key:next",
        "key:previous", "key:rewind", "key:fast_forward", "key:volume_up", "key:volume_down", "key:mute",
        "events"]


class MockTV:
    def __init__(self, data_dir: Path, code: str = "", name: str = "Mock BRAVIA", host: str = "127.0.0.1",
                 port: int = 0, pairing_open: bool = True, cast: bool = True, cast_upload_max: int = 64 << 20,
                 cast_kinds=("url", "youtube", "video", "audio", "image", "text"), cast_play: bool = True,
                 pair_limit: bool = False):
        self.data_dir = Path(data_dir)
        (self.data_dir / "internal").mkdir(parents=True, exist_ok=True, mode=0o700)
        (self.data_dir / "files" / "link").mkdir(parents=True, exist_ok=True)
        self.secrets_file = self.data_dir / "internal" / "link.json"   # = Context.getFilesDir() on Android
        self.config_file = self.data_dir / "files" / "omarchytv.json"  # = getExternalFilesDir()
        sec = self._read(self.secrets_file, {})
        self.tv_id = sec.get("tvId") or secrets.token_hex(16)
        self.pcs: dict = sec.get("pcs", {})
        self.name = name
        self.code = code or f"{secrets.randbelow(10**6):06d}"
        self.pairing_open = pairing_open
        self.pair_limit = pair_limit        # pair/* 1 request per 2 s per peer (§3), as the launcher enforces
        self.pair_last: dict = {}
        self.pending: dict = {}
        self.failures = 0
        self.lock = threading.RLock()
        self.nonces: dict = {}
        self.received: "queue.Queue" = queue.Queue()
        self.seen: list = []                # every PC -> TV event, never consumed
        self.clients: list = []
        self.log: list = []
        self.wallpaper_sha = None
        # cast: what a launcher with the "cast" capability does (docs/PROTOCOL.md, Cast)
        self.cast_enabled, self.cast_upload_max, self.cast_kinds = cast, cast_upload_max, list(cast_kinds)
        self.cast_play = cast_play          # simulate the player fetching the URL, then "ended"
        self.casts: list = []
        self.cast_controls: list = []
        self.cast_fetched: dict = {}        # id -> bytes the "player" got from the PC
        self.cast_live_bytes = 4096         # a live stream is read this far, then "stopped"
        self.state = {"foreground": {"package": "com.omarchytv.home", "label": "OmarchyTV"},
                      "homeVisible": True, "screensaver": False, "screenOn": True}
        self.apps = [
            {"package": "com.netflix.ninja", "label": "Netflix", "dock": True},
            {"package": "com.amazon.amazonvideo.livingroom", "label": "Prime Video", "dock": True},
            {"package": "com.google.android.youtube.tv", "label": "YouTube", "dock": True},
            {"package": "com.disney.disneyplus", "label": "Disney+", "dock": True},
            {"package": "com.sony.dtv.tvx", "label": "TV", "dock": False},
        ]
        self._save_secrets()
        handler = type("H", (Handler,), {"tv": self})
        self.httpd = ThreadingHTTPServer((host, port), handler)
        self.httpd.daemon_threads = True
        self.host, self.port = self.httpd.server_address[:2]
        self.responder = None

    # -------------------------------------------------------- storage
    @staticmethod
    def _read(path: Path, default):
        try:
            return json.loads(path.read_text())
        except (OSError, ValueError):
            return default

    def _save_secrets(self):
        tmp = self.secrets_file.with_suffix(".tmp")
        tmp.write_text(json.dumps({"tvId": self.tv_id, "pcs": self.pcs}, indent=2))
        os.chmod(tmp, 0o600)
        os.replace(tmp, self.secrets_file)

    def config(self) -> dict:
        return self._read(self.config_file, {})

    def update_config(self, fn):
        """Read-modify-write keeping unknown keys, like LauncherConfig.update()."""
        with self.lock:
            j = self.config()
            j.setdefault("version", 1)
            fn(j)
            tmp = self.config_file.with_suffix(".tmp")
            tmp.write_text(json.dumps(j, indent=2))
            os.replace(tmp, self.config_file)

    def _public_pcs(self, j):
        j.setdefault("link", {})["pcs"] = [
            {"name": p["name"], "address": p.get("address", ""), "lastSeen": p.get("lastSeen", 0)}
            for p in self.pcs.values()]

    # -------------------------------------------------------- lifecycle
    def start(self, mdns_responder: bool = False, mdns_bind=("0.0.0.0", 5353), multicast=True) -> "MockTV":
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        if mdns_responder:
            self.responder = mdns.Responder(
                self.name, self.port,
                {"v": "1", "id": self.tv_id, "name": self.name, "pair": "1" if self.pairing_open else "0"},
                "127.0.0.1" if self.host in ("127.0.0.1", "0.0.0.0") else self.host,
                bind=mdns_bind, multicast=multicast).start()
        return self

    def close(self):
        for c in list(self.clients):
            try:
                c[0].close()
            except OSError:
                pass
        if self.responder:
            self.responder.close()
        self.httpd.shutdown()
        self.httpd.server_close()

    # -------------------------------------------------------- events
    def broadcast(self, msg_type: str, data: dict):
        for entry in list(self.clients):
            conn, key, seq_box, lock = entry
            with lock:
                seq_box[0] += 1
                payload = json.dumps({"seq": seq_box[0], "type": msg_type, "data": data},
                                     separators=(",", ":")).encode()
                try:
                    conn.send_text(crypto.seal_message(key, payload))
                except OSError:
                    pass

    def ask(self, text: str, provider: str | None = None, session: str | None = None,
            lang: str | None = None, timeout: float = 30) -> dict:
        """What the TV's "> ask" screen does: send an "ask" event, gather the answer.
        -> {"chunks":[...], "final": {...}} or {"error": {...}}"""
        rid = secrets.token_hex(8)
        data = {"id": rid, "text": text}
        for k, v in (("provider", provider), ("session", session), ("lang", lang)):
            if v:
                data[k] = v
        self.broadcast("ask", data)
        chunks, deadline = [], time.time() + timeout
        while time.time() < deadline:
            try:
                msg = self.received.get(timeout=max(0.05, deadline - time.time()))
            except queue.Empty:
                break
            d = msg.get("data") or {}
            if d.get("id") != rid:
                continue
            if msg.get("type") == "answer_error":
                return {"chunks": chunks, "error": d}
            if msg.get("type") == "answer":
                if d.get("done"):
                    return {"chunks": chunks, "final": d}
                chunks.append(d.get("text", ""))
        return {"chunks": chunks, "error": {"error": "timeout"}}

    def wait_event(self, kind: str, pred=lambda d: True, timeout: float = 15, after: int = 0) -> dict | None:
        """First PC event of `kind` (from index `after` of seen) whose data matches pred."""
        end = time.time() + timeout
        while time.time() < end:
            for m in list(self.seen[after:]):
                if m.get("type") == kind and pred(m.get("data") or {}):
                    return m["data"]
            time.sleep(0.05)
        return None

    def agent_ask(self, agent_id: str, text: str, timeout: float = 20) -> list:
        """What the TV's agent widget does: send "agent_ask", collect replies until answer/error."""
        rid = secrets.token_hex(8)
        start = len(self.seen)
        self.broadcast("agent_ask", {"id": rid, "agentId": agent_id, "text": text})
        end, out = time.time() + timeout, []
        while time.time() < end:
            out = [m for m in self.seen[start:] if (m.get("data") or {}).get("id") == rid]
            if any(m["type"] in ("agent_answer", "agent_ask_error") for m in out):
                break
            time.sleep(0.05)
        return [(m["type"], m["data"]) for m in out]

    def grokbot_ask(self, text: str, bot: str | None = None, timeout: float = 20) -> dict:
        """What the TV's Grok Bot tile does: send "grokbot_ask", wait for grokbot_answer/grokbot_error."""
        rid = secrets.token_hex(8)
        start = len(self.seen)
        self.broadcast("grokbot_ask", {"id": rid, "text": text, **({"bot": bot} if bot else {})})
        end = time.time() + timeout
        while time.time() < end:
            for m in list(self.seen[start:]):
                if (m.get("data") or {}).get("id") == rid and m.get("type") in ("grokbot_answer", "grokbot_error"):
                    return m
            time.sleep(0.05)
        return {"type": "timeout"}

    def set_ai_sources(self, enabled: list, timeout: float = 15) -> dict:
        """What the TV's AI-pane switches do: send "ai_sources", wait for the PC's reply."""
        rid = secrets.token_hex(8)
        self.broadcast("ai_sources", {"id": rid, "enabled": enabled})
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                msg = self.received.get(timeout=max(0.05, deadline - time.time()))
            except queue.Empty:
                break
            if (msg.get("data") or {}).get("id") == rid and msg.get("type") in ("ai_sources_ok", "ai_sources_error"):
                return msg
        return {"type": "timeout"}

    def status(self) -> dict:
        return {**self.state, "tvId": self.tv_id, "name": self.name, "launcherVersion": "mock",
                "protocol": 1, "capabilities": CAPS + (["ai-ask"] if self.config().get("link", {}).get("aiProviders") else [])
                + (["cast", "cast-upload"] if self.cast_enabled else []) + ["agents"],
                "time": int(time.time()),
                **({"castKinds": self.cast_kinds, "castUploadMax": self.cast_upload_max} if self.cast_enabled else {})}

    def cast_state(self, rid: str, state: str, **extra):
        self.broadcast("cast_state", {"id": rid, "state": state, **extra})

    def _play(self, c: dict):
        """The launcher's player: fetch the media like MediaPlayer would (Range first), report states."""
        import urllib.request
        rid = c["id"]
        time.sleep(0.2)
        self.cast_state(rid, "playing", position=c.get("position") or 0)
        if c.get("url") and c["kind"] in ("video", "audio", "image") and c["url"].startswith("http://"):
            try:
                if c.get("live"):
                    with urllib.request.urlopen(c["url"], timeout=10) as r:
                        got = b""
                        while len(got) < self.cast_live_bytes:
                            chunk = r.read(1024)
                            if not chunk:
                                break
                            got += chunk
                    self.cast_fetched[rid] = got
                    return self.cast_state(rid, "stopped")
                req = urllib.request.Request(c["url"], headers={"Range": "bytes=0-"})
                with urllib.request.urlopen(req, timeout=10) as r:
                    self.cast_fetched[rid] = r.read()
            except OSError as e:
                return self.cast_state(rid, "error", error=f"cannot open media: {e}")
        self.cast_state(rid, "ended", position=1.0, duration=1.0)

    def set_state(self, **kw):
        self.state.update(kw)
        self.broadcast("status", self.status())

    def open_app(self, package: str):
        app = next((a for a in self.apps if a["package"] == package), None)
        if not app:
            return False
        self.set_state(foreground={"package": package, "label": app["label"]}, homeVisible=False, screensaver=False)
        return True


class Handler(BaseHTTPRequestHandler):
    tv: MockTV = None
    protocol_version = "HTTP/1.1"
    server_version = "OmarchyTV-mock"

    def log_message(self, fmt, *args):
        pass

    # ---------------------------------------------------------- helpers
    def _lan_only(self) -> bool:
        ip = ipaddress.ip_address(self.client_address[0])
        if ip.is_loopback or ip.is_private or ip.is_link_local:
            return True
        self._json(403, {"error": "not_lan", "message": "LAN clients only"})
        return False

    def _json(self, status: int, obj, sign: tuple | None = None, extra: dict | None = None):
        body = json.dumps(obj).encode() if obj is not None else b""
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-OTV-Time", str(int(time.time())))
        if sign:
            token, nonce = sign
            self.send_header("X-OTV-Sig", crypto.sign_response(token, nonce, status, body))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def _body(self, limit: int) -> bytes | None:
        n = int(self.headers.get("Content-Length") or 0)
        if n > limit:
            self._json(413, {"error": "too_large"})
            self.close_connection = True
            return None
        return self.rfile.read(n) if n else b""

    def _auth(self, body: bytes):
        """Returns (pc_id, token, nonce) or None after answering 401."""
        tv = self.tv
        try:
            f = crypto.parse_authorization(self.headers.get("Authorization", ""))
        except ValueError:
            self._json(401, {"error": "unauthorized", "message": "missing or bad Authorization"})
            return None
        pc = tv.pcs.get(f["pc"])
        if not pc:
            self._json(401, {"error": "unauthorized", "message": "unknown PC"})
            return None
        if not f["ts"].lstrip("-").isdigit() or abs(int(f["ts"]) - time.time()) > SKEW:
            self._json(401, {"error": "clock_skew", "message": "timestamp outside ±120 s"})
            return None
        token = base64.b64decode(pc["token"])
        expected = crypto.hmac256(token, crypto.request_string(self.command, self.path, f["ts"], f["nonce"], body))
        try:
            ok = hmac.compare_digest(crypto.unb64u(f["sig"]), expected)
        except ValueError:
            ok = False
        if not ok:
            self._json(401, {"error": "unauthorized", "message": "bad signature"})
            return None
        with tv.lock:
            now = time.time()
            for k in [k for k, t in tv.nonces.items() if now - t > 2 * SKEW]:
                del tv.nonces[k]
            if (f["pc"], f["nonce"]) in tv.nonces:
                self._json(401, {"error": "replay", "message": "nonce already used"})
                return None
            tv.nonces[(f["pc"], f["nonce"])] = now
            pc["lastSeen"] = int(now)
            pc["address"] = self.client_address[0]
        return f["pc"], token, f["nonce"]

    # ---------------------------------------------------------- routing
    def do_GET(self):
        self._route("GET")

    def do_POST(self):
        self._route("POST")

    def do_PUT(self):
        self._route("PUT")

    def _route(self, method: str):
        if not self._lan_only():
            return
        tv, path = self.tv, self.path
        if method == "GET" and path == "/v1/info":
            return self._json(200, {"service": "omarchytv-link", "protocol": 1, "tvId": tv.tv_id,
                                    "name": tv.name, "pairingOpen": tv.pairing_open})
        if method == "POST" and path in ("/v1/pair/start", "/v1/pair/confirm"):
            body = self._body(JSON_MAX)
            if body is not None and tv.pair_limit:
                now, peer = time.time(), self.client_address[0]
                with tv.lock:
                    wait = 2.0 - (now - tv.pair_last.get(peer, 0.0))
                    if wait <= 0:
                        tv.pair_last[peer] = now
                if wait > 0:
                    return self._json(429, {"error": "rate_limited", "message": "1 pairing request per 2 s"},
                                      extra={"Retry-After": str(int(wait + 0.999))})
            return None if body is None else self._pair(path, body)
        if method == "GET" and path == "/v1/events" and self.headers.get("Upgrade", "").lower() == "websocket":
            return self._websocket()
        limit = JPEG_MAX if (method, path) == ("PUT", "/v1/wallpaper") else JSON_MAX
        if (method, path) == ("PUT", "/v1/cast/file"):
            limit = self.tv.cast_upload_max
        body = self._body(limit)
        if body is None:
            return
        who = self._auth(body)
        if not who:
            return
        pc_id, token, nonce = who
        sign = (token, nonce)
        try:
            payload = json.loads(body) if body and path not in ("/v1/wallpaper", "/v1/cast/file") else {}
        except ValueError:
            return self._json(400, {"error": "bad_json"}, sign)
        tv.log.append((method, path, payload if path not in ("/v1/wallpaper", "/v1/cast/file") else len(body)))
        handler = {
            ("GET", "/v1/status"): self._status, ("GET", "/v1/apps"): self._apps,
            ("POST", "/v1/apps/launch"): self._launch, ("POST", "/v1/remote/key"): self._key,
            ("PUT", "/v1/theme"): self._theme, ("GET", "/v1/wallpaper"): self._wallpaper_get,
            ("PUT", "/v1/wallpaper"): self._wallpaper_put, ("PUT", "/v1/widgets"): self._widgets,
            ("PUT", "/v1/ai"): self._ai, ("PUT", "/v1/ai/providers"): self._ai_providers, ("POST", "/v1/unpair"): self._unpair,
            ("PUT", "/v1/agents"): self._agents_put, ("GET", "/v1/agents"): self._agents_get,
            ("POST", "/v1/cast"): self._cast, ("PUT", "/v1/cast/file"): self._cast_file,
            ("POST", "/v1/cast/control"): self._cast_control,
            ("PUT", "/v1/plugins/grokbot"): self._grokbot_put,
        }.get((method, path))
        if not handler:
            return self._json(404, {"error": "not_found"}, sign)
        status, obj = handler(pc_id, payload, body)
        self._json(status, obj, sign)

    # ---------------------------------------------------------- pairing
    def _pair(self, path, body):
        tv = self.tv
        if not tv.pairing_open:
            return self._json(403, {"error": "pairing_closed", "message": "open “Connect Omarchy Linux” on the TV"})
        try:
            req = json.loads(body)
        except ValueError:
            return self._json(400, {"error": "bad_json"})
        with tv.lock:
            if path == "/v1/pair/start":
                pc_id = str(req.get("pcId", ""))
                if not (8 <= len(pc_id) <= 64) or not pc_id.isalnum():
                    return self._json(400, {"error": "bad_request", "message": "pcId"})
                try:
                    b = crypto.TvPairing(tv.code, tv.tv_id, pc_id, crypto.unb64u(req.get("t", "")))
                except ValueError as e:
                    return self._json(400, {"error": "bad_request", "message": str(e)})
                pid = secrets.token_hex(8)
                tv.pending = {pid: {"b": b, "pcId": pc_id, "pcName": str(req.get("pcName", "omarchy"))[:40],
                                    "expires": time.time() + 60}}
                return self._json(200, {"pairingId": pid, "tvId": tv.tv_id, "tvName": tv.name,
                                        "s": crypto.b64u(b.message()), "expiresIn": 60})
            p = tv.pending.pop(str(req.get("pairingId", "")), None)
            if not p or p["expires"] < time.time():
                return self._json(400, {"error": "no_pairing", "message": "start again"})
            try:
                ok = hmac.compare_digest(crypto.unb64u(req.get("confirm", "")), p["b"].keys.confirm_pc)
            except ValueError:
                ok = False
            if not ok:
                tv.failures += 1
                left = max(0, 5 - tv.failures)
                if left == 0:
                    tv.code = f"{secrets.randbelow(10**6):06d}"
                    tv.failures = 0
                return self._json(401, {"error": "bad_code", "message": "wrong code", "attemptsLeft": left})
            tv.failures = 0
            tv.pcs[p["pcId"]] = {"name": p["pcName"], "token": base64.b64encode(p["b"].keys.token).decode(),
                                 "pairedAt": int(time.time()), "address": self.client_address[0]}
            tv._save_secrets()
            tv.code = f"{secrets.randbelow(10**6):06d}"   # a code is good for one pairing
            tv.update_config(tv._public_pcs)
            return self._json(200, {"confirm": crypto.b64u(p["b"].keys.confirm_tv), "tvId": tv.tv_id})

    # ---------------------------------------------------------- handlers
    def _status(self, pc, payload, body):
        return 200, self.tv.status()

    def _apps(self, pc, payload, body):
        return 200, {"apps": self.tv.apps}

    def _launch(self, pc, payload, body):
        return (200, {"ok": True}) if self.tv.open_app(str(payload.get("package", ""))) else \
            (404, {"error": "no_such_app"})

    def _key(self, pc, payload, body):
        key = payload.get("key")
        if key not in KEYS:
            return 400, {"error": "bad_key"}
        if "key:" + key not in CAPS:
            return 409, {"error": "capability_missing", "message": f"TV cannot send {key}"}
        if key == "home":
            self.tv.set_state(foreground={"package": "com.omarchytv.home", "label": "OmarchyTV"},
                              homeVisible=True, screensaver=False)
        return 200, {"ok": True}

    def _theme(self, pc, t, body):
        import re
        hexre = re.compile(r"^#[0-9a-fA-F]{6}$")
        if not all(hexre.match(str(t.get(k, ""))) for k in ("accent", "accent2")):
            return 400, {"error": "bad_theme", "message": "accent/accent2 must be #rrggbb"}
        colors = {k: v for k, v in (t.get("colors") or {}).items() if hexre.match(str(v))}
        wp = (t.get("wallpaper") or {}).get("sha256")
        if wp and wp != self.tv.wallpaper_sha:
            return 409, {"error": "unknown_wallpaper", "message": "PUT /v1/wallpaper first"}

        def edit(j):
            j["theme"] = {"name": str(t.get("name", ""))[:40], "mode": t.get("mode", "dark"),
                          "accent": t["accent"], "accent2": t["accent2"], "colors": colors, "source": "omarchy-link"}
            if wp:
                j["wallpaper"] = str(self.tv.data_dir / "files" / "link" / f"wallpaper-{wp[:12]}.jpg")
            pin = t.get("prompt") if isinstance(t.get("prompt"), dict) else {}
            part = lambda v: re.sub(r"[^a-z0-9._-]", "", str(v or "").lower())[:16]   # = LinkServer.promptPart
            pr = {k: part(pin.get(k)) for k in ("user", "host") if part(pin.get(k))}
            if pr:
                j["prompt"] = {**(j.get("prompt") or {}), **pr}
        self.tv.update_config(edit)
        return 200, {"ok": True}

    def _wallpaper_get(self, pc, payload, body):
        return 200, {"sha256": self.tv.wallpaper_sha}

    def _wallpaper_put(self, pc, payload, body):
        if self.headers.get("Content-Type") != "image/jpeg" or body[:3] != b"\xff\xd8\xff":
            return 415, {"error": "not_jpeg"}
        sha = hashlib.sha256(body).hexdigest()
        d = self.tv.data_dir / "files" / "link"
        for old in d.glob("wallpaper-*.jpg"):
            old.unlink()
        (d / f"wallpaper-{sha[:12]}.jpg").write_bytes(body)
        self.tv.wallpaper_sha = sha
        return 200, {"sha256": sha}

    def _widgets(self, pc, w, body):
        if len(w.get("panes", [])) > 8 or len(w.get("shortcuts", [])) > 16:
            return 400, {"error": "too_many"}
        self.tv.update_config(lambda j: j.setdefault("link", {}).update(
            panes=w.get("panes", []), shortcuts=w.get("shortcuts", []), widgetsAt=int(time.time())))
        return 200, {"ok": True}

    def _ai(self, pc, a, body):
        items = a.get("ai")
        if not isinstance(items, list) or len(items) > 16:
            return 400, {"error": "bad_ai"}
        # canonical place: top-level "ai" (+ "aiAt"), which LauncherConfig already reads
        srcs = a.get("aiSources")
        if srcs is not None and (not isinstance(srcs, list) or len(srcs) > 16):
            return 400, {"error": "bad_ai"}
        def upd(j):
            j["ai"], j["aiAt"] = items, int(time.time())
            if srcs is not None:
                j["aiSources"] = srcs
            j.get("link", {}).pop("ai", None)
        self.tv.update_config(upd)
        return 200, {"ok": True}

    def _ai_providers(self, pc, a, body):
        provs = a.get("providers")
        if not isinstance(provs, list) or len(provs) > 16:
            return 400, {"error": "bad_providers"}
        clean = []
        for p in provs:
            if not isinstance(p, dict) or not re.match(r"^[a-z0-9][a-z0-9._-]{0,31}$", str(p.get("id", ""))):
                return 400, {"error": "bad_providers", "message": "bad id"}
            clean.append({"id": p["id"], "name": str(p.get("name") or p["id"])[:32],
                          **{k: p[k] for k in ("kind", "model", "streaming", "provider") if k in p}})
        self.tv.update_config(lambda j: j.setdefault("link", {}).update(
            aiProviders=clean, aiDefault=a.get("default"), aiProvidersFrom=pc))
        return 200, {"ok": True}

    # ---------------------------------------------------------- agents
    def _agents_put(self, pc, a, body):
        if not isinstance(a.get("agents"), list) or len(a["agents"]) > 8:
            return 400, {"error": "bad_agents"}
        self.tv.update_config(lambda j: j.setdefault("link", {}).update(agents=a, agentsAt=int(time.time())))
        return 200, {"ok": True}

    def _agents_get(self, pc, a, body):
        return 200, self.tv.config().get("link", {}).get("agents") or {"agents": [], "at": None}

    # ---------------------------------------------------------- Grok Bot companion mirror (experimental)
    def _grokbot_put(self, pc, g, body):
        bot = g.get("bot")
        if bot is not None and (not isinstance(bot, dict) or not str(bot.get("name", "")).strip()):
            return 400, {"error": "bad_bot"}
        self.tv.update_config(lambda j: j.setdefault("plugins", {}).update(grokbotBot=bot))
        return 200, {"ok": True}

    # ---------------------------------------------------------- cast
    def _cast_file(self, pc, payload, body):
        if not self.tv.cast_enabled:
            return 409, {"error": "capability_missing"}
        if not body:
            return 400, {"error": "bad_cast", "message": "empty file"}
        sha = hashlib.sha256(body).hexdigest()
        d = self.tv.data_dir / "internal" / "cast"     # = getCacheDir()/cast on Android, never shared storage
        d.mkdir(parents=True, exist_ok=True)
        (d / sha).write_bytes(body)
        return 200, {"file": sha, "size": len(body)}

    def _cast(self, pc, c, body):
        tv = self.tv
        if not tv.cast_enabled:
            return 409, {"error": "capability_missing"}
        kind, rid = c.get("kind"), str(c.get("id", ""))
        if not (1 <= len(rid) <= 64) or kind not in tv.cast_kinds:
            return 400 if kind in ("url", "youtube", "video", "audio", "image", "text") or not rid else 415, \
                {"error": "bad_cast" if not rid else "unsupported_kind", "message": str(kind)}
        url = c.get("url")
        if url is not None and (not isinstance(url, str) or not re.match(r"^https?://", url) or len(url) > 4096):
            return 400, {"error": "bad_cast", "message": "url must be http(s)"}
        if kind == "youtube" and not re.fullmatch(r"[A-Za-z0-9_-]{11}", str(c.get("videoId", ""))):
            return 400, {"error": "bad_cast", "message": "videoId"}
        if kind == "text" and not (isinstance(c.get("text"), str) and 0 < len(c["text"].encode()) <= 32 * 1024):
            return 400, {"error": "bad_cast", "message": "text"}
        if c.get("file") and not (tv.data_dir / "internal" / "cast" / str(c["file"])).is_file():
            return 404, {"error": "no_such_file"}
        if kind in ("video", "audio", "image") and not (url or c.get("file")):
            return 400, {"error": "bad_cast", "message": "url or file"}
        tv.casts.append(c)
        app = {"youtube": "youtube", "video": "player", "audio": "player", "image": "viewer",
               "text": "text", "url": "browser"}[kind]
        if tv.cast_play:
            threading.Thread(target=tv._play, args=(c,), daemon=True).start()
        return 200, {"id": rid, "state": "opening", "app": app}

    def _cast_control(self, pc, c, body):
        if not self.tv.cast_enabled:
            return 409, {"error": "capability_missing"}
        if c.get("action") not in ("play", "pause", "stop", "seek"):
            return 400, {"error": "bad_action"}
        self.tv.cast_controls.append(c)
        if c["action"] == "stop" and self.tv.casts:
            self.tv.cast_state(c.get("id") or self.tv.casts[-1]["id"], "stopped")
        return 200, {"ok": True}

    def _unpair(self, pc, payload, body):
        with self.tv.lock:
            self.tv.pcs.pop(pc, None)
            self.tv._save_secrets()
            self.tv.update_config(self.tv._public_pcs)
        return 200, {"ok": True}

    # ---------------------------------------------------------- websocket
    def _websocket(self):
        who = self._auth(b"")
        if not who:
            return
        pc_id, token, nonce = who
        server_nonce = crypto.b64u(secrets.token_bytes(16))
        self.send_response(101)
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", ws.accept_key(self.headers.get("Sec-WebSocket-Key", "")))
        self.send_header("Sec-WebSocket-Protocol", "omarchytv-link.v1")
        self.send_header("X-OTV-Nonce", server_nonce)
        self.send_header("X-OTV-Sig", crypto.sign_response(token, nonce, 101, server_nonce.encode()))
        self.end_headers()
        self.wfile.flush()
        conn = ws.Conn(self.connection, mask=False)
        key = crypto.ws_session_key(token, nonce, server_nonce)
        entry = (conn, key, [0], threading.Lock())
        self.tv.clients.append(entry)
        # hello only to this client
        with entry[3]:
            entry[2][0] += 1
            conn.send_text(crypto.seal_message(key, json.dumps(
                {"seq": 1, "type": "hello", "data": self.tv.status()}, separators=(",", ":")).encode()))
        recv_seq = 0
        try:
            while True:
                raw = crypto.open_message(key, conn.recv())
                msg = json.loads(raw)
                if msg.get("seq") != recv_seq + 1:
                    break
                recv_seq += 1
                self.tv.seen.append(msg)
                self.tv.received.put(msg)
        except (ws.Closed, OSError, ValueError):
            pass
        finally:
            if entry in self.tv.clients:
                self.tv.clients.remove(entry)
            conn.close()
            self.close_connection = True


def _ask_loop(tv):
    session = None
    for line in sys.stdin:
        provider, _, text = line.strip().rpartition("> ")  # "codex> question" picks a provider
        if not text:
            continue
        r = tv.ask(text, provider=provider or None, session=session, timeout=120)
        if "error" in r:
            print(f"[{r['error'].get('error')}] {r['error'].get('message', '')}", flush=True)
            continue
        session = r["final"].get("session")
        print(r["final"].get("final", ""), f"  ({r['final'].get('provider')})", flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="/tmp/omarchytv-mock")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=47800)
    ap.add_argument("--code", default="")
    ap.add_argument("--name", default="Mock BRAVIA KD-55XE9005")
    ap.add_argument("--mdns", action="store_true", help="answer _omarchytv._tcp on 224.0.0.251:5353")
    ap.add_argument("--simulate", action="store_true", help="cycle apps and the screensaver every 20 s")
    ap.add_argument("--ask", action="store_true", help="read questions from stdin and send them like '> ask'")
    a = ap.parse_args()
    tv = MockTV(Path(a.data), a.code, a.name, a.host, a.port).start(mdns_responder=a.mdns)
    print(f"Mock OmarchyTV on {tv.host}:{tv.port}  code {tv.code[:3]} {tv.code[3:]}  id {tv.tv_id}", flush=True)
    if a.ask:
        threading.Thread(target=_ask_loop, args=(tv,), daemon=True).start()
    try:
        i = 0
        while True:
            time.sleep(20)
            if a.simulate:
                i += 1
                if i % 3 == 0:
                    tv.set_state(screensaver=True, homeVisible=False)
                else:
                    tv.open_app(tv.apps[i % 4]["package"])
    except KeyboardInterrupt:
        tv.close()


if __name__ == "__main__":
    main()
