"""v2.21 extras: Omarchy hook pack, spoken reminders, rooms, approve on the TV, couch console, TV anywhere."""
import datetime as dt
import json
import os
import subprocess
import time
import unittest
import urllib.request
from types import SimpleNamespace
from unittest import mock

import helpers  # noqa: F401  (hermetic env + sys.path)
from omarchy_link import cli, config, console, hookpack, mcp_bridge, panes, remind, rooms
from omarchy_link.agents import ask as agent_ask, permission


class HookPack(unittest.TestCase):
    def test_add_list_remove_and_the_scripts_run_quietly(self):
        fake = helpers.TMP / "fake-omarchy-link"
        log = helpers.TMP / "fake.log"
        fake.write_text(f"#!/bin/sh\necho \"$@\" >> {log}\n")
        os.chmod(fake, 0o755)
        self.assertEqual(hookpack.parse([]), ("theme-set",))
        self.assertEqual(hookpack.parse(["all"]), hookpack.EVENTS)
        with self.assertRaises(ValueError):
            hookpack.parse(["rm-rf"])
        files = hookpack.add(hookpack.EVENTS, str(fake))
        self.assertEqual(sorted(hookpack.installed()), sorted(hookpack.EVENTS))
        for f in files:
            self.assertTrue(os.access(f, os.X_OK))
        bat = hookpack.hooks_dir() / "battery-low.d" / hookpack.NAME
        r = subprocess.run(["bash", str(bat), "9;rm -rf /"], timeout=10)   # what omarchy-hook does, hostile arg
        self.assertEqual(r.returncode, 0)
        for _ in range(50):
            if log.exists() and "Battery" in log.read_text():
                break
            time.sleep(0.1)
        line = log.read_text()
        self.assertIn("--speak", line)
        self.assertIn("Battery at 9%", line)   # digits only reach the message
        self.assertNotIn("rm", line)
        hookpack.remove()
        self.assertEqual(hookpack.installed(), [])


class Remind(unittest.TestCase):
    def test_times(self):
        self.assertEqual(remind.seconds_until("90s"), 90)
        self.assertEqual(remind.seconds_until("1h30m"), 5400)
        self.assertEqual(remind.seconds_until("45"), 2700)
        now = dt.datetime(2026, 11, 19, 22, 0)
        self.assertEqual(remind.seconds_until("22:30", now), 1800)
        self.assertEqual(remind.seconds_until("21:00", now), 23 * 3600)   # tomorrow
        for bad in ("", "soon", "25:00", "0s", "8d", "1x"):
            with self.assertRaises(remind.RemindError):
                remind.seconds_until(bad)

    def test_schedule_uses_a_user_timer(self):
        calls = []
        run = lambda c, **k: calls.append(c) or SimpleNamespace(returncode=0, stderr="")
        r = remind.schedule("/x/omarchy-link", "10m", "Pizza\x07 in the oven", run=run, which=lambda b: "/usr/bin/" + b)
        c = calls[0]
        self.assertEqual(c[:3], ["systemd-run", "--user", "--quiet"])
        self.assertIn("--on-active=600", c)
        self.assertEqual(c[-2:], ["--", "Pizza in the oven"])
        self.assertIn("--speak", c)
        self.assertEqual(r["in"], 600)
        with self.assertRaises(remind.RemindError):
            remind.schedule("/x", "10m", "hi", run=run, which=lambda b: None)
        with self.assertRaises(remind.RemindError):
            remind.cancel("../etc")


