"""Send content from the Omarchy PC to the TV ("Share to TV", AirPlay-like).

Two routes, picked per item:
  cast   Google Cast, built into Sony / Android TVs, through `catt` (pip/AUR). Needs no launcher.
         YouTube opens the TV's YouTube app; media URLs play in the Cast player; pages use DashCast.
  link   the signed OmarchyTV Link channel (docs/PROTOCOL.md, Cast): POST /v1/cast and the launcher
         opens the right app (YouTube, its own player, an Omarchy-styled image viewer, a text card,
         or the browser). Local files are uploaded over the signed channel when small enough.

The PC opens no listening port unless the user opts in (--serve / "castServe": true, or --screen).
Then a temporary HTTP server binds to the LAN address facing the TV only, answers only the
TV's IP, serves one unguessable URL, and stops when playback ends, after idle, or at a hard deadline.
"""
from __future__ import annotations

import hashlib
import hmac
import http.server
import json
import mimetypes
import os
import queue
import re
import secrets
import shutil
import socket
import subprocess
import threading
import time
import urllib.parse
from pathlib import Path

from .. import config, netcheck

KINDS = ("url", "youtube", "video", "audio", "image", "text")
TEXT_MAX = 32 * 1024
TITLE_MAX = 200
UPLOAD_MAX_DEFAULT = 64 << 20
END_STATES = {"ended", "stopped", "error"}
YOUTUBE = re.compile(r"^https?://(?:www\.|m\.|music\.)?(?:youtube\.com/(?:watch\?(?:[^#]*&)?v=|shorts/|live/|embed/)"
                     r"|youtu\.be/)([A-Za-z0-9_-]{11})")
TEXT_EXT = {".txt", ".md", ".markdown", ".log", ".csv", ".json", ".srt"}
STREAM_TYPES = {".m3u8": "application/vnd.apple.mpegurl", ".mpd": "application/dash+xml", ".ts": "video/mp2t"}
for _ext, _type in {".mkv": "video/x-matroska", ".webm": "video/webm", ".flac": "audio/flac", ".opus": "audio/ogg",
                    ".m4a": "audio/mp4", ".webp": "image/webp", ".heic": "image/heic", ".avif": "image/avif",
                    ".md": "text/markdown", **STREAM_TYPES}.items():
    mimetypes.add_type(_type, _ext)


class CastError(Exception):
    def __init__(self, message: str, code: str = "cast_failed"):
        super().__init__(message)
        self.code = code


# ------------------------------------------------------------------ what is it?
def seconds(text) -> float | None:
    """"90", "90s", "1m30s", "1h2m3s", "1:30", "01:02:03" -> seconds."""
    if text is None or text == "":
        return None
    s = str(text).strip().lower()
    try:
        if re.fullmatch(r"\d+(\.\d+)?s?", s):
            return float(s.rstrip("s"))
        m = re.fullmatch(r"(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?", s)
        if m and any(m.groups()):
            h, mi, se = (int(x or 0) for x in m.groups())
            return float(h * 3600 + mi * 60 + se)
        if re.fullmatch(r"\d+(:\d{1,2}){1,2}(\.\d+)?", s):
            total = 0.0
            for part in s.split(":"):
                total = total * 60 + float(part)
            return total
    except ValueError:
        pass
    raise CastError(f"bad time {text!r} (use 90, 1m30s or 1:30)", "bad_request")


def _kind_for(mime: str | None) -> str | None:
    mime = mime or ""
    if mime in STREAM_TYPES.values() or mime.startswith("video/"):
        return "video"
    if mime.startswith("audio/"):
        return "audio"
    if mime.startswith("image/"):
        return "image"
    return None


def text_item(text: str, title: str | None = None) -> dict:
    data = text.encode("utf-8", "replace")[:TEXT_MAX].decode("utf-8", "ignore")
    if not data.strip():
        raise CastError("nothing to send: the text is empty", "bad_request")
    return {"kind": "text", "text": data, "title": (title or data.strip().splitlines()[0])[:TITLE_MAX]}


