import json, os, sys, threading, unittest, urllib.error, urllib.request
from unittest import mock
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
    UPSTREAM, UPSTREAM_REF = "https://github.com/tcballard/omarchy-plugin-grokbot-desktop", "v0.2.0"
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
                "dispatch": "webhook", "version": "0.2.0"}

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



class FakeTheme:
    def __init__(self):
        self.asked = []

    def set_theme(self, name):
        self.asked.append(name)
        return {"gruvbox": "ok", "nord": "same", "busy": "busy"}.get(name, "unknown")


class ThemeSetBridgeTest(unittest.TestCase):
    post = GrokbotBridgeTest.post   # reuse the server and helper, not the grokbot tests

    def setUp(self):
        GrokbotBridgeTest.setUp(self)
        self.old_t, self.old_cfg = webos._link_theme, webos.link_cfg
        self.th = FakeTheme()
        self.cfg = {}
        webos._link_theme = lambda: self.th
        webos.link_cfg = lambda: self.cfg

    def tearDown(self):
        webos._link_theme, webos.link_cfg = self.old_t, self.old_cfg
        GrokbotBridgeTest.tearDown(self)

    def test_needs_the_code(self):
        self.assertEqual(self.post("/v1/theme/set", {"name": "gruvbox"})[0], 401)
        self.assertEqual(self.th.asked, [])

    def test_results_map_to_status_codes(self):
        tok = webos.bridge_token()
        self.assertEqual(self.post("/v1/theme/set", {"name": "gruvbox"}, tok), (200, {"result": "ok"}))
        self.assertEqual(self.post("/v1/theme/set", {"name": "nord"}, tok), (200, {"result": "same"}))
        self.assertEqual(self.post("/v1/theme/set", {"name": "x"}, tok), (404, {"result": "unknown"}))
        self.assertEqual(self.post("/v1/theme/set", {"name": "busy"}, tok)[0], 429)

    def test_disabled_on_the_pc_and_not_installed(self):
        tok = webos.bridge_token()
        self.cfg["themeFromTv"] = False
        self.assertEqual(self.post("/v1/theme/set", {"name": "gruvbox"}, tok)[0], 403)
        self.assertEqual(self.th.asked, [])
        webos._link_theme = lambda: None
        self.assertEqual(self.post("/v1/theme/set", {"name": "gruvbox"}, tok)[0], 503)