class Rooms(unittest.TestCase):
    def setUp(self):
        f = config.STATE_DIR / "rooms.json"
        if f.exists():
            f.unlink()

    def test_post_keeps_five_lines_three_rooms_and_rides_with_the_panes(self):
        for i in range(7):
            rooms.post("hermes", f"report {i}", "cron", now=1000 + i)
        self.assertEqual([m["text"] for m in rooms.load()["hermes"]["msgs"]], [f"report {i}" for i in range(2, 7)])
        for r in ("a", "b", "c"):
            rooms.post(r, "hi", now=2000)
        self.assertEqual(len(rooms.load()), 3)
        self.assertNotIn("hermes", rooms.load())   # the oldest made way
        with self.assertRaises(ValueError):
            rooms.post("../x", "hi")
        ids = [p["id"] for p in panes.load_widgets()["panes"]]
        self.assertTrue({"room-a", "room-b", "room-c"} <= set(ids) or not ids)   # old ones expire after a day
        rooms.post("hermes", "fresh")
        w = panes.home_widgets({"pcPane": False})
        p = [x for x in w["panes"] if x["id"] == "room-hermes"][0]
        self.assertEqual(p["title"], "hermes · room")
        self.assertEqual(p["lines"][0]["value"], "fresh")

    def test_mcp_tools_listed_and_answered_on_this_pc(self):
        client = mock.Mock()
        client.request.return_value = {"jsonrpc": "2.0", "id": 1, "result": {"tools": [{"name": "tv_notify"}]}}
        names = [t["name"] for t in mcp_bridge.handle({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}, client, "notify")["result"]["tools"]]
        self.assertIn("tv_room_post", names)
        self.assertIn("tv_remind", names)
        self.assertNotIn("tv_room_post", [t["name"] for t in mcp_bridge.handle(
            {"jsonrpc": "2.0", "id": 1, "method": "tools/list"}, client, "read")["result"]["tools"]])
        r = mcp_bridge.handle({"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                               "params": {"name": "tv_room_post", "arguments": {"room": "hermes", "text": "done"}}}, client)
        self.assertFalse(r["result"].get("isError"))
        client.put_widgets.assert_called_once()
        r = mcp_bridge.handle({"jsonrpc": "2.0", "id": 3, "method": "tools/call",
                               "params": {"name": "tv_room_post", "arguments": {"room": "BAD/x", "text": "x"}}}, client)
        self.assertTrue(r["result"]["isError"])


class Approve(unittest.TestCase):
    PAYLOAD = {"hook_event_name": "PermissionRequest", "tool_name": "Bash", "cwd": "/home/me/shop",
               "tool_input": {"command": "git push origin main"}}

    def test_answers(self):
        asked = []
        ask = lambda q, ch, t, say="": asked.append((q, ch, say)) or "Allow"
        out = permission.decide(self.PAYLOAD, ask)
        self.assertEqual(out["hookSpecificOutput"], {"hookEventName": "PermissionRequest", "decision": {"behavior": "allow"}})
        self.assertIn("shop wants Bash: git push origin main", asked[0][0])
        self.assertEqual(asked[0][2], "Claude Code, project shop wants to use Bash: git push origin main")   # v2.22
        self.assertEqual(permission.decide(self.PAYLOAD, lambda *a: "Deny")["hookSpecificOutput"]["decision"]["behavior"], "deny")
        self.assertIsNone(permission.decide(self.PAYLOAD, lambda *a: "On the PC"))   # Claude Code's own prompt
        self.assertIsNone(permission.decide(self.PAYLOAD, lambda *a: None))          # no answer in time
        def away(*a):
            raise OSError("TV off")
        self.assertIsNone(permission.decide(self.PAYLOAD, away))                      # never allow by itself
        self.assertIsNone(permission.decide({"hook_event_name": "PreToolUse"}, lambda *a: "Allow"))

    def test_spoken_request_has_the_whole_command(self):   # v2.22 approvals on --speak
        long = "rsync -av " + " ".join(f"dir{i}/" for i in range(30)) + " backup:/srv"
        p = dict(self.PAYLOAD, tool_input={"command": long})
        self.assertLessEqual(len(permission.describe(p)), 200)
        self.assertIn("…", permission.describe(p))
        said = permission.spoken(p)
        self.assertIn("dir20/", said)
        self.assertLessEqual(len(said), 300)
        sent = []

        class C:
            def request(self, m, path, body=None, timeout=0):
                if m == "POST":
                    sent.append(body)
                    return {"id": "a1"}
                return {"state": "answered", "answer": "Deny"}
        self.assertEqual(permission.decide(p, permission.tv_ask(C(), speak=True))["hookSpecificOutput"]["decision"]["behavior"], "deny")
        self.assertTrue(sent[0]["speak"])
        self.assertEqual(sent[0]["say"], said)
        permission.decide(p, permission.tv_ask(C()))
        self.assertNotIn("speak", sent[1])

    def test_install_and_remove_keep_other_hooks(self):
        f = agent_ask.claude_settings()
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(json.dumps({"model": "x", "hooks": {"PermissionRequest": [{"matcher": "Bash", "hooks": [{"type": "command", "command": "mine"}]}]}}))
        permission.install("/x/my tools/omarchy-link")
        permission.install("/x/my tools/omarchy-link")   # twice: still one entry, the backup is the user's file
        self.assertNotIn("agents permission", f.with_suffix(".json.omarchy-link.bak").read_text())
        d = json.loads(f.read_text())
        self.assertEqual(len(d["hooks"]["PermissionRequest"]), 2)
        self.assertIn("agents permission", json.dumps(d["hooks"]["PermissionRequest"][1]))
        self.assertGreater(d["hooks"]["PermissionRequest"][1]["hooks"][0]["timeout"], permission.WAIT)
        self.assertTrue(d["hooks"]["PermissionRequest"][1]["hooks"][0]["command"].startswith("'/x/my tools/omarchy-link'"))
        permission.install("/x/my tools/omarchy-link", speak=True)   # v2.22: replaces the entry, read aloud
        d = json.loads(f.read_text())
        self.assertEqual(len(d["hooks"]["PermissionRequest"]), 2)
        self.assertTrue(d["hooks"]["PermissionRequest"][1]["hooks"][0]["command"].endswith("agents permission --speak"))
        self.assertTrue(permission.remove())
        d = json.loads(f.read_text())
        self.assertEqual(d["hooks"]["PermissionRequest"][0]["hooks"][0]["command"], "mine")
        self.assertEqual(d["model"], "x")
        f.unlink()


class Console(unittest.TestCase):
    def test_strip_and_read_only_server(self):
        self.assertEqual(console.strip("\x1b[1;32mok\x1b[0m\x07 done"), "ok done")
        srv, url = console.serve("127.0.0.1", lambda: "line1\n\x1b[31mline2\x1b[0m\n", "test")
        try:
            page = urllib.request.urlopen(url, timeout=5).read().decode()
            self.assertIn("read-only", page)
            data = json.loads(urllib.request.urlopen(url + "text", timeout=5).read())
            self.assertEqual(data["text"], "line1\nline2")
            with self.assertRaises(urllib.error.HTTPError):
                urllib.request.urlopen(url.rsplit("/", 2)[0] + "/guess/text", timeout=5)
            req = urllib.request.Request(url + "text", data=b"x", method="POST")
            with self.assertRaises(urllib.error.HTTPError):   # no way to type into the PC
                urllib.request.urlopen(req, timeout=5)
        finally:
            srv.shutdown()
        srv, url = console.serve("127.0.0.1", lambda: "x", "test", allow={"192.168.1.5"})
        try:
            with self.assertRaises(urllib.error.HTTPError) as e:   # not the TV: refused even with the path
                urllib.request.urlopen(url + "text", timeout=5)
            self.assertEqual(e.exception.code, 403)
        finally:
            srv.shutdown()

    def test_remind_text_may_look_like_an_option(self):
        with mock.patch.object(remind, "schedule", return_value={"id": "abc123", "at": "10:00:00", "text": "--level"}) as s:
            self.assertEqual(cli.main(["remind", "10m", "--level"]), 0)
            self.assertEqual(s.call_args[0][2], "--level")
            cli.main(["remind", "10m", "--", "--level", "now"])
            self.assertEqual(s.call_args[0][2], "--level now")


class HomeMotion(unittest.TestCase):
    def test_tv_home_says_what_really_moves(self):
        m = cli.home_motion
        self.assertEqual(m({"intro": "light", "loop": True}), "looping (light 540p copy)")
        self.assertEqual(m({"intro": "full", "loop": True}), "looping")
        self.assertEqual(m({"intro": "full", "loop": False}), "plays once, then the still")
        self.assertIn("weak TV", m({"intro": "skip", "loop": True}))
        self.assertIn("no animation", m({"intro": "none", "loop": True}))
        self.assertIn("could not keep up", m({"intro": "light", "loop": True, "loopStutter": True}))


class Anywhere(unittest.TestCase):
    def test_remote_setup_snippets(self):
        with mock.patch.object(cli, "_call", return_value={"network": {"tailnet": "100.101.7.3"}, "settings": {"tailscale": False}}):
            t = cli.remote_setup({}, "otva_abc")
        self.assertIn("http://100.101.7.3:47800/v1/notify", t)
        self.assertIn("claude mcp add --transport http omarchytv http://100.101.7.3:47800/v1/mcp", t)
        self.assertIn("Bearer otva_abc", t)
        self.assertIn("turn on the TV's switch", t)
        with mock.patch.object(cli, "_call", return_value={"network": {"tailnet": "fd7a:115c:a1e0::5"}, "settings": {"tailscale": True}}):
            self.assertIn("http://[fd7a:115c:a1e0::5]:47800", cli.remote_setup({}, "t"))


if __name__ == "__main__":
    unittest.main()
