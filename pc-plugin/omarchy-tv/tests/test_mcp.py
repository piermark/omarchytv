import http.client
import json
import tempfile
import threading
import unittest
from pathlib import Path

from omarchy_tv import mcp_server


class FakeTvClient:
    def __init__(self):
        self.theme = ("tokyo-night", 0)
        self.keys = []

    def status(self):
        return {"ok": True, "theme": self.theme[0], "capabilities": ["themes", "mcp"]}

    def key(self, k):
        self.keys.append(k)
        return {"ok": True, "key": k}

    def apps(self):
        return {"apps": [{"package": "com.netflix.ninja", "label": "Netflix"}]}

    def launch(self, pkg):
        return {"ok": True, "package": pkg}

    def request(self, method, path, body=None, timeout=8.0):
        if method == "GET" and path == "/v1/themes":
            return {"themes": [{"name": "jade-horse", "backgrounds": ["01-ink-horse.webp"], "bytes": 1000}], "used": 1000, "cap": 100000}
        if method == "POST" and path.startswith("/v1/themes/") and path.endswith("/select"):
            name = path.split("/")[3]
            self.theme = (name, (body or {}).get("bg", 0))
            return {"ok": True, "name": name}
        if method == "GET" and path == "/v1/state":
            return {"screen": "home", "app": None}
        if method == "POST" and path == "/v1/ask":
            self.asked = body
            return {"id": "0123456789abcdef", "state": "pending"}
        if method == "GET" and path.startswith("/v1/ask/0123456789abcdef"):
            return {"id": "0123456789abcdef", "state": "answered", "answer": "Yes"}
        if method == "POST" and path == "/v1/notify":
            self.notes = getattr(self, "notes", []) + [body]
            return {"ok": True, "shown": True}
        if method == "PUT" and path == "/v1/screensaver":
            return {"ok": True, "text": (body or {}).get("text", "")}
        return {"ok": True}


class TestMcpServer(unittest.TestCase):
    def test_rpc_initialize_list_and_call(self):
        tv = FakeTvClient()
        init = mcp_server.handle_rpc({"jsonrpc": "2.0", "id": 1, "method": "initialize"}, tv)
        self.assertEqual(init["result"]["serverInfo"]["name"], "omarchy-tv-mcp")

        lst = mcp_server.handle_rpc({"jsonrpc": "2.0", "id": 2, "method": "tools/list"}, tv)
        names = [t["name"] for t in lst["result"]["tools"]]
        self.assertIn("tv_status", names)
        self.assertIn("tv_set_theme", names)
        self.assertNotIn("exec", names)

        call = mcp_server.handle_rpc({
            "jsonrpc": "2.0", "id": 3, "method": "tools/call",
            "params": {"name": "tv_set_theme", "arguments": {"name": "jade-horse"}}
        }, tv)
        self.assertFalse(call["result"]["isError"])
        self.assertEqual(tv.theme[0], "jade-horse")

        bad = mcp_server.handle_rpc({
            "jsonrpc": "2.0", "id": 4, "method": "tools/call",
            "params": {"name": "shell_exec", "arguments": {"cmd": "id"}}
        }, tv)
        self.assertEqual(bad["error"]["code"], -32601)

    def test_http_bearer_auth(self):
        tv = FakeTvClient()
        with tempfile.TemporaryDirectory() as tmp:
            tok = mcp_server.ensure_token(Path(tmp) / "mcp-token")
            srv = mcp_server.create_http_server(lambda: tv, tok, "127.0.0.1", 0)
            threading.Thread(target=srv.serve_forever, daemon=True).start()
            port = srv.server_address[1]
            try:
                # 1. Missing/wrong token -> 401
                c = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
                c.request("POST", "/mcp", body=b'{"jsonrpc":"2.0","id":1,"method":"ping"}',
                          headers={"Content-Type": "application/json", "Authorization": "Bearer wrong"})
                self.assertEqual(c.getresponse().status, 401)
                c.close()

                # 2. Valid token -> 200
                c = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
                c.request("POST", "/mcp", body=b'{"jsonrpc":"2.0","id":1,"method":"tools/list"}',
                          headers={"Content-Type": "application/json", "Authorization": f"Bearer {tok}"})
                r = c.getresponse()
                self.assertEqual(r.status, 200)
                data = json.loads(r.read())
                self.assertTrue(len(data["result"]["tools"]) >= 7)
                c.close()

                # 3. v2.21 a notification: 202, no body (as openapi.yaml says)
                c = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
                c.request("POST", "/mcp", body=b'{"jsonrpc":"2.0","method":"notifications/initialized"}',
                          headers={"Content-Type": "application/json", "Authorization": f"Bearer {tok}"})
                r = c.getresponse()
                self.assertEqual((r.status, r.read()), (202, b""))
                c.close()

                # 4. GET: a JSON 405 with Allow: POST, not the stdlib's 501 HTML
                c = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
                c.request("GET", "/mcp")
                r = c.getresponse()
                self.assertEqual(r.status, 405)
                self.assertEqual(r.getheader("Allow"), "POST")
                self.assertEqual(json.loads(r.read())["error"], "method_not_allowed")
                c.close()
            finally:
                srv.shutdown()


