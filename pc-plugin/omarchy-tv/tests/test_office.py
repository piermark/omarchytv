import json, os, sys, tempfile, threading, unittest, urllib.request
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

    def tearDown(self):
        self.env.stop()
        self.tmp.cleanup()

    def test_clean_keeps_only_real_bots_and_usage_numbers(self):
        d = office.clean({"bots": [{"name": "Trimmer", "role": "Cuts token use", "color": "#FF9E64", "status": "working", "secret": "x"},
                                   {"name": ""}, "junk", {"name": "Trimmer"}, {"name": "<i>Scout</i>", "color": "url(x)", "status": "?"}],
                          "usage": {"weekly_pct": "46.25%", "label": "Weekly usage", "resets": "Mon 09:00"}})
        self.assertEqual([b["name"] for b in d["bots"]], ["Trimmer", "iScout/i"])
        self.assertEqual(d["bots"][0], {"id": "trimmer", "name": "Trimmer", "status": "working", "role": "Cuts token use", "color": "#ff9e64"})
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
        self.assertEqual(c.calls, [("PUT", "/v1/office", {"bots": [{"id": "scout", "name": "Scout", "status": "idle", "color": "cyan"}], "usage": {"weekly_pct": 12.0}})])
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


if __name__ == "__main__":
    unittest.main()
