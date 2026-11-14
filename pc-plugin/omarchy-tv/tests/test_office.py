import json, os, sys, tempfile, threading, unittest, urllib.error, urllib.request
from http.server import HTTPServer
from pathlib import Path
from unittest import mock
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lib"))
from omarchy_tv import office, webos


class FakeClient:
    def __init__(self):
        self.calls = []

    def request(self, m, p, body=None, **k):
        self.calls.append((m, p, body))
        return {"ok": True}


class OfficeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.env = mock.patch.dict(os.environ, {"XDG_CONFIG_HOME": self.tmp.name})
        self.env.start()
        self.nolink = mock.patch.object(office, "_link_office", lambda: None)   # hermetic: no omarchy-link
        self.nolink.start()

    def tearDown(self):
        self.nolink.stop()
        self.env.stop()
        self.tmp.cleanup()

    def test_clean_keeps_only_real_bots_and_usage_numbers(self):
        d = office.clean({"bots": [{"name": "Trimmer", "role": "Cuts token use", "color": "#FF9E64", "status": "working", "secret": "x"},
                                   {"name": ""}, "junk", {"name": "Trimmer"}, {"name": "<i>Scout</i>", "color": "url(x)", "status": "?"}],
                          "usage": {"weekly_pct": "46.25%", "label": "Weekly usage", "resets": "Mon 09:00"}})
        self.assertEqual([b["name"] for b in d["bots"]], ["Trimmer", "iScout/i"])
        self.assertEqual(d["bots"][0], {"id": "trimmer", "name": "Trimmer", "status": "working", "role": "Cuts token use", "color": "#ff9e64", "provider": "grok"})
        self.assertNotIn("color", d["bots"][1])
        self.assertEqual(d["bots"][1]["status"], "idle")
        self.assertEqual(d["usage"], {"weekly_pct": 46.2, "label": "Weekly usage", "resets": "Mon 09:00"})
        self.assertNotIn("usage", office.clean({"bots": [], "usage": {"label": "no number"}}))
        self.assertNotIn("usage", office.clean({"bots": [], "usage": {"weekly_pct": 101}}))
        self.assertNotIn("usage", office.clean({"bots": [], "usage": {"weekly_pct": True}}))
        self.assertEqual(len(office.clean({"bots": [{"name": f"b{i}"} for i in range(40)]})["bots"]), office.MAX_BOTS)
        with self.assertRaises(office.OfficeError):
            office.clean([1, 2])

    def test_push_saves_for_the_bridge_and_sends_to_android(self):
        f = Path(self.tmp.name, "bots.json")
        f.write_text(json.dumps({"bots": [{"name": "Scout", "color": "cyan"}], "usage": {"weekly_pct": 12}}))
        c, out = FakeClient(), []
        self.assertEqual(office.run(["push", str(f)], lambda: c, say=out.append), 0)
        self.assertEqual(c.calls, [("PUT", "/v1/office", {"bots": [{"id": "scout", "name": "Scout", "status": "idle", "color": "cyan", "provider": "grok"}], "usage": {"weekly_pct": 12.0}})])
        self.assertEqual(office.load()["bots"][0]["name"], "Scout")
        self.assertTrue(any("12.0%" in s for s in out))

    def test_push_without_a_paired_tv_still_saves(self):
        f = Path(self.tmp.name, "bots.json")
        f.write_text(json.dumps({"bots": [{"name": "Solo"}]}))
        def unpaired():
            raise SystemExit(3)
        out = []
        self.assertEqual(office.run(["push", str(f)], unpaired, say=out.append), 0)
        self.assertEqual(office.load()["bots"][0]["name"], "Solo")
        self.assertTrue(any("no Android TV paired" in s for s in out))

    def test_bad_files_say_why(self):
        bad = Path(self.tmp.name, "bad.json")
        bad.write_text("{nope")
        with self.assertRaises(office.OfficeError):
            office.run(["push", str(bad)], None, say=lambda s: None)
        with self.assertRaises(office.OfficeError):
            office.run(["push", str(Path(self.tmp.name, "missing.json"))], None, say=lambda s: None)
        with self.assertRaises(office.OfficeError):
            office.run(["push"], None, say=lambda s: None)
        with self.assertRaises(office.OfficeError):
            office.run(["dance"], None, say=lambda s: None)

    def test_payload_falls_back_to_the_roster_without_usage_and_clear_empties(self):
        self.assertEqual(office.payload(), {"bots": [], "source": "none"})
        p = office.payload([{"id": "abc", "name": "Grok Bot", "shortName": "Grok", "fill": "#7aa2f7"}])
        self.assertEqual(p["source"], "roster")
        self.assertEqual(p["bots"][0]["name"], "Grok")
        self.assertNotIn("usage", p)
        office.save(office.clean({"bots": [{"name": "Pushed"}]}))
        self.assertEqual(office.payload([{"name": "Ignored"}])["bots"][0]["name"], "Pushed")
        c = FakeClient()
        office.run(["clear"], lambda: c, say=lambda s: None)
        self.assertIsNone(office.load())
        self.assertEqual(c.calls, [("PUT", "/v1/office", {"bots": []})])

    def test_bridge_serves_get_v1_office(self):
        office.save(office.clean({"bots": [{"name": "Herald", "status": "working"}], "usage": {"weekly_pct": 46.3}}))
        srv = HTTPServer(("127.0.0.1", 0), webos.WebOSHandler)
        t = threading.Thread(target=srv.serve_forever, daemon=True)
        t.start()
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{srv.server_port}/v1/office", timeout=5) as r:
                self.assertEqual(r.headers.get("Access-Control-Allow-Origin"), "*")
                j = json.loads(r.read().decode("utf-8"))
            self.assertEqual(j["source"], "push")
            self.assertEqual(j["bots"][0]["name"], "Herald")
            self.assertEqual(j["usage"]["weekly_pct"], 46.3)
        finally:
            srv.shutdown()

    def test_provider_defaults_to_grok_and_stays_a_short_word(self):
        d = office.clean({"bots": [{"name": "A"}, {"name": "B", "provider": "Claude"}, {"name": "C", "provider": "ChatGPT"},
                                   {"name": "D", "provider": "<script>mistral large 2 with many words</script>"},
                                   {"name": "E", "provider": 7}, {"name": "F", "provider": "  "}, {"name": "G", "assistant": "Office-G!"}]})
        self.assertEqual([b["provider"] for b in d["bots"]][:3], ["grok", "claude", "chatgpt"])
        self.assertLessEqual(len(d["bots"][3]["provider"]), 16)
        self.assertRegex(d["bots"][3]["provider"], r"^[a-z0-9._-]+$")
        self.assertEqual(d["bots"][5]["provider"], "grok")
        self.assertEqual(d["bots"][6]["assistant"], "office-g")

    def test_providers_usage_only_with_real_numbers(self):
        d = office.clean({"bots": [], "providers_usage": [
            {"provider": "claude", "spent": 12.345, "unit": "$", "budget": 50, "label": "Claude API", "resets": "2026-12-01T00:00:00Z"},
            {"provider": "chatgpt", "spent": 3.5},
            {"provider": "claude", "spent": "lots"}, {"provider": "x", "spent": -1}, {"provider": "y", "spent": True}, "junk"]})
        u = d["providers_usage"]
        self.assertEqual(len(u), 2)
        self.assertEqual(u[0], {"provider": "claude", "spent": 12.35, "unit": "$", "period": "month", "label": "Claude API",
                                "budget": 50.0, "pct": 24.7, "resets": "2026-12-01T00:00:00Z"})
        self.assertNotIn("pct", u[1])   # no budget, no percentage
        self.assertNotIn("providers_usage", office.clean({"bots": [], "providers_usage": "no"}))

    def test_with_link_adds_hired_bots_and_survives_a_broken_link(self):
        class Lo:
            @staticmethod
            def merge(d):
                return dict(d, bots=list(d["bots"]) + [{"id": "office-trimmer", "name": "Trimmer", "provider": "claude", "assistant": "office-trimmer"}],
                            providers_usage=[{"provider": "claude", "spent": 1.0}])
        office.save(office.clean({"bots": [{"name": "Scout"}]}))
        p = office.payload(lo=Lo)
        self.assertEqual([(b["name"], b["provider"]) for b in p["bots"]], [("Scout", "grok"), ("Trimmer", "claude")])
        self.assertEqual(p["bots"][1]["assistant"], "office-trimmer")
        self.assertEqual(p["source"], "push")
        self.assertEqual(p["providers_usage"][0]["spent"], 1.0)

        class Broken:
            @staticmethod
            def merge(d):
                raise ValueError("assistant.json is broken")
        self.assertEqual([b["name"] for b in office.payload(lo=Broken)["bots"]], ["Scout"])

    def test_bridge_hire_needs_the_token_and_reports_what_happened(self):
        class Err(Exception):
            def __init__(self, code, msg):
                super().__init__(msg)
                self.code = code

        class Lo:
            HireError = Err
            calls = []

            @classmethod
            def hire(cls, name, provider, role, description):
                cls.calls.append((name, provider, role, description))
                if provider == "claude":
                    raise Err("no_key", "no Claude API key on the PC")
                return {"id": "office-scout", "name": name, "provider": "chatgpt", "assistant": "office-scout"}

            @staticmethod
            def merge(d):
                return d
        srv = HTTPServer(("127.0.0.1", 0), webos.WebOSHandler)
        threading.Thread(target=srv.serve_forever, daemon=True).start()

        def post(body, token):
            req = urllib.request.Request(f"http://127.0.0.1:{srv.server_port}/v1/office/hire", data=json.dumps(body).encode(), method="POST",
                                         headers={"Content-Type": "application/json", **({"X-Omarchy-Token": token} if token else {})})
            try:
                with urllib.request.urlopen(req, timeout=5) as r:
                    return r.status, json.loads(r.read())
            except urllib.error.HTTPError as e:
                return e.code, json.loads(e.read())
        try:
            with mock.patch.object(webos, "_link_mod", lambda n: Lo if n == "office" else None), \
                    mock.patch.object(webos, "ASK_GAP_S", 0), mock.patch.object(webos, "TOKEN_FILE", Path(self.tmp.name, "tok")):
                self.assertEqual(post({"name": "Scout", "provider": "chatgpt"}, None)[0], 401)
                st, j = post({"name": "Scout", "provider": "chatgpt", "role": "Finds things"}, webos.bridge_token())
                self.assertEqual((st, j["ok"], j["bot"]["name"]), (200, True, "Scout"))
                self.assertIn("office", j)
                st, j = post({"name": "Trimmer", "provider": "claude"}, webos.bridge_token())
                self.assertEqual((st, j["ok"], j["error"]), (502, False, "no_key"))
            self.assertEqual(Lo.calls[0], ("Scout", "chatgpt", "Finds things", ""))
        finally:
            srv.shutdown()


if __name__ == "__main__":
    unittest.main()