class TestMcpV221(unittest.TestCase):
    def test_notify_and_theme_tools(self):
        tv = FakeTvClient()
        names = [t["name"] for t in mcp_server.handle_rpc({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}, tv)["result"]["tools"]]
        self.assertIn("tv_notify", names)
        self.assertIn("tv_get_theme", names)
        r = mcp_server.handle_rpc({"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                                   "params": {"name": "tv_notify", "arguments": {"text": "x" * 500, "from": "Claude Code"}}}, tv)
        self.assertFalse(r["result"]["isError"])
        self.assertEqual(len(tv.notes[0]["text"]), 200)
        self.assertEqual(tv.notes[0]["from"], "Claude Code")
        r = mcp_server.handle_rpc({"jsonrpc": "2.0", "id": 3, "method": "tools/call",
                                   "params": {"name": "tv_notify", "arguments": {"text": "  "}}}, tv)
        self.assertTrue(r["result"]["isError"])

    def test_widget_tools_map_to_the_tv_api(self):   # v2.22 #157
        sent = []

        class C(FakeTvClient):
            def request(self, method, path, body=None, timeout=8.0):
                sent.append((method, path, body))
                return {"ok": True}
        c = C()
        call = lambda name, args, scope="control": mcp_server.handle_rpc({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                                                                         "params": {"name": name, "arguments": args}}, c, scope)
        call("tv_widget_create", {"id": "nas", "type": "lines", "x": 0, "evil": 1, "data": {"lines": []}})
        self.assertEqual(sent[-1][:2], ("POST", "/v1/widgets/item"))
        self.assertNotIn("evil", sent[-1][2])
        call("tv_widget_update", {"id": "nas", "x": 5, "data": {"lines": []}}, "notify")
        self.assertEqual(sent[-1], ("PATCH", "/v1/widgets/item/nas", {"data": {"lines": []}}))   # content only
        call("tv_widget_move", {"id": "nas", "x": 5, "title": "no"})
        self.assertEqual(sent[-1], ("PATCH", "/v1/widgets/item/nas", {"x": 5}))
        call("tv_widget_list", {"page": 2}, "read")
        self.assertEqual(sent[-1][:2], ("GET", "/v1/widgets/list?page=2"))
        call("tv_widget_delete", {"id": "a/../b"})
        self.assertEqual(sent[-1][1], "/v1/widgets/item/a%2F..%2Fb")
        n = len(sent)
        r = call("tv_widget_create", {"id": "x", "type": "text"}, "notify")
        self.assertIn("error", r)
        self.assertEqual(len(sent), n)   # a notify scope never reaches the TV for a create
        names = {t["name"] for t in mcp_server.handle_rpc({"jsonrpc": "2.0", "id": 2, "method": "tools/list"}, c, "notify")["result"]["tools"]}
        self.assertIn("tv_widget_update", names)
        self.assertNotIn("tv_widget_move", names)

    def test_max_level_caps_tv_notify(self):   # v2.22: the owner's rule, not the model's
        tv = FakeTvClient()
        call = lambda lvl, mx: mcp_server.handle_rpc({"jsonrpc": "2.0", "id": 5, "method": "tools/call", "params": {
            "name": "tv_notify", "arguments": {"text": "disk", "level": lvl, "speak": True}}}, tv, "notify", mx)
        r = call("alert", "warn")
        self.assertEqual(json.loads(r["result"]["content"][0]["text"])["capped"], "warn")
        self.assertEqual((tv.notes[-1]["level"], tv.notes[-1]["speak"]), ("warn", False))
        call("warn", "warn")
        self.assertEqual((tv.notes[-1]["level"], tv.notes[-1]["speak"]), ("warn", True))
        call("alert", "alert")
        self.assertEqual(tv.notes[-1]["level"], "alert")
        self.assertEqual(mcp_server.default_max_level(True, None), "warn")    # through the internet
        self.assertEqual(mcp_server.default_max_level(False, None), "alert")
        self.assertEqual(mcp_server.default_max_level(True, "alert"), "alert")

    def test_theme_tool_reads_the_tv_unsigned(self):
        from http.server import BaseHTTPRequestHandler, HTTPServer

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_GET(self):
                body = json.dumps({"schema": "omarchytv-theme/1", "accent": "#7aa2f7"}).encode()
                self.send_response(200 if self.path == "/v1/theme" else 404)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        srv = HTTPServer(("127.0.0.1", 0), H)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        tv = FakeTvClient()
        tv.host, tv.port = "127.0.0.1", srv.server_address[1]
        try:
            r = mcp_server.handle_rpc({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                                       "params": {"name": "tv_get_theme", "arguments": {}}}, tv)
            self.assertEqual(json.loads(r["result"]["content"][0]["text"])["accent"], "#7aa2f7")
        finally:
            srv.shutdown()

    def test_tailnet_rule(self):
        self.assertTrue(mcp_server.is_tailnet("100.101.2.3"))
        self.assertTrue(mcp_server.is_tailnet("::ffff:100.101.2.3"))
        self.assertTrue(mcp_server.is_tailnet("fd7a:115c:a1e0::9"))
        self.assertFalse(mcp_server.is_tailnet("127.0.0.1"))
        self.assertFalse(mcp_server.is_tailnet("192.168.1.2"))
        run = lambda *a, **k: type("R", (), {"stdout": "100.100.1.1\n", "returncode": 0})()
        orig = mcp_server.shutil.which
        mcp_server.shutil.which = lambda n: "/usr/bin/tailscale"
        try:
            self.assertEqual(mcp_server.tailnet_ip(run), "100.100.1.1")
        finally:
            mcp_server.shutil.which = orig

    def test_tailnet_only_server_refuses_other_peers(self):
        tv = FakeTvClient()
        with tempfile.TemporaryDirectory() as tmp:
            tok = mcp_server.ensure_token(Path(tmp) / "mcp-token")
            srv = mcp_server.create_http_server(lambda: tv, tok, "127.0.0.1", 0, tailnet_only=True)
            threading.Thread(target=srv.serve_forever, daemon=True).start()
            try:
                c = http.client.HTTPConnection("127.0.0.1", srv.server_address[1], timeout=5)
                c.request("POST", "/mcp", body=b'{"jsonrpc":"2.0","id":1,"method":"ping"}',
                          headers={"Content-Type": "application/json", "Authorization": f"Bearer {tok}"})
                self.assertEqual(c.getresponse().status, 403)   # loopback is not the tailnet
                c.close()
            finally:
                srv.shutdown()


class TestMcpScopesAndCloudflare(unittest.TestCase):
    def names(self, scope):
        r = mcp_server.handle_rpc({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}, FakeTvClient(), scope)
        return {t["name"] for t in r["result"]["tools"]}

    def test_every_tool_has_a_scope(self):
        self.assertEqual({t["name"] for t in mcp_server.ALLOWED_TOOLS}, set(mcp_server.TOOL_SCOPE))

    def test_scopes_filter_list_and_call(self):
        read, notify, control = self.names("read"), self.names("notify"), self.names("control")
        self.assertIn("tv_get_state", read)
        self.assertNotIn("tv_notify", read)
        self.assertIn("tv_ask_user", notify)
        self.assertNotIn("tv_send_key", notify)
        self.assertTrue(read < notify < control)
        tv = FakeTvClient()
        r = mcp_server.handle_rpc({"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                                   "params": {"name": "tv_send_key", "arguments": {"key": "home"}}}, tv, "notify")
        self.assertIn("Not allowed", r["error"]["message"])
        self.assertEqual(tv.keys, [])

    def test_ask_user_waits_for_the_answer(self):
        tv = FakeTvClient()
        r = mcp_server.handle_rpc({"jsonrpc": "2.0", "id": 3, "method": "tools/call",
                                   "params": {"name": "tv_ask_user", "arguments": {"question": "Deploy?", "choices": ["Yes", "No"], "wait": 5}}}, tv, "notify")
        out = json.loads(r["result"]["content"][0]["text"])
        self.assertEqual((out["state"], out["answer"]), ("answered", "Yes"))
        self.assertEqual(tv.asked["choices"], ["Yes", "No"])

    def test_internet_defaults_to_notify(self):
        self.assertEqual(mcp_server.default_scope(True, None), "notify")
        self.assertEqual(mcp_server.default_scope(False, None), "control")
        self.assertEqual(mcp_server.default_scope(True, "control"), "control")

    def test_tunnel_argv(self):
        self.assertEqual(mcp_server.tunnel_argv("cloudflared", 8990, None)[-2:], ["--url", "http://127.0.0.1:8990"])
        named = mcp_server.tunnel_argv("cloudflared", 8990, "omarchytv-mcp")
        self.assertEqual(named[:4], ["cloudflared", "tunnel", "--no-autoupdate", "run"])
        self.assertEqual(named[-1], "omarchytv-mcp")

    def test_access_header_required_when_asked(self):
        tv = FakeTvClient()
        with tempfile.TemporaryDirectory() as tmp:
            tok = mcp_server.ensure_token(Path(tmp) / "mcp-token")
            srv = mcp_server.create_http_server(lambda: tv, tok, "127.0.0.1", 0, scope="notify", require_access=True)
            threading.Thread(target=srv.serve_forever, daemon=True).start()
            port = srv.server_address[1]
            body = b'{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
            try:
                c = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
                c.request("POST", "/mcp", body=body, headers={"Authorization": f"Bearer {tok}"})
                self.assertEqual(c.getresponse().status, 403)
                c.close()
                c = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
                c.request("POST", "/mcp", body=body, headers={"Authorization": f"Bearer {tok}", "Cf-Access-Jwt-Assertion": "x.y.z"})
                r = c.getresponse()
                self.assertEqual(r.status, 200)
                names = {t["name"] for t in json.loads(r.read())["result"]["tools"]}
                self.assertNotIn("tv_send_key", names)
                c.close()
            finally:
                srv.shutdown()


if __name__ == "__main__":
    unittest.main()
