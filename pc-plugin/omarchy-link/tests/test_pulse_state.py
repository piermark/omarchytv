"""2.15 Pulse on the TV: what the PC's Pulse state looks like once it leaves for the TV."""
import json
import os
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "lib"))
from omarchy_link import pulse_state  # noqa: E402

STATE = {"v": "1.6.0", "t": 1.0, "agents": [{"name": "claude · omarchytv", "state": "working"}],
         "widgets": [{"id": "github", "type": "list", "data": {"items": []}},
                     {"id": "notifications", "type": "list", "data": {"items": [{"title": "Mail", "text": "secret"}]}},
                     {"id": "bank", "type": "stat", "private": True, "data": {"value": 1}},
                     {"id": "cam-gate", "type": "image", "data": {"src": "/img/cam-gate"}}],
         "calendar": [{"title": "doctor", "start": 1, "end": 2}], "lan": [{"ip": "10.0.0.5", "name": "nas"}],
         "term": ["$ ssh prod"], "heart": 70, "listen": {"t": 1}, "sceneTicket": "x", "sky": [{"flight": "AZ1"}]}


class ForTv(unittest.TestCase):
    def test_private_parts_never_leave_the_pc(self):
        o = pulse_state.for_tv(json.loads(json.dumps(STATE)))
        self.assertEqual([w["id"] for w in o["widgets"]], ["github"])
        self.assertEqual(o["calendar"], [{"title": "", "start": 1, "end": 2}])
        self.assertEqual(o["lan"], [])
        self.assertEqual(o["term"], [])
        self.assertIsNone(o["heart"])
        self.assertNotIn("sceneTicket", o)
        self.assertEqual(o["agents"], STATE["agents"])   # what the TV is for: agents, planes...
        self.assertEqual(o["sky"], STATE["sky"])
        self.assertNotIn("secret", json.dumps(o))
        self.assertEqual(pulse_state.for_tv({}), {})
        self.assertEqual(pulse_state.for_tv(None), {})


class Read(unittest.TestCase):
    def test_reads_the_local_hub_with_its_token_and_is_quiet_when_off(self):
        seen = []

        class H(BaseHTTPRequestHandler):
            def do_GET(self):   # noqa: N802
                seen.append(self.path)
                b = json.dumps(STATE).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(b)))
                self.end_headers()
                self.wfile.write(b)

            def log_message(self, *a):
                pass

        srv = HTTPServer(("127.0.0.1", 0), H)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "omarchy-pulse").mkdir()
            (Path(d) / "omarchy-pulse" / "token").write_text("tok123\n")
            with mock.patch.dict(os.environ, {"XDG_CONFIG_HOME": d, "PULSE_PORT": str(srv.server_address[1])}):
                st = pulse_state.read()
                self.assertEqual(seen, ["/api/state?token=tok123"])
                self.assertEqual([w["id"] for w in st["widgets"]], ["github"])
            srv.shutdown()
            with mock.patch.dict(os.environ, {"XDG_CONFIG_HOME": d, "PULSE_PORT": "1"}):
                self.assertIsNone(pulse_state.read(timeout=0.5))   # Pulse not running
        with tempfile.TemporaryDirectory() as d, mock.patch.dict(os.environ, {"XDG_CONFIG_HOME": d}):
            self.assertIsNone(pulse_state.read())   # Pulse never installed: no token


if __name__ == "__main__":
    unittest.main()
