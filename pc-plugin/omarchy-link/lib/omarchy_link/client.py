"""PC-side client for the OmarchyTV Link API (docs/PROTOCOL.md)."""

from __future__ import annotations

import base64
import hashlib
import hmac
import http.client
import json
import os
import socket
import threading
import time

from . import PROTOCOL, crypto, netcheck, ws

USER_AGENT = "omarchy-link/0.1"
JSON_MAX = 1 << 20


class LinkError(Exception):
    def __init__(self, message: str, status: int = 0, code: str = ""):
        super().__init__(message)
        self.status, self.code = status, code


def _conn(host: str, port: int, timeout: float) -> http.client.HTTPConnection:
    return http.client.HTTPConnection(host, port, timeout=timeout)


def probe(host: str, port: int, timeout: float = 3.0) -> dict:
    """Unauthenticated GET /v1/info (manual-IP fallback)."""
    c = _conn(host, port, timeout)
    try:
        c.request("GET", "/v1/info", headers={"User-Agent": USER_AGENT})
        r = c.getresponse()
        body = r.read(65536)
        if r.status != 200:
            raise LinkError(f"{host}:{port} answered {r.status}", r.status)
        info = json.loads(body)
        if info.get("service") != "omarchytv-link":
            raise LinkError(f"{host}:{port} is not an OmarchyTV launcher")
        return info
    except (OSError, ValueError) as e:
        raise LinkError(f"cannot reach {host}:{port}: {e}") from e
    finally:
        c.close()


def _post_plain(host: str, port: int, path: str, payload: dict, timeout: float = 10.0, _retry: bool = True) -> dict:
    body = json.dumps(payload).encode()
    c = _conn(host, port, timeout)
    try:
        c.request("POST", path, body=body, headers={"Content-Type": "application/json", "User-Agent": USER_AGENT})
        r = c.getresponse()
        data = r.read(65536)
        try:
            obj = json.loads(data or b"{}")
        except ValueError:
            obj = {}
        if r.status == 429 and _retry:
            # pair/* is 1 request per 2 s per peer (§3): start and confirm share it, so a fast PC waits once.
            try:
                wait = min(max(int(r.getheader("Retry-After", "2")), 1), 5)
            except ValueError:
                wait = 2
            time.sleep(wait)
            return _post_plain(host, port, path, payload, timeout, False)
        if r.status != 200:
            raise LinkError(obj.get("message") or f"HTTP {r.status}", r.status, obj.get("error", ""))
        return obj
    except OSError as e:
        raise LinkError(f"cannot reach {host}:{port}: {e}") from e
    finally:
        c.close()


def pair(host: str, port: int, code: str, pc_id: str, pc_name: str) -> dict:
    """SPAKE2 pairing. Returns the TV record to store (includes the token)."""
    a = crypto.PcPairing(code, pc_id)
    start = _post_plain(host, port, "/v1/pair/start", {
        "protocol": PROTOCOL, "pcId": pc_id, "pcName": pc_name, "t": crypto.b64u(a.message()),
    })
    tv_id = str(start.get("tvId", ""))
    if not tv_id:
        raise LinkError("TV did not send its id")
    try:
        keys = a.finish(tv_id, crypto.unb64u(start["s"]))
    except (KeyError, ValueError) as e:
        raise LinkError(f"bad pairing reply: {e}") from e
    done = _post_plain(host, port, "/v1/pair/confirm", {
        "pairingId": start.get("pairingId", ""), "confirm": crypto.b64u(keys.confirm_pc),
    })
    if not hmac.compare_digest(crypto.unb64u(done.get("confirm", "")), keys.confirm_tv):
        raise LinkError("the TV failed key confirmation (wrong code or not the real TV)", 0, "tv_confirm")
    return {
        "id": tv_id, "name": start.get("tvName") or "OmarchyTV", "address": host, "port": port,
        "token": base64.b64encode(keys.token).decode(), "pairedAt": int(time.time()),
    }


