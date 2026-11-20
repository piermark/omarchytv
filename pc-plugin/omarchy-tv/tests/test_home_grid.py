"""v2.22 #157-#159 Home widgets and pages for LG / Samsung, kept by the PC bridge (a port of the Android model)."""
import http.client
import json
import os
import sys
import tempfile
import threading
import unittest
from http.server import HTTPServer
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "lib"))

from omarchy_tv import home_grid as hg, webos
from omarchy_tv.bridge_client import BridgeClient


class ModelTest(unittest.TestCase):
    def test_placement_matches_android(self):
        all_ = []
        a = hg.create(all_, {"id": "cpu", "type": "stat", "data": {"value": "42", "unit": "%"}}, "pc", 1)
        self.assertEqual((a["x"], a["y"], a["w"], a["h"], a["page"]), (0, 0, 2, 1, 1))
        b = hg.create(all_, {"id": "log", "type": "lines", "data": {"lines": [{"label": "ok", "value": "1", "tone": "up"}]}}, "pc", 1)
        self.assertEqual((b["x"], b["y"]), (2, 0))
        with self.assertRaises(hg.Bad) as e:
            hg.create(all_, {"id": "x", "type": "text", "x": 1, "y": 0}, "pc", 1)
        self.assertEqual((e.exception.status, e.exception.code), (409, "cell_taken"))
        self.assertTrue(e.exception.free)
        c = hg.create(all_, {"id": "big", "type": "text", "x": 0, "y": 0, "w": 4, "h": 2, "push": True}, "pc", 1)
        self.assertEqual((c["x"], c["y"]), (0, 0))
        for o in all_:   # nothing overlaps after the push
            others = [p for p in all_ if p is not o and p["page"] == o["page"]]
            self.assertTrue(all(not hg.overlap(o["x"], o["y"], o["w"], o["h"], p["x"], p["y"], p["w"], p["h"]) for p in others))
        with self.assertRaises(hg.Bad):
            hg.create(all_, {"id": "cpu", "type": "stat"}, "pc", 1)

    def test_data_is_checked_and_text_only(self):
        d = hg.clean_data("text", {"text": "a\u0000b‮\nline 2"})
        self.assertEqual(d["text"], "ab\nline 2")
        for t, data in (("image", {"url": "http://8.8.8.8/x.png"}), ("page", {"url": "https://example.com"}),
                        ("chart", {"points": ["1"]}), ("homeassistant.scenes", {"items": ["Scene Movie"]}),
                        ("shortcuts", {"items": [{"id": "x", "action": {"type": "shell", "command": "rm"}}]}),
                        ("nope", {})):
            with self.assertRaises(hg.Bad, msg=t):
                hg.clean_data(t, data)
        self.assertEqual(hg.clean_data("homeassistant.scenes", {"items": "all"})["items"], "all")
        self.assertEqual(hg.clean_data("qr", {"preset": "skill"}), {"preset": "skill"})
        with self.assertRaises(hg.Bad):
            hg.clean_data("text", {"text": "x" * 20000})

    def test_pages_on_demand_reorder_delete(self):
        cfg = {"widgets": []}
        idx = hg.on_demand(cfg, "casa", False, 10)
        self.assertEqual(idx, 2)
        hg.create(cfg["widgets"], {"id": "lights", "type": "radio", "page": idx}, "pc", 10)
        self.assertEqual(hg.resolve(cfg, "casa"), 2)
        self.assertEqual(hg.on_demand(cfg, 9, False, 10), 3)   # past the end: the next page
        with self.assertRaises(hg.Bad):
            hg.on_demand(cfg, "nope", True, 10)
        three = hg.page_create(cfg, {"name": "Work"}, 10)
        self.assertEqual(three, 3)
        hg.page_reorder(cfg, "casa", 3, 10)
        self.assertEqual(hg.resolve(cfg, "casa"), 3)
        self.assertEqual(cfg["widgets"][0]["page"], 3)
        with self.assertRaises(hg.Bad):
            hg.page_reorder(cfg, 1, 2, 10)
        self.assertEqual(hg.page_delete(cfg, "casa", 1, 10), 1)
        self.assertEqual(cfg["widgets"][0]["page"], 1)
        self.assertEqual(hg.total(cfg), 2)

    def test_empty_pages_go_after_a_day(self):
        cfg = {"widgets": []}
        hg.page_create(cfg, {"name": "Tmp"}, 0)
        self.assertEqual(hg.total(cfg), 2)
        hg.cleanup(cfg, hg.EMPTY_TTL_S - 1)
        self.assertEqual(hg.total(cfg), 2)
        hg.cleanup(cfg, hg.EMPTY_TTL_S + 1)
        self.assertEqual(hg.total(cfg), 1)

    def test_store_writes_nothing_on_an_error(self):
        with tempfile.TemporaryDirectory() as d:
            st = hg.Store(os.path.join(d, "w.json"))
            self.assertEqual(hg.route(st, "POST", "/v1/widgets/item", {"id": "a", "type": "stat"})[0], 200)
            before = open(st.path).read()
            self.assertEqual(hg.route(st, "POST", "/v1/widgets/item", {"id": "b", "type": "image", "data": {"url": "x"}})[0], 400)
            self.assertEqual(open(st.path).read(), before)
            self.assertEqual(os.stat(st.path).st_mode & 0o777, 0o600)