def classify(target: str, kind: str | None = None, title: str | None = None, position=None,
             allow_text: bool = False) -> dict:
    """A link, a local file, or (allow_text) plain text -> the item we send."""
    if kind and kind not in KINDS:
        raise CastError(f"unknown kind {kind!r}; one of: {', '.join(KINDS)}", "bad_request")
    if kind == "text":
        p = Path(target).expanduser()
        return text_item(p.read_text(errors="replace") if p.is_file() else target, title or (p.name if p.is_file() else None))
    s = (target or "").strip()
    if not s:
        raise CastError("nothing to send", "bad_request")
    if s.startswith("file://"):
        s = urllib.parse.unquote(urllib.parse.urlparse(s).path)
    pos = seconds(position)
    m = YOUTUBE.match(s)
    if m and kind in (None, "youtube"):
        q = urllib.parse.parse_qs(urllib.parse.urlparse(s).query)
        t = (q.get("t") or q.get("start") or [None])[0]
        item = {"kind": "youtube", "videoId": m.group(1), "url": s, "title": (title or "YouTube")[:TITLE_MAX]}
        p2 = pos if pos is not None else seconds(t)
        if p2:
            item["position"] = p2
        return item
    u = urllib.parse.urlparse(s)
    if u.scheme in ("http", "https"):
        if not u.netloc:
            raise CastError(f"{s}: not a valid link", "bad_target")
        ext = Path(u.path).suffix.lower()
        mime = STREAM_TYPES.get(ext) or mimetypes.guess_type(u.path)[0]
        k = kind or _kind_for(mime) or "url"
        item = {"kind": k, "url": s, "title": (title or urllib.parse.unquote(Path(u.path).name) or u.netloc)[:TITLE_MAX]}
        if k != "url" and mime:
            item["mime"] = mime
        if pos:
            item["position"] = pos
        return item
    p = Path(s).expanduser()
    if p.is_file():
        p = p.resolve()
        mime = mimetypes.guess_type(p.name)[0] or ""
        k = kind or _kind_for(mime) or ("text" if p.suffix.lower() in TEXT_EXT or mime.startswith("text/") else None)
        if k == "text":
            return text_item(p.read_bytes()[:TEXT_MAX].decode("utf-8", "replace"), title or p.name)
        if not k:
            raise CastError(f"{p.name}: not a video, audio, image or text file", "unsupported_kind")
        item = {"kind": k, "path": str(p), "mime": mime or "application/octet-stream", "size": p.stat().st_size,
                "title": (title or p.stem)[:TITLE_MAX]}
        if pos:
            item["position"] = pos
        return item
    if p.exists():
        raise CastError(f"{s}: not a regular file", "bad_target")
    if re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]*:", s) and not allow_text:
        raise CastError(f"only http(s) links and local files can be sent ({s.split(':', 1)[0]}: refused)", "bad_target")
    if allow_text:
        return text_item(s, title)
    raise CastError(f"{s}: no such file, and not an http(s) link", "bad_target")


def clipboard() -> str:
    # pbpaste: macOS, where neither of the others exists
    for argv in (["wl-paste", "--no-newline"], ["xclip", "-o", "-selection", "clipboard"], ["pbpaste"]):
        if shutil.which(argv[0]):
            r = subprocess.run(argv, capture_output=True, timeout=5)
            if r.returncode == 0:
                return r.stdout[:TEXT_MAX].decode("utf-8", "replace")
    raise CastError("cannot read the clipboard (install wl-clipboard)", "no_clipboard")


def from_clipboard(title: str | None = None) -> dict:
    text = clipboard().strip()
    first = text.splitlines()[0].strip() if text else ""
    if first and (first.startswith(("http://", "https://", "file://", "/", "~")) and len(text.splitlines()) == 1):
        return classify(first, title=title)
    return text_item(text, title)


# ------------------------------------------------------------------ route 1: Google Cast via catt
def catt() -> str | None:
    return shutil.which("catt")


DEVICES_FILE = "cast-devices.json"


def cast_devices(refresh: bool = False, timeout: float = 15) -> list[dict]:
    """[{name, address, model}] from `catt scan`, cached for 10 minutes."""
    cache = config.STATE_DIR / DEVICES_FILE
    if not refresh:
        c = config.read_json(cache, {})
        if isinstance(c, dict) and time.time() - c.get("at", 0) < 600 and isinstance(c.get("devices"), list):
            return c["devices"]
    if not catt():
        return []
    try:
        out = subprocess.run(["catt", "scan"], capture_output=True, text=True, timeout=timeout).stdout
    except (OSError, subprocess.SubprocessError):
        return []
    devs = []
    for line in out.splitlines():
        parts = [x.strip() for x in line.split(" - ")]
        if len(parts) >= 2 and re.fullmatch(r"[0-9a-fA-F:.]+", parts[0]):
            devs.append({"address": parts[0], "name": parts[1], "model": " - ".join(parts[2:])})
    config.STATE_DIR.mkdir(parents=True, exist_ok=True, mode=0o700)
    config.write_json_private(cache, {"at": int(time.time()), "devices": devs})
    return devs