class Client:
    """Authenticated client for one paired TV."""

    def __init__(self, tv: dict, pc_id: str, strict_subnet: bool = True):
        self.tv = tv
        self.pc_id = pc_id
        self.host, self.port = tv["address"], int(tv.get("port", 47800))
        self.token = base64.b64decode(tv["token"])
        self.skew = 0
        # None = not known yet; set from /v1/status capabilities. OMARCHY_LINK_ENCRYPT=0 forces plaintext.
        self.encrypt = None if os.environ.get("OMARCHY_LINK_ENCRYPT", "1") != "0" else False
        netcheck.check_host(self.host, strict_subnet)

    # ------------------------------------------------------------- REST
    def request(self, method: str, path: str, payload=None, raw: bytes | None = None,
                content_type: str = "application/json", timeout: float = 8.0, _retry: bool = True):
        body = raw if raw is not None else (json.dumps(payload).encode() if payload is not None else b"")
        ts = int(time.time()) + self.skew
        enc = bool(self.encrypt) and crypto.HAVE_AES
        nonce = crypto.b64u(os.urandom(16))
        if enc and body:   # the HMAC signs the ciphertext; the AAD binds it to this request's nonce
            body = crypto.seal(self.token, "OTV1-E\n" + nonce, body)
        auth, nonce = crypto.sign_request(self.token, self.pc_id, method, path, body, ts, nonce)
        headers = {"Authorization": auth, "User-Agent": USER_AGENT}
        if enc:
            headers[crypto.ENC_HEADER] = crypto.ENC_ALG
        if body:
            headers["Content-Type"] = content_type
        c = _conn(self.host, self.port, timeout)
        try:
            c.request(method, path, body=body or None, headers=headers)
            r = c.getresponse()
            data = r.read(JSON_MAX)
            sig = r.getheader("X-OTV-Sig", "")
            server_time = r.getheader("X-OTV-Time", "")
            resp_enc = r.getheader(crypto.ENC_HEADER, "")
        except (OSError, http.client.HTTPException) as e:   # refused, reset, or a garbled/half-open reply
            raise LinkError(f"TV unreachable: {e}") from e
        finally:
            c.close()
        sealed = data
        if resp_enc == crypto.ENC_ALG and data:
            if not enc:
                raise LinkError("unexpected encrypted response", r.status, "bad_enc")
            try:
                data = crypto.unseal(self.token, "OTV1-E-R\n" + nonce, data)
            except Exception as e:
                raise LinkError("response decryption failed", r.status, "bad_enc") from e
        try:
            obj = json.loads(data) if data else {}
        except ValueError:
            obj = {}
        if (self.encrypt is None and method == "GET" and path == "/v1/status" and r.status == 200
                and sig and hmac.compare_digest(sig, crypto.sign_response(self.token, nonce, r.status, sealed))):
            self.encrypt = crypto.ENC_CAP in (obj.get("capabilities") or [])
        if r.status == 401 and obj.get("error") == "clock_skew" and server_time.isdigit() and _retry:
            self.skew = int(server_time) - int(time.time())
            return self.request(method, path, payload, raw, content_type, timeout, False)
        if r.status in (200, 204) or obj.get("error") not in ("unauthorized", "clock_skew", "replay"):
            # every answer to a signed request must be signed by the TV, errors included
            expected = crypto.sign_response(self.token, nonce, r.status, sealed)
            if not sig or not hmac.compare_digest(sig, expected):
                raise LinkError("response not signed by the paired TV", r.status, "bad_response_sig")
        if r.status not in (200, 204):
            raise LinkError(obj.get("message") or f"HTTP {r.status}", r.status, obj.get("error", ""))
        return obj

    def status(self):
        return self.request("GET", "/v1/status")

    def apps(self):
        return self.request("GET", "/v1/apps").get("apps", [])

    def key(self, key: str):
        return self.request("POST", "/v1/remote/key", {"key": key})

    def launch(self, package: str):
        return self.request("POST", "/v1/apps/launch", {"package": package})

    def put_theme(self, theme: dict):
        return self.request("PUT", "/v1/theme", theme)

    def wallpaper_info(self):
        return self.request("GET", "/v1/wallpaper")

    def put_wallpaper(self, jpeg: bytes):
        return self.request("PUT", "/v1/wallpaper", raw=jpeg, content_type="image/jpeg", timeout=60)

    def put_widgets(self, widgets: dict):
        return self.request("PUT", "/v1/widgets", widgets)

    def put_stats(self, stats: dict):
        return self.request("PUT", "/v1/stats", stats)

    def put_screensaver(self, text: str):
        return self.request("PUT", "/v1/screensaver", {"text": text})

    def put_grokbot(self, bot: dict | None):
        """Agent Desktop's (the Grok Bot Desktop Companion) current bot for the TV tile (None = not installed)."""
        return self.request("PUT", "/v1/plugins/grokbot", {"bot": bot, "from": "pc"})

    def put_ai(self, ai: dict):
        return self.request("PUT", "/v1/ai", ai)

    def put_ai_providers(self, providers: dict):
        return self.request("PUT", "/v1/ai/providers", providers)

    def unpair(self):
        return self.request("POST", "/v1/unpair", {})

    # -------------------------------------------------------- WebSocket
    def events(self, timeout: float = 8.0) -> "EventStream":
        path = "/v1/events"
        ts = int(time.time()) + self.skew
        auth, nonce = crypto.sign_request(self.token, self.pc_id, "GET", path, b"", ts)
        sock = socket.create_connection((self.host, self.port), timeout=timeout)
        key = ws.new_key()
        req = (
            f"GET {path} HTTP/1.1\r\nHost: {self.host}:{self.port}\r\nUpgrade: websocket\r\n"
            f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n"
            f"Sec-WebSocket-Protocol: omarchytv-link.v1\r\nAuthorization: {auth}\r\n"
            f"User-Agent: {USER_AGENT}\r\n\r\n"
        )
        sock.sendall(req.encode())
        buf = b""
        while b"\r\n\r\n" not in buf:
            chunk = sock.recv(4096)
            if not chunk:
                sock.close()
                raise LinkError("TV closed the event socket during the handshake")
            buf += chunk
            if len(buf) > 16384:
                sock.close()
                raise LinkError("handshake too large")
        head, _, rest = buf.partition(b"\r\n\r\n")
        lines = head.decode("latin-1").split("\r\n")
        status = int(lines[0].split()[1])
        headers = {k.strip().lower(): v.strip() for k, _, v in (l.partition(":") for l in lines[1:])}
        if status != 101:
            sock.close()
            raise LinkError(f"event socket refused ({status})", status)
        if headers.get("sec-websocket-accept") != ws.accept_key(key):
            sock.close()
            raise LinkError("bad websocket accept")
        server_nonce = headers.get("x-otv-nonce", "")
        expected = crypto.sign_response(self.token, nonce, 101, server_nonce.encode())
        if not server_nonce or not hmac.compare_digest(headers.get("x-otv-sig", ""), expected):
            sock.close()
            raise LinkError("event socket not signed by the paired TV", 101, "bad_response_sig")
        sock.settimeout(None)
        return EventStream(ws.Conn(sock, mask=True, leftover=rest),
                           crypto.ws_session_key(self.token, nonce, server_nonce))


class EventStream:
    """Sealed messages both ways; seq must increase by exactly one per direction."""

    def __init__(self, conn: ws.Conn, key: bytes):
        self.conn, self.key = conn, key
        self.recv_seq = 0
        self.send_seq = 0
        self.lock = threading.Lock()

    def recv(self) -> dict:
        raw = crypto.open_message(self.key, self.conn.recv())
        msg = json.loads(raw)
        if msg.get("seq") != self.recv_seq + 1:
            raise LinkError("event out of sequence (replay?)")
        self.recv_seq += 1
        return msg

    def send(self, msg_type: str, data: dict | None = None) -> None:
        with self.lock:
            self.send_seq += 1
            payload = json.dumps({"seq": self.send_seq, "type": msg_type, "data": data or {}},
                                 separators=(",", ":")).encode()
            self.conn.send_text(crypto.seal_message(self.key, payload))

    def close(self) -> None:
        self.conn.close()
