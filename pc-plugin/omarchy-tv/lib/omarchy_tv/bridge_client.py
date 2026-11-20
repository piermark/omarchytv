"""v2.21 the same agent calls for LG / Samsung: talk to the local bridge (omarchy-tv webos-serve) instead of the TV.

`omarchy-tv mcp --bridge`, `omarchy-tv notify|page|ask` use this when the TV is an LG or a Samsung: the bridge keeps
the inbox the TV app polls (agent_inbox). Same paths and answers as an Android TV, so the MCP tools do not change.
"""

from __future__ import annotations

import http.client
import json

from . import webos


class BridgeError(Exception):
    def __init__(self, status: int, data: dict):
        super().__init__(data.get("message") or data.get("error") or f"HTTP {status}")
        self.status = status
        self.code = data.get("error", "")


class BridgeClient:
    SUPPORTED = ("/v1/notify", "/v1/page", "/v1/ask", "/v1/theme", "/v1/status")   # not /v1/ask/<id> POST: the TV answers

    def __init__(self, host: str = "127.0.0.1", port: int = 8765, token: str | None = None):
        self.host, self.port = host, port
        self.token = token if token is not None else webos.bridge_agent_token()   # the agents' key, not the TV's

    def status(self):
        """tv_status on LG / Samsung: the bridge's own status (host, theme, now playing)."""
        return self.request("GET", "/v1/status")

    def __getattr__(self, name: str):
        # v2.21 review: the Android-only calls (apps, launch, key, ...) answer "for Android TV", not AttributeError
        if name.startswith("_"):
            raise AttributeError(name)

        def android_only(*_a, **_k):
            raise BridgeError(404, {"error": "not_on_lg_samsung",
                                    "message": f"not_on_lg_samsung: {name} is for Android TV (Omarchy Link)"})
        return android_only

    def request(self, method: str, path: str, body=None, timeout: float = 8.0):
        if method == "DELETE" and path == "/v1/page":
            method, body = "POST", {"close": True}
        if path == "/v1/state":   # LG / Samsung: what the bridge knows (no foreground app on these TVs)
            st = self.request("GET", "/v1/status", timeout=timeout)
            return {"screen": "unknown", "app": None, "theme": st.get("theme"), "nowPlaying": None, "tv": "webos-or-tizen"}
        base = path.split("?", 1)[0]
        home = base.startswith("/v1/widgets/") or base == "/v1/widget-pages" or base.startswith("/v1/widget-pages/")   # v2.22
        if not (base in self.SUPPORTED or base.startswith("/v1/ask/") or home):
            raise BridgeError(404, {"error": "not_on_lg_samsung", "message": f"not_on_lg_samsung: {base} is for Android TV (Omarchy Link)"})
        c = http.client.HTTPConnection(self.host, self.port, timeout=timeout)
        try:
            data = json.dumps(body).encode() if body is not None else None
            c.request(method, path, body=data, headers={"X-Omarchy-Agent": self.token, "Content-Type": "text/plain"})
            r = c.getresponse()
            out = json.loads(r.read(1 << 20) or b"{}")
            if r.status >= 400:
                raise BridgeError(r.status, out if isinstance(out, dict) else {})
            return out
        finally:
            c.close()
