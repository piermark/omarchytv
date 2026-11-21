"""v2.22 AI LIVE, the night shift and skills on LG / Samsung, kept by the PC bridge (ports of the Android models)."""
import http.client
import json
import os
import sys
import tempfile
import threading
import time
import unittest
from http.server import HTTPServer
from pathlib import Path
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "lib"))

from omarchy_tv import ai_bridge as ab, home_grid as hg, webos  # noqa: E402

ROOT = Path(__file__).resolve().parents[3]


class ModelTest(unittest.TestCase):
    def test_the_catalogue_is_the_launchers(self):
        mine = sorted(p.name for p in ab.catalog_dir().glob("*.json"))
        tv = sorted(p.name for p in (ROOT / "launcher/app/src/main/assets/skills").glob("*.json"))
        self.assertEqual(mine, tv)
        for n in mine:
            self.assertEqual((ab.catalog_dir() / n).read_bytes(), (ROOT / "launcher/app/src/main/assets/skills" / n).read_bytes(), n)
        self.assertEqual(set(ab.catalog()), {"night-shift", "homelab", "movie-night"})

    def test_shift_rules(self):
        with tempfile.TemporaryDirectory() as d:
            s = ab.Shift(os.path.join(d, "s.json"))
            now = 1_800_000_000.0
            with self.assertRaises(ab.Bad):
                s.log("a", {"text": "x"}, now)
            s.start({"tasks": ["fix tests"], "until": (now + 3600) * 1000, "wake": (now + 4000) * 1000}, "owner", now)
            self.assertTrue(s.quiet(now + 10))
            s.log("claude", {"level": "ok", "text": "green‮", "task": 0}, now + 1)
            q = s.queue("claude", {"title": "push", "risk": "push"}, now + 2)
            self.assertEqual(q["id"], "q1")
            self.assertEqual(s.answer("q1", True, now + 3)["state"], "approved")
            with self.assertRaises(ab.Bad):
                s.answer("q1", False, now + 4)
            self.assertEqual(s.status(now=now + 5)["counts"], {"ok": 1, "fail": 0, "info": 0, "waiting": 0})
            self.assertFalse(s.quiet(now + 3601))
            s.log("late", {"text": "within the grace"}, now + 3600 + ab.GRACE_S - 1)
            with self.assertRaises(ab.Bad):
                s.log("late", {"text": "too late"}, now + 3600 + ab.GRACE_S)
            self.assertEqual(os.stat(s.path).st_mode & 0o777, 0o600)

    def test_live_and_previews(self):
        f = ab.LiveFeed()
        f.now("Claude", {"text": "tests\nnow", "progress": 140, "preview": {"kind": "diff", "text": "+ a"}}, 1)
        f.waiting("Claude", "a1", "push?", 2)
        a = f.snapshot(3)["agents"][0]
        self.assertEqual((a["text"], a["progress"], a["waiting"]["id"]), ("tests now", 100, "a1"))
        f.answered("a1", "Deny", 4)
        self.assertNotIn("waiting", f.snapshot(5)["agents"][0])
        self.assertIsNone(ab.preview({"kind": "image", "url": "http://8.8.8.8/x.png"}))
        self.assertIsNone(ab.preview({"kind": "html", "text": "<b>"}))

    def test_the_agent_monitor_goes_on_air_by_itself(self):
        f = ab.LiveFeed()
        f.from_monitor({"agents": [{"agentName": "Claude Code", "project": "omarchytv", "status": "running", "step": "Bash · npm test"},
                                   {"agentName": "Codex", "status": "idle", "step": "x"}, "junk"]}, 10)
        a = f.snapshot(10)["agents"]
        self.assertEqual([(x["agent"], x["text"]) for x in a], [("Claude Code · omarchytv", "Bash · npm test")])

    def test_skill_install_and_remove(self):
        cfg = {"widgets": []}
        rec = ab.skill_install(cfg, ab.catalog()["homelab"], 10)
        self.assertEqual((rec["page"], len(rec["widgets"])), (2, 5))
        self.assertEqual(hg.name(cfg, 2), "Homelab")
        ab.skill_remove(cfg, "homelab")
        self.assertEqual((cfg["widgets"], hg.total(cfg)), ([], 1))
        bad = dict(ab.catalog()["homelab"], permissions=["notify"])
        with self.assertRaises(ab.Bad):
            ab.check_skill(bad)