def pick_device(to: str | None, cfg: dict, tv_name: str | None = None) -> str | None:
    """--to, then config castDevice; otherwise the one Cast device on the LAN, or the one named like
    the paired TV. None lets catt use its own default (`catt -d NAME set_default`)."""
    if to:
        return to
    if cfg.get("castDevice"):
        return str(cfg["castDevice"])
    devs = cast_devices()
    if len(devs) == 1:
        return devs[0]["name"]
    if tv_name and devs:
        words = {w for w in re.findall(r"[a-z0-9]{3,}", tv_name.lower())}
        hits = [d for d in devs if words & set(re.findall(r"[a-z0-9]{3,}", (d["name"] + " " + d["model"]).lower()))]
        if len(hits) == 1:
            return hits[0]["name"]
    return None


def catt_argv(item: dict, device: str | None) -> list[str]:
    base = ["catt"] + (["-d", device] if device else [])
    k = item["kind"]
    if k == "text":
        raise CastError("Google Cast cannot show text; use --via link (OmarchyTV launcher)", "unsupported_kind")
    if k == "url":
        return base + ["cast_site", item["url"]]
    target = item.get("path") or item["url"]
    seek = ["-t", str(int(item["position"]))] if item.get("position") else []
    return base + ["cast"] + seek + [target]


def via_cast(item: dict, device: str | None, serve_ok: bool, out=print) -> dict:
    if not catt():
        raise CastError("Google Cast needs catt: pipx install catt (or yay -S catt)", "no_catt")
    if item.get("path") and not serve_ok:
        raise CastError("casting a local file makes catt serve it from this PC (a port in 45000-47000, "
                        "while it plays). Allow it with --serve, or \"castServe\": true in config.json", "serve_needed")
    argv = catt_argv(item, device)
    if item.get("path"):
        out(f"Casting {item['title']} to {device or 'the default Cast device'}; catt serves the file "
            "until playback ends (Ctrl+C stops).")
        r = subprocess.run(argv, stdin=subprocess.DEVNULL)  # stays in front while serving
        if r.returncode != 0:
            raise CastError(f"catt failed ({r.returncode})", "cast_failed")
        return {"via": "cast", "device": device}
    r = subprocess.run(argv, stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=90)
    if r.returncode != 0:
        msg = (r.stderr or r.stdout or "").strip().splitlines()
        text = msg[-1] if msg else f"catt exited {r.returncode}"
        code = "no_device" if re.search(r"no (chromecast|devices?)|not found|could not find", text, re.I) else "cast_failed"
        raise CastError(f"Google Cast: {text[:200]}", code)
    return {"via": "cast", "device": device}


def cast_stop(device: str | None) -> bool:
    if not catt():
        return False
    return subprocess.run(["catt"] + (["-d", device] if device else []) + ["stop"],
                          capture_output=True, timeout=30).returncode == 0


# ------------------------------------------------------------------ opt-in temporary server
def local_ip_for(host: str) -> str:
    """The address of this PC's interface that routes to `host` (no packet is sent)."""
    fam = socket.AF_INET6 if ":" in host else socket.AF_INET
    with socket.socket(fam, socket.SOCK_DGRAM) as s:
        s.connect((host, 9))
        return s.getsockname()[0]