class UpdatePowerBridgeTest(unittest.TestCase):
    post = GrokbotBridgeTest.post

    def setUp(self):
        GrokbotBridgeTest.setUp(self)
        self.old_cfg, self.old_mod = webos.link_cfg, webos._link_mod
        self.cfg = {}
        webos.link_cfg = lambda: self.cfg
        self.power_calls = []
        outer = self

        class P:
            @staticmethod
            def tv_power(state, cfg, log):
                outer.power_calls.append(state)
                return "scheduled"
        webos._link_mod = lambda n: P if n == "power" else None

    def tearDown(self):
        webos.link_cfg, webos._link_mod = self.old_cfg, self.old_mod
        GrokbotBridgeTest.tearDown(self)

    def test_power_needs_the_code_and_reaches_omarchy_link(self):
        self.assertEqual(self.post("/v1/power", {"state": "standby"})[0], 401)
        tok = webos.bridge_token()
        self.assertEqual(self.post("/v1/power", {"state": "standby"}, tok), (200, {"result": "scheduled"}))
        self.assertEqual(self.power_calls, ["standby"])
        webos._link_mod = lambda n: None
        self.assertEqual(self.post("/v1/power", {"state": "on"}, tok)[0], 503)

    def test_update_check_install_and_off(self):
        from omarchy_tv import tvupdate
        tok = webos.bridge_token()
        self.assertEqual(self.post("/v1/update/tv", {"action": "check", "platform": "webos"})[0], 401)
        with mock.patch.object(tvupdate, "check", return_value={"current": "2.13.11", "latest": "2.13.12", "name": "x"}):
            st, d = self.post("/v1/update/tv", {"action": "check", "platform": "webos", "version": "2.13.11"}, tok)
        self.assertEqual((st, d["latest"]), (200, "2.13.12"))
        with mock.patch.object(tvupdate, "start", return_value="started") as stt:
            st, d = self.post("/v1/update/tv", {"action": "install", "platform": "tizen", "version": "2.13.11",
                                                "want": "2.13.12"}, tok)
        self.assertEqual((st, d), (200, {"result": "started"}))
        self.assertEqual(stt.call_args[0][:4], ("tizen", "2.13.11", "2.13.12", "127.0.0.1"))
        with mock.patch.object(tvupdate, "check", side_effect=tvupdate.UpdateError("bad_platform")):
            self.assertEqual(self.post("/v1/update/tv", {"platform": "x"}, tok)[0], 400)
        self.cfg["updateFromTv"] = False
        self.assertEqual(self.post("/v1/update/tv", {"action": "install"}, tok)[0], 403)

    def test_living_needs_the_code_and_returns_the_feed(self):
        class FakeLiving:
            def __init__(self, *a):
                self.prefs = None
                self.touched = False

            def set_prefs(self, p):
                self.prefs = p

            def touch(self):
                self.touched = True

            def payload(self):
                return {"github": [], "notes": [{"app": "Slack", "title": "Ola", "body": "ciao"}], "x": [], "xMode": "off"}

        class Mod:
            Living = FakeLiving
        webos._living_obj = None
        webos._link_mod = lambda n: Mod if n == "living" else None
        self.assertEqual(self.post("/v1/living", {"notes": True})[0], 401)
        tok = webos.bridge_token()
        st, d = self.post("/v1/living", {"notes": True, "x": "notify"}, tok)
        self.assertEqual((st, d["notes"][0]["title"]), (200, "Ola"))
        self.assertEqual(webos._living_obj.prefs, {"notes": True, "x": "notify"})
        self.assertTrue(webos._living_obj.touched)
        self.cfg["livingToTv"] = False
        self.assertEqual(self.post("/v1/living", {}, tok)[0], 403)
        webos._living_obj = None

    def test_status_has_liftoff(self):
        with urllib.request.urlopen(self.base + "/v1/status", timeout=5) as r:
            self.assertIn("liftoff", json.loads(r.read()))

class FakeAssistantMod:
    class NoProvider(Exception): pass
    class RateLimited(Exception): pass


class FakeAssistant:
    def __init__(self):
        self.asked = []

    def listing(self):
        return {"providers": [{"id": i, "name": n} for i, n in (("claude-code", "Claude"), ("codex", "ChatGPT (Codex)"),
                ("grok-cli", "Grok"), ("hermes-cli", "Hermes"))], "default": "claude-code"}

    def ask(self, text, provider=None, conversation=None, lang=None):
        if not text:
            raise ValueError("empty text")
        if provider == "nope":
            raise FakeAssistantMod.NoProvider("provider 'nope' is not available on the PC")
        self.asked.append((text, provider, lang))
        return {"text": provider + " says hi", "provider": provider, "model": None, "conversationId": conversation or "abcd1234"}


class AiAgentsBridgeTest(unittest.TestCase):
    post = GrokbotBridgeTest.post

    def setUp(self):
        GrokbotBridgeTest.setUp(self)
        self.old_a = webos._assistant
        self.asst = FakeAssistant()
        webos._assistant = lambda: (FakeAssistantMod, self.asst)

    def tearDown(self):
        webos._assistant = self.old_a
        GrokbotBridgeTest.tearDown(self)

    def test_agents_list_and_ask(self):
        self.assertEqual(self.post("/v1/ai/providers", {})[0], 401)
        tok = webos.bridge_token()
        st, d = self.post("/v1/ai/providers", {}, tok)
        self.assertEqual(st, 200)
        self.assertEqual([p["id"] for p in d["providers"]], ["claude-code", "codex", "grok-cli", "hermes-cli"])
        st, d = self.post("/v1/ai/ask", {"text": "ciao", "provider": "hermes-cli", "lang": "it-IT"}, tok)
        self.assertEqual((st, d["text"], d["session"]), (200, "hermes-cli says hi", "abcd1234"))
        self.assertEqual(self.asst.asked, [("ciao", "hermes-cli", "it-IT")])
        self.assertEqual(self.post("/v1/ai/ask", {"text": "x", "provider": "nope"}, tok)[0], 404)
        self.assertEqual(self.post("/v1/ai/ask", {"text": ""}, tok)[0], 400)


if __name__ == "__main__":
    unittest.main()