class BridgeHomeTest(unittest.TestCase):
    A = "agent-key-0123456789abcdef"

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.patches = [mock.patch.object(webos, "bridge_token", return_value="ABCD1234"),
                        mock.patch.object(webos, "bridge_agent_token", return_value=self.A),
                        mock.patch.object(webos, "_home_store", hg.Store(os.path.join(self.tmp.name, "w.json"))),
                        mock.patch.object(webos, "_home_shown", 0)]
        for p in self.patches:
            p.start()
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

    def test_agents_build_pages_and_the_tv_reads_them(self):
        A = self.A
        self.assertEqual(self.req("POST", "/v1/widgets/item", {"id": "a", "type": "stat"})[0], 401)
        self.assertEqual(self.req("POST", "/v1/widgets/item", {"id": "a", "type": "stat"}, token="ABCD1234")[0], 401)   # the TV is not an agent
        self.assertEqual(self.req("GET", "/v1/home", agent=A)[0], 401)   # agents do not read the TV's view
        st, r = self.req("POST", "/v1/widgets/item", {"id": "cpu", "type": "stat", "data": {"value": "12"}}, agent=A)
        self.assertEqual((st, r["widget"]["page"]), (200, 1))
        st, r = self.req("POST", "/v1/widgets/item", {"id": "scenes", "type": "homeassistant.scenes", "page": "casa",
                                                      "data": {"items": ["scene.movie"]}}, agent=A)
        self.assertEqual(r["widget"]["page"], 2)
        self.assertEqual(self.req("PATCH", "/v1/widgets/item/cpu", {"data": {"value": "99"}}, agent=A)[0], 200)
        self.assertEqual(self.req("PATCH", "/v1/widget-pages/casa", {"name": "Casa", "rotate": True}, agent=A)[0], 200)
        self.assertEqual(self.req("POST", "/v1/widget-pages/casa/show", {}, agent=A)[0], 200)
        st, v = self.req("GET", "/v1/home", token="ABCD1234")
        self.assertEqual(st, 200)
        self.assertEqual([w["id"] for w in v["widgets"]], ["cpu", "scenes"])
        self.assertEqual(v["widgets"][0]["data"]["value"], "99")
        self.assertEqual(v["pages"][1]["name"], "Casa")
        self.assertTrue(v["pages"][1]["rotate"])
        self.assertEqual(v["show"]["index"], 2)
        self.assertEqual(self.req("POST", "/v1/home/shown", {"index": 2}, token="ABCD1234")[0], 200)
        self.assertEqual(self.req("GET", "/v1/widget-pages/current", agent=A)[1]["index"], 2)
        st, r = self.req("DELETE", "/v1/widget-pages/casa", {"moveWidgetsTo": 1}, agent=A)
        self.assertEqual((st, r["movedWidgets"]), (200, 1))
        self.assertEqual(self.req("DELETE", "/v1/widgets/item/cpu", agent=A)[0], 200)
        self.assertEqual(self.req("DELETE", "/v1/widgets/item/cpu", agent=A)[0], 404)

    def test_the_mcp_bridge_client_reaches_the_same_routes(self):
        c = BridgeClient("127.0.0.1", self.srv.server_address[1], token=self.A)
        self.assertEqual(c.request("POST", "/v1/widgets/item", {"id": "t", "type": "text", "data": {"text": "hi"}})["widget"]["id"], "t")
        self.assertEqual(c.request("GET", "/v1/widget-pages")["total"], 1)
        self.assertEqual(c.request("GET", "/v1/widgets/types")["grid"]["cols"], 12)