class BridgeTest(unittest.TestCase):
    A = "agent-key-0123456789abcdef"

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.patches = [mock.patch.object(webos, "bridge_token", return_value="ABCD1234"),
                        mock.patch.object(webos, "bridge_agent_token", return_value=self.A),
                        mock.patch.object(webos, "_home_store", hg.Store(os.path.join(self.tmp.name, "w.json"))),
                        mock.patch.object(ab, "SHIFT", ab.Shift(os.path.join(self.tmp.name, "s.json"))),
                        mock.patch.object(ab, "LIVE", ab.LiveFeed())]
        for p in self.patches:
            p.start()
        ab.SKILL_ASK.clear()
        self.srv = HTTPServer(("127.0.0.1", 0), webos.WebOSHandler)
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()

    def tearDown(self):
        self.srv.shutdown()
        self.srv.server_close()
        for p in self.patches:
            p.stop()
        self.tmp.cleanup()

    def req(self, method, path, body=None, token=None, agent=None):
        c = http.client.HTTPConnection("127.0.0.1", self.srv.server_address[1], timeout=8)
        h = {"Content-Type": "text/plain"}
        if token:
            h["X-Omarchy-Token"] = token
        if agent:
            h["X-Omarchy-Agent"] = agent
        c.request(method, path, body=json.dumps(body).encode() if body is not None else None, headers=h)
        r = c.getresponse()
        out = (r.status, json.loads(r.read() or b"{}"))
        c.close()
        return out

    def test_a_night_on_lg(self):
        A, T = self.A, "ABCD1234"
        until = int((time.time() + 3600) * 1000)
        self.assertEqual(self.req("POST", "/v1/shift/start", {"tasks": ["x"], "until": until}, token=T)[0], 401)   # not the TV
        self.assertEqual(self.req("POST", "/v1/shift/start", {"tasks": ["x"], "until": until, "from": "Hermes"}, agent=A)[0], 200)
        self.assertEqual(self.req("POST", "/v1/notify", {"text": "build red", "from": "CI"}, agent=A)[1].get("kept_for_morning"), True)
        self.assertEqual(self.req("POST", "/v1/shift/log", {"level": "ok", "text": "fixed", "from": "Hermes"}, agent=A)[0], 200)
        st, q = self.req("POST", "/v1/shift/queue", {"title": "push", "from": "Hermes"}, agent=A)
        self.assertEqual(q["item"]["id"], "q1")
        self.assertEqual(self.req("POST", "/v1/shift/answer", {"id": "q1", "yes": True}, agent=A)[0], 401)   # agents cannot answer
        self.assertEqual(self.req("POST", "/v1/shift/answer", {"id": "q1", "yes": True}, token=T)[0], 200)
        self.assertEqual(self.req("GET", "/v1/shift/queue/q1", agent=A)[1]["state"], "approved")
        st, v = self.req("GET", "/v1/tvlive", token=T)
        self.assertEqual(v["shift"]["counts"]["fail"] + v["shift"]["counts"]["info"] + v["shift"]["counts"]["ok"], 2)
        self.assertTrue(any(a["agent"] == "Hermes" for a in v["live"]["agents"]))

    def test_live_ask_with_preview_and_skills(self):
        A, T = self.A, "ABCD1234"
        self.assertEqual(self.req("POST", "/v1/ask", {"question": "push?", "from": "Claude", "preview": {"kind": "html"}}, agent=A)[0], 400)
        st, a = self.req("POST", "/v1/ask", {"question": "push?", "from": "Claude", "preview": {"kind": "text", "text": "ok"}}, agent=A)
        self.assertEqual(st, 200)
        live = self.req("GET", "/v1/tvlive", token=T)[1]["live"]["agents"][0]
        self.assertEqual((live["waiting"]["id"], live["preview"]["kind"]), (a["id"], "text"))
        self.assertEqual(self.req("POST", "/v1/skills/homelab/install", {}, agent=A)[0], 200)
        self.assertEqual(self.req("GET", "/v1/tvlive", token=T)[1]["skillAsk"]["id"], "homelab")
        self.assertEqual(self.req("POST", "/v1/skills/answer", {"id": "homelab", "yes": True}, agent=A)[0], 401)
        st, r = self.req("POST", "/v1/skills/answer", {"id": "homelab", "yes": True}, token=T)
        self.assertTrue(r["installed"])
        self.assertIn("homelab", self.req("GET", "/v1/skills", agent=A)[1]["installed"])
        self.assertEqual(len(self.req("GET", "/v1/home", token=T)[1]["widgets"]), 5)
        self.assertEqual(self.req("DELETE", "/v1/skills/homelab", agent=A)[0], 200)
        self.assertEqual(self.req("POST", "/v1/tvskills/install", {"id": "movie-night"}, token=T)[0], 200)
