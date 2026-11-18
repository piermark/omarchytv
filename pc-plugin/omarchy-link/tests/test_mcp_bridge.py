"""v2.21 omarchy-link mcp: stdio <-> the TV's /v1/mcp, with a local scope filter."""
import io
import json
import unittest

import helpers  # noqa: F401
from omarchy_link import mcp_bridge


class FakeClient:
    def __init__(self, fail=False):
        self.sent = []
        self.fail = fail

    def request(self, method, path, payload=None, timeout=8.0):
        if self.fail:
            raise OSError("no route to host")
        self.sent.append((method, path, payload, timeout))
        if payload["method"] == "tools/list":
            return {"jsonrpc": "2.0", "id": payload["id"], "result": {"tools": [{"name": "tv_status"}, {"name": "tv_notify"}, {"name": "tv_send_key"}]}}
        return {"jsonrpc": "2.0", "id": payload["id"], "result": {"content": [{"type": "text", "text": "{}"}], "isError": False}}


class BridgeTest(unittest.TestCase):
    def run_lines(self, lines, scope="control", client=None):
        c = client or FakeClient()
        out = io.StringIO()
        mcp_bridge.serve(lambda: c, scope, io.StringIO("\n".join(json.dumps(x) for x in lines) + "\n"), out)
        return c, [json.loads(l) for l in out.getvalue().splitlines()]

    def test_forwards_to_the_tv_signed_route(self):
        c, out = self.run_lines([{"jsonrpc": "2.0", "id": 1, "method": "initialize"},
                                 {"jsonrpc": "2.0", "method": "notifications/initialized"},
                                 {"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "tv_status"}}])
        self.assertEqual([s[1] for s in c.sent], ["/v1/mcp", "/v1/mcp"])
        self.assertEqual(c.sent[1][3], 130.0)
        self.assertEqual([o["id"] for o in out], [1, 2])

    def test_scope_filters_before_the_tv(self):
        c, out = self.run_lines([{"jsonrpc": "2.0", "id": 1, "method": "tools/list"},
                                 {"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "tv_send_key"}}], "notify")
        self.assertEqual({t["name"] for t in out[0]["result"]["tools"]}, {"tv_status", "tv_notify"})
        self.assertIn("Not allowed", out[1]["error"]["message"])
        self.assertEqual(len(c.sent), 1)   # the refused call never left the PC

    def test_tv_away_is_an_mcp_error(self):
        _, out = self.run_lines([{"jsonrpc": "2.0", "id": 7, "method": "tools/list"}], client=FakeClient(fail=True))
        self.assertEqual(out[0]["error"]["code"], -32000)


if __name__ == "__main__":
    unittest.main()