class FileServer:
    """Serves exactly one file, or one live stream, at one random URL, to one IP. Opt-in only."""

    def __init__(self, tv_host: str, name: str, mime: str, path: str | None = None, live_argv: list | None = None,
                 idle: float = 600, lifetime: float = 4 * 3600):
        bind = local_ip_for(tv_host)
        if not netcheck.is_lan_address(bind):
            raise CastError(f"refusing to serve on {bind}: not a LAN address", "not_lan")
        self.allow = tv_host.split("%", 1)[0]
        self.path, self.live_argv, self.mime = path, live_argv, mime
        self.token = secrets.token_urlsafe(24)
        self.route = f"/cast/{self.token}/{urllib.parse.quote(name)}"
        self.idle, self.deadline = idle, time.time() + lifetime
        self.last = time.time()
        self.active = 0
        self.sent = 0
        self.proc: subprocess.Popen | None = None
        self.closed = threading.Event()
        server = self

        class H(http.server.BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"
            server_version = "omarchy-link-cast"
            sys_version = ""

            def log_message(self, *a):
                pass

            def _deny(self, code):
                self.send_response(code)
                self.send_header("Content-Length", "0")
                self.send_header("Connection", "close")
                self.end_headers()
                self.close_connection = True

            def _check(self) -> bool:
                if self.client_address[0] != server.allow:
                    self._deny(403)
                    return False
                if not hmac.compare_digest(self.path.split("?", 1)[0].encode(), server.route.encode()):
                    self._deny(404)
                    return False
                return True

            def do_HEAD(self):
                self.do_GET(head=True)

            def do_GET(self, head=False):
                if not self._check():
                    return
                server.active += 1
                server.last = time.time()
                try:
                    if server.live_argv:
                        return self._live(head)
                    return self._file(head)
                except (BrokenPipeError, ConnectionResetError):
                    pass
                finally:
                    server.active -= 1
                    server.last = time.time()

            def _file(self, head):
                size = os.path.getsize(server.path)
                start, end = 0, size - 1
                rng = self.headers.get("Range", "")
                m = re.fullmatch(r"bytes=(\d*)-(\d*)", rng.strip())
                if m and (m.group(1) or m.group(2)):
                    if m.group(1):
                        start = int(m.group(1))
                        end = min(int(m.group(2)), size - 1) if m.group(2) else size - 1
                    else:
                        start = max(0, size - int(m.group(2)))
                    if start > end or start >= size:
                        self.send_response(416)
                        self.send_header("Content-Range", f"bytes */{size}")
                        self.send_header("Content-Length", "0")
                        self.end_headers()
                        return
                    self.send_response(206)
                    self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
                else:
                    self.send_response(200)
                self.send_header("Content-Type", server.mime)
                self.send_header("Accept-Ranges", "bytes")
                self.send_header("Content-Length", str(end - start + 1))
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                if head:
                    return
                with open(server.path, "rb") as f:
                    f.seek(start)
                    left = end - start + 1
                    while left > 0 and not server.closed.is_set():
                        chunk = f.read(min(1 << 16, left))
                        if not chunk:
                            break
                        self.wfile.write(chunk)
                        left -= len(chunk)
                        server.sent += len(chunk)
                        server.last = time.time()

            def _live(self, head):
                self.send_response(200)
                self.send_header("Content-Type", server.mime)
                self.send_header("Cache-Control", "no-store")
                self.send_header("Connection", "close")
                self.end_headers()
                self.close_connection = True
                if head:
                    return
                proc = server.start_live()
                while not server.closed.is_set():
                    chunk = proc.stdout.read1(1 << 16) if hasattr(proc.stdout, "read1") else proc.stdout.read(1 << 16)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    self.wfile.flush()
                    server.sent += len(chunk)
                    server.last = time.time()

        class S(http.server.ThreadingHTTPServer):
            daemon_threads = True
            allow_reuse_address = False
            address_family = socket.AF_INET6 if ":" in bind else socket.AF_INET

        self.httpd = S((bind, 0), H)
        port = self.httpd.server_address[1]
        host = f"[{bind}]" if ":" in bind else bind
        self.url = f"http://{host}:{port}{self.route}"
        threading.Thread(target=self.httpd.serve_forever, kwargs={"poll_interval": 0.2}, daemon=True).start()
        threading.Thread(target=self._watchdog, daemon=True).start()

    def start_live(self) -> subprocess.Popen:
        if self.proc is None or self.proc.poll() is not None:
            self.proc = subprocess.Popen(self.live_argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                         stderr=subprocess.DEVNULL, start_new_session=True)
        return self.proc

    def _watchdog(self):
        while not self.closed.wait(1):
            now = time.time()
            if now > self.deadline or (self.active == 0 and now - self.last > self.idle):
                self.close()

    def close(self):
        if self.closed.is_set():
            return
        self.closed.set()
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(3)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        threading.Thread(target=self.httpd.shutdown, daemon=True).start()
        self.httpd.server_close()


# ------------------------------------------------------------------ screen (experimental)
def screen_argv(cfg: dict) -> list[str]:
    """A command writing a low-latency MPEG-TS (H.264) stream of the screen to stdout."""
    cmd = cfg.get("screenCommand")
    if isinstance(cmd, list) and cmd and all(isinstance(x, str) for x in cmd):
        return cmd
    audio = bool(cfg.get("screenAudio", False)) or os.environ.get("OMARCHY_LINK_SCREEN_AUDIO") == "1"  # opt-in audio
    fps = str(max(10, min(60, int(cfg.get("screenFps", 30)))))
    # 2.13 extended display: capture one named output (e.g. a Hyprland headless monitor) instead of all screens
    output = os.environ.get("OMARCHY_LINK_SCREEN_OUTPUT") or cfg.get("screenOutput") or ""
    # 2.14 Omastorm on the TV: one window's rectangle only ("X,Y WxH", logical pixels, from omastorm.region()).
    reg = os.environ.get("OMARCHY_LINK_SCREEN_REGION", "")
    m = re.match(r"^(-?\d+),(-?\d+) (\d+)x(\d+)$", reg)
    if m:
        x, y, w, h = m.groups()
        if shutil.which("gpu-screen-recorder"):
            return ["gpu-screen-recorder", "-w", "region", "-region", f"{w}x{h}+{x}+{y}", "-f", fps, "-k", "h264",
                    "-c", "mpegts", "-o", "/dev/stdout"]
        if shutil.which("wf-recorder"):
            vaapi = os.path.exists("/dev/dri/renderD128") and cfg.get("screenHwEncode", True)
            enc = ["-c", "h264_vaapi", "-d", "/dev/dri/renderD128"] if vaapi else \
                ["-c", "libx264", "-p", "preset=ultrafast", "-p", "tune=zerolatency", "-x", "yuv420p"]
            return ["wf-recorder", "-y", "-r", fps, "-g", reg] + enc + ["-m", "mpegts", "-f", "/dev/stdout"]
    if shutil.which("gpu-screen-recorder"):  # hardware encoder (VAAPI/NVENC), lowest latency on Hyprland
        return ["gpu-screen-recorder", "-w", output or "screen", "-f", fps, "-k", "h264", "-c", "mpegts"] + \
            (["-a", "default_output"] if audio else []) + ["-o", "/dev/stdout"]
    if shutil.which("wf-recorder"):
        vaapi = os.path.exists("/dev/dri/renderD128") and cfg.get("screenHwEncode", True)
        enc = ["-c", "h264_vaapi", "-d", "/dev/dri/renderD128"] if vaapi else \
            ["-c", "libx264", "-p", "preset=ultrafast", "-p", "tune=zerolatency", "-x", "yuv420p"]
        return ["wf-recorder", "-y", "-r", fps] + (["-o", output] if output else []) + enc + (["--audio"] if audio else []) + ["-m", "mpegts", "-f", "/dev/stdout"]
    raise CastError("screen mirroring needs gpu-screen-recorder or wf-recorder (or \"screenCommand\" in config.json)",
                    "no_recorder")


def screen_item(cfg: dict) -> dict:
    return {"kind": "video", "mime": "video/mp2t", "live": True, "title": f"{socket.gethostname()} screen",
            "screen": screen_argv(cfg)}


# ------------------------------------------------------------------ route 2: OmarchyTV Link
def link_caps(status: dict) -> dict:
    caps = status.get("capabilities") or []
    return {"cast": "cast" in caps, "upload": "cast-upload" in caps,
            "kinds": [k for k in (status.get("castKinds") or KINDS) if k in KINDS],
            "uploadMax": int(status.get("castUploadMax") or UPLOAD_MAX_DEFAULT)}


WIRE = ("kind", "url", "videoId", "title", "mime", "position", "text", "live", "size", "subtitle")


def via_link(client, item: dict, serve_ok: bool = False, wait: bool = False, out=print,
             idle: float = 600, lifetime: float = 4 * 3600) -> dict:
    caps = link_caps(client.status())
    if not caps["cast"]:
        raise CastError("the TV launcher cannot receive casts yet (OmarchyTV without the \"cast\" capability)",
                        "capability_missing")
    if item["kind"] not in caps["kinds"]:
        raise CastError(f"the TV launcher cannot show {item['kind']} yet", "unsupported_kind")
    rid = secrets.token_hex(8)
    body = {k: item[k] for k in WIRE if item.get(k) not in (None, "")}
    body["id"] = rid
    body["from"] = socket.gethostname()[:40]
    server = None
    if item.get("screen"):
        server = FileServer(client.host, "screen.ts", item["mime"], live_argv=item["screen"], idle=60, lifetime=lifetime)
        body["url"] = server.url
    elif item.get("path"):
        size = item["size"]
        if caps["upload"] and size <= caps["uploadMax"] and not (serve_ok and size > (8 << 20)):
            data = Path(item["path"]).read_bytes()
            sha = hashlib.sha256(data).hexdigest()
            r = client.request("PUT", "/v1/cast/file", raw=data, content_type=item["mime"],
                               timeout=max(30.0, len(data) / (1 << 20) * 2))
            if r.get("file") != sha:
                raise CastError("the TV stored a different file", "bad_upload")
            body["file"] = sha
        elif serve_ok:
            server = FileServer(client.host, Path(item["path"]).name, item["mime"], path=item["path"],
                                idle=idle, lifetime=lifetime)
            body["url"] = server.url
        else:
            mb = lambda n: f"{n / (1 << 20):.0f} MiB"
            limit = f"up to {mb(caps['uploadMax'])}" if caps["upload"] else "no uploads"
            raise CastError(f"{Path(item['path']).name} is {mb(size)}; the TV accepts {limit}. Add --serve to stream it "
                            "from this PC (a temporary port, for the TV only, closed after playback)", "too_large")
    events = client.events() if (server or wait) else None
    q: "queue.Queue" = queue.Queue()
    if events:
        def reader():
            try:
                while True:
                    q.put(events.recv())
            except Exception:  # noqa: BLE001 - socket closed
                q.put(None)
        threading.Thread(target=reader, daemon=True).start()
    try:
        res = client.request("POST", "/v1/cast", body)
        result = {"via": "link", "id": rid, "app": res.get("app"), "state": res.get("state", "opening")}
        if server:
            out(f"Serving {item['title']} to the TV only, at a one-time address; stops when playback ends.")
        if not events:
            return result
        last_ping = time.time()
        while True:
            if server and server.closed.is_set():
                result["state"] = result.get("state") if result.get("state") in END_STATES else "timeout"
                return result
            try:
                msg = q.get(timeout=1)
            except queue.Empty:
                if time.time() - last_ping > 25:
                    events.send("ping", {})
                    last_ping = time.time()
                continue
            if msg is None:
                result["state"] = "disconnected"
                return result
            d = msg.get("data") or {}
            if msg.get("type") == "cast_state" and d.get("id") == rid:
                result["state"] = d.get("state")
                if d.get("error"):
                    result["error"] = str(d["error"])[:200]
                if wait:
                    out(f"TV: {d.get('state')}" + (f" at {d['position']:.0f}s" if isinstance(d.get("position"), (int, float)) else ""))
                if d.get("state") in END_STATES:
                    return result
    finally:
        if server:
            server.close()
        if events:
            events.close()


def link_stop(client, rid: str | None = None) -> dict:
    return client.request("POST", "/v1/cast/control", {"action": "stop", **({"id": rid} if rid else {})})


def link_control(client, action: str, position=None) -> dict:
    if action not in ("play", "pause", "stop", "seek"):
        raise CastError(f"unknown action {action}", "bad_request")
    body = {"action": action}
    if action == "seek":
        body["position"] = seconds(position) or 0
    return client.request("POST", "/v1/cast/control", body)


# ------------------------------------------------------------------ choose
def choose(item: dict, via: str, cfg: dict, serve_ok: bool) -> list[str]:
    """Routes to try, in order. Cast first when available (works with any Cast TV, no launcher);
    Link for what Cast cannot do (text, the screen) and as the fallback."""
    if via in ("cast", "link"):
        return [via]
    if item["kind"] == "text" or item.get("screen"):
        return ["link"]
    order = ["link", "cast"] if cfg.get("castPrefer") == "link" else ["cast", "link"]
    if not catt() or (item.get("path") and not serve_ok and "cast" in order):
        order = ["link"] + (["cast"] if catt() else [])
    return order
