"""v2.21 agents on LG / Samsung through the PC bridge."""
import http.client
import json
import threading
import unittest
from http.server import HTTPServer
from unittest import mock

from omarchy_tv import agent_inbox, webos


class InboxTest(unittest.TestCase):
    def test_notes_page_asks(self):
        ib = agent_inbox.Inbox()
        ib.notify("build green", "CI", "warn", "Claude Code")
        ib.notify("second")
        s = ib.since(0)
        self.assertEqual([n["text"] for n in s["notes"]], ["build green", "second"])
        self.assertEqual(s["notes"][0]["level"], "warn")
        self.assertEqual(ib.since(s["seq"])["notes"], [])
        with self.assertRaises(ValueError):
            ib.set_page("https://example.com/")
        with self.assertRaises(ValueError):
            ib.set_page("http://127.0.0.1:8000/")
        ib.set_page("http://192.168.1.20:8080/", "Hermes")
        self.assertEqual(ib.since(0)["page"]["url"], "http://192.168.1.20:8080/")
        ib.set_page(None)
        self.assertIsNone(ib.since(0)["page"])
        a = ib.ask("Deploy?", ["Yes", "Not now", "Yes"], 60, "Claude")
        self.assertEqual(a["choices"], ["Yes", "Not now"])
        self.assertEqual(len(ib.since(0)["asks"]), 1)
        self.assertFalse(ib.answer(a["id"], "Maybe"))
        threading.Timer(0.2, lambda: ib.answer(a["id"], "Not now")).start()
        r = ib.get_ask(a["id"], wait=5)
        self.assertEqual((r["state"], r["answer"]), ("answered", "Not now"))
        self.assertEqual(ib.since(0)["asks"], [])

    def test_ask_limit_and_expiry(self):
        t = [1000.0]
        ib = agent_inbox.Inbox(now=lambda: t[0])
        ids = [ib.ask(f"q{i}", timeout=10)["id"] for i in range(4)]
        with self.assertRaises(OverflowError):
            ib.ask("one more")
        t[0] += 11
        self.assertEqual(ib.get_ask(ids[0])["state"], "expired")
        ib.ask("now there is room")


class BridgeRoutesTest(unittest.TestCase):
    def setUp(self):
        self.p = mock.patch.object(webos, "bridge_token", return_value="ABCD1234")
        self.p.start()
        self.pa = mock.patch.object(webos, "bridge_agent_token", return_value="agent-key-0123456789abcdef")
        self.pa.start()
        self.old = agent_inbox.INBOX
        agent_inbox.INBOX = agent_inbox.Inbox()
        self.srv = HTTPServer(("127.0.0.1", 0), webos.WebOSHandler)
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()

    def tearDown(self):
        self.srv.shutdown()
        self.srv.server_close()
        self.p.stop()
        self.pa.stop()
        agent_inbox.INBOX = self.old

    def req(self, method, path, body=None, token="ABCD1234", agent=None):
        """token: the TV's ask code (X-Omarchy-Token); agent: the agents' key (X-Omarchy-Agent)."""
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

    def test_the_tv_reads_its_inbox_and_answers(self):
        A = "agent-key-0123456789abcdef"
        self.assertEqual(self.req("POST", "/v1/notify", {"text": "hi"}, token=None)[0], 401)
        self.assertEqual(self.req("POST", "/v1/notify", {"text": "hi"})[0], 401)   # the TV's code is not an agent key
        self.assertEqual(self.req("GET", "/v1/inbox", token="WRONG")[0], 401)
        self.assertEqual(self.req("GET", "/v1/inbox", token=None, agent=A)[0], 401)   # agents do not read the TV's inbox
        self.assertEqual(self.req("POST", "/v1/notify", {"text": "backup done", "from": "Pi"}, token=None, agent=A)[0], 200)
        self.assertEqual(self.req("POST", "/v1/page", {"url": "https://evil.example/"}, token=None, agent=A)[0], 400)
        self.assertEqual(self.req("POST", "/v1/page", {"url": "http://192.168.1.5:8080/"}, token=None, agent=A)[0], 200)
        st, a = self.req("POST", "/v1/ask", {"question": "Merge?", "choices": ["Merge", "Wait"]}, token=None, agent=A)
        self.assertEqual(st, 200)
        st, box = self.req("GET", "/v1/inbox?since=0")
        self.assertEqual(box["notes"][0]["text"], "backup done")
        self.assertEqual(box["page"]["url"], "http://192.168.1.5:8080/")
        self.assertEqual(box["asks"][0]["question"], "Merge?")
        # v2.21 review: an agent cannot approve its own question; only the TV (ask code) answers
        self.assertEqual(self.req("POST", f"/v1/ask/{a['id']}", {"answer": "Merge"}, token=None, agent=A)[0], 401)
        self.assertEqual(self.req("POST", f"/v1/ask/{a['id']}", {"answer": "Merge"})[0], 200)
        st, r = self.req("GET", f"/v1/ask/{a['id']}?wait=1", token=None, agent=A)
        self.assertEqual(r["answer"], "Merge")
        self.assertEqual(self.req("POST", "/v1/page", {"close": True}, token=None, agent=A)[1]["url"], None)

    def test_peers_and_urls(self):
        self.assertTrue(agent_inbox.peer_ok("127.0.0.1"))
        self.assertTrue(agent_inbox.peer_ok("192.168.1.20"))
        self.assertTrue(agent_inbox.peer_ok("::ffff:10.0.0.4"))
        self.assertFalse(agent_inbox.peer_ok("8.8.8.8"))
        with mock.patch.object(agent_inbox, "tailnet_on", return_value=False):
            self.assertFalse(agent_inbox.peer_ok("100.101.102.103"))
            self.assertFalse(agent_inbox.lan_url("http://100.101.102.103/"))
            self.assertFalse(agent_inbox.lan_url("http://[fd7a:115c:a1e0::5]/"))
        with mock.patch.object(agent_inbox, "tailnet_on", return_value=True):
            self.assertTrue(agent_inbox.peer_ok("100.101.102.103"))
            self.assertTrue(agent_inbox.lan_url("http://100.101.102.103:8080/"))
            self.assertTrue(agent_inbox.lan_url("http://[fd7a:115c:a1e0::5]/"))
        self.assertFalse(agent_inbox.lan_url("http://169.254.1.1/"))
        self.assertFalse(agent_inbox.lan_url("http://[fe80::1]/"))
        self.assertTrue(agent_inbox.lan_url("http://[fd12:3456::1]/"))


