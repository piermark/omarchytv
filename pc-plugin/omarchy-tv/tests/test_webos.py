import json, os, sys, threading, unittest, urllib.error, urllib.request
from http.server import HTTPServer
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "lib"))
from omarchy_tv import webos


class WebOSBridgeTest(unittest.TestCase):
    def test_status_and_themes_endpoints(self):
        srv = HTTPServer(("127.0.0.1", 0), webos.WebOSHandler)
        port = srv.server_port
        t = threading.Thread(target=srv.serve_forever, daemon=True)
        t.start()
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/v1/status", timeout=5) as r:
                self.assertEqual(r.status, 200)
                self.assertEqual(r.headers.get("Access-Control-Allow-Origin"), "*")
                st = json.loads(r.read().decode("utf-8"))
                self.assertIn("cpu", st)
                self.assertIn("ram", st)
                self.assertIn("host", st)
                self.assertEqual(st.get("version"), "2.11.0-testing")
                self.assertIsInstance(st.get("ai"), list)

            with urllib.request.urlopen(f"http://127.0.0.1:{port}/v1/themes", timeout=5) as r:
                self.assertEqual(r.status, 200)
                th = json.loads(r.read().decode("utf-8"))
                self.assertIn("themes", th)
        finally:
            srv.shutdown()
            srv.server_close()


class FakeGrokbot:
    UPSTREAM, UPSTREAM_REF = "https://github.com/tcballard/omarchy-plugin-grokbot-desktop", "v0.1.0"
    PALETTE = {"orange": "#FF6700"}

    class GrokbotError(Exception):
        def __init__(self, code, msg):
            super().__init__(msg)
            self.code = code

    asked = []

    def plain(self, v, n):
        return str(v or "")[:n]

    def color_name(self, v):
        return "orange"

    def avatar_shape(self, v):
        return "teardrop"

    def current(self, cfg):
        return {"id": "b1", "name": "Bob", "shortName": "Bob", "avatarShape": "teardrop", "fill": "#FF6700",
                "dispatch": "webhook", "version": "0.1.0"}

    def roster(self, cfg):
        return [{"id": "b1", "name": "Bob", "avatarShape": "drop", "avatarColor": "orange"}]

    def ask(self, cfg, text, bot=None):
        if not text:
            raise self.GrokbotError("empty", "empty question")
        self.asked.append((text, bot))
        return {"bot": "Bob", "text": "hi " + text, "mode": "relay", "links": []}


class GrokbotBridgeTest(unittest.TestCase):
    def setUp(self):
        import tempfile
        self.tmp = tempfile.mkdtemp()
        self.old_tok, self.old_g = webos.TOKEN_FILE, webos._grokbot
        webos.TOKEN_FILE = webos.Path(self.tmp) / "tok"
        self.fake = FakeGrokbot()
        webos._grokbot = lambda: self.fake
        webos._last_ask = 0.0
        self.srv = HTTPServer(("127.0.0.1", 0), webos.WebOSHandler)
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.srv.server_port}"

    def tearDown(self):
        self.srv.shutdown()
        self.srv.server_close()
        webos.TOKEN_FILE, webos._grokbot = self.old_tok, self.old_g

    def post(self, path, body, token=None):
        req = urllib.request.Request(self.base + path, data=json.dumps(body).encode(), method="POST",
                                     headers={"Content-Type": "application/json", **({"X-Omarchy-Token": token} if token else {})})
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    def test_state_is_public_and_has_no_secrets(self):
        with urllib.request.urlopen(self.base + "/v1/grokbot", timeout=5) as r:
            d = json.loads(r.read())
        self.assertTrue(d["available"])
        self.assertEqual(d["bot"]["name"], "Bob")
        self.assertEqual(d["roster"][0]["avatarShape"], "teardrop")
        self.assertNotIn("token", json.dumps(d).lower())

    def test_ask_needs_the_code(self):
        self.assertEqual(self.post("/v1/grokbot/ask", {"text": "x"})[0], 401)
        self.assertEqual(self.post("/v1/grokbot/ask", {"text": "x"}, "WRONG123")[0], 401)
        self.assertEqual(self.fake.asked, [])
        tok = webos.bridge_token()
        self.assertEqual(oct(os.stat(webos.TOKEN_FILE).st_mode & 0o777), "0o600")
        st, d = self.post("/v1/grokbot/ask", {"text": "ciao", "bot": "Bob"}, tok.lower())
        self.assertEqual((st, d["text"]), (200, "hi ciao"))
        self.assertEqual(self.fake.asked, [("ciao", "Bob")])

    def test_rate_limit_and_errors(self):
        tok = webos.bridge_token()
        self.assertEqual(self.post("/v1/grokbot/ask", {"text": ""}, tok)[0], 400)
        self.assertEqual(self.post("/v1/grokbot/ask", {"text": "again"}, tok)[0], 429)
        webos._last_ask = 0.0
        self.assertEqual(self.post("/v1/nope", {}, tok)[0], 404)


if __name__ == "__main__":
    unittest.main()