class BridgeClientTest(BridgeRoutesTest):
    def test_the_mcp_tools_work_through_the_bridge(self):
        from omarchy_tv import bridge_client, mcp_server
        c = bridge_client.BridgeClient("127.0.0.1", self.srv.server_address[1], "agent-key-0123456789abcdef")
        r = mcp_server.handle_rpc({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                                   "params": {"name": "tv_notify", "arguments": {"text": "from MCP", "level": "warn"}}}, c)
        self.assertFalse(r["result"]["isError"])
        self.assertEqual(agent_inbox.INBOX.since(0)["notes"][0]["level"], "warn")
        r = mcp_server.handle_rpc({"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                                   "params": {"name": "tv_show_page", "arguments": {"url": "http://192.168.1.9/"}}}, c)
        self.assertFalse(r["result"]["isError"])
        mcp_server.handle_rpc({"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "tv_close_page", "arguments": {}}}, c)
        self.assertIsNone(agent_inbox.INBOX.since(0)["page"])
        r = mcp_server.handle_rpc({"jsonrpc": "2.0", "id": 4, "method": "tools/call",
                                   "params": {"name": "tv_send_key", "arguments": {"key": "home"}}}, c)
        self.assertTrue(r["result"]["isError"])   # Android TV only: a clear error, not a crash



class BridgeAndroidOnlyTest(unittest.TestCase):
    def test_android_only_tools_say_so(self):
        from omarchy_tv import bridge_client, mcp_server
        c = bridge_client.BridgeClient("127.0.0.1", 9, "k")
        for tool in ("tv_list_apps", "tv_send_key", "tv_launch_app"):
            with self.assertRaises(bridge_client.BridgeError) as e:
                mcp_server._call_tool(tool, {"key": "home", "package": "x"}, c)
            self.assertEqual(e.exception.code, "not_on_lg_samsung")
        with mock.patch.object(c, "request", return_value={"host": "pc", "theme": "tokyo-night"}) as r:
            self.assertEqual(mcp_server._call_tool("tv_status", {}, c)["host"], "pc")
            r.assert_called_once_with("GET", "/v1/status")

if __name__ == "__main__":
    unittest.main()
