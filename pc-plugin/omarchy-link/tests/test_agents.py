"""Live agent monitor + instructions from the TV + the theme prompt, with fixture sessions and the mock TV."""
import datetime as dt
import json
import os
import shutil
import sqlite3
import subprocess
import time
import unittest
from unittest.mock import patch
import tempfile

import helpers
from helpers import cli
from mock_tv import MockTV
from omarchy_link import agents, config
from omarchy_link.agents import ask as agent_ask
from omarchy_link.agents.redact import redact, short, tool_step

BIN = helpers.TMP / "agentbin"
LOG = helpers.TMP / "agent-cli.log"
PROJ = helpers.TMP / "work" / "shop"
SID = "7d0c6a2e-1111-4a2b-9c3d-5e6f7a8b9c0d"
SECRET = "sk-ant-api03-SUPERSECRETvalue1234567890"


def now(offset=0):
    return (dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=offset)).strftime("%Y-%m-%dT%H:%M:%S.%fZ")


def stub(name, body):
    BIN.mkdir(parents=True, exist_ok=True)
    p = BIN / name
    p.write_text(body)
    p.chmod(0o755)


def claude_file():
    d = helpers.HOME / ".claude/projects" / str(PROJ).replace("/", "-")
    d.mkdir(parents=True, exist_ok=True)
    return d / f"{SID}.jsonl"


def claude_session(finished=False, pending=True):
    c = str(PROJ)
    recs = [
        {"type": "user", "cwd": c, "sessionId": SID, "timestamp": now(-20),
         "message": {"role": "user", "content": "run the tests and fix the login bug"}},
        {"type": "assistant", "cwd": c, "timestamp": now(-18), "message": {"stop_reason": "tool_use", "content": [
            {"type": "thinking", "thinking": "secret plan"},
            {"type": "tool_use", "id": "t1", "name": "Bash",
             "input": {"command": f"export ANTHROPIC_API_KEY={SECRET} && npm test -- --token=abcdef123456"}}]}},
        {"type": "user", "cwd": c, "timestamp": now(-10), "message": {"role": "user", "content": [
            {"type": "tool_result", "tool_use_id": "t1", "is_error": True, "content": f"FAIL login.spec {SECRET}"}]}},
        {"type": "assistant", "cwd": c, "timestamp": now(-5), "message": {"stop_reason": "tool_use", "content": [
            {"type": "tool_use", "id": "t2", "name": "Read", "input": {"file_path": f"{c}/.env"}}]}},
    ]
    if not pending:
        recs.append({"type": "user", "cwd": c, "timestamp": now(-3), "message": {"content": [
            {"type": "tool_result", "tool_use_id": "t2", "content": "DB_PASSWORD=hunter2"}]}})
    if finished:
        recs.append({"type": "assistant", "cwd": c, "timestamp": now(-1), "message": {"stop_reason": "end_turn",
                     "content": [{"type": "text", "text": "Fixed."}]}})
    claude_file().write_text("\n".join(json.dumps(r) for r in recs) + "\n")


def on(**extra):
    return {"agentMonitor": {"enabled": True, **extra}}


class Redaction(unittest.TestCase):
    def test_redact(self):
        for s in (f"curl -H 'Authorization: Bearer {'x' * 30}' https://u:p@h.example/x?token=abc&q=1",
                  "OPENAI_API_KEY=sk-proj-abcdefghijklmnop", "git clone https://ghp_abcdefghijklmnopqrstuvwx@github.com/a/b",
                  "deploy --password hunter2 --api-key=k3y", "export FOO=bar", "aws AKIAABCDEFGHIJKLMNOP"):
            out = redact(s)
            for leak in ("x" * 30, "u:p@", "abc&", "sk-proj", "ghp_", "hunter2", "k3y", "=bar", "AKIAABCD"):
                self.assertNotIn(leak, out, (s, out))
        self.assertEqual(tool_step("Read", {"file_path": "/home/user/secret/plan.md"}), "Read: plan.md")
        self.assertEqual(tool_step("WebFetch", {"url": "https://docs.x.ai/a?key=1"}), "WebFetch: docs.x.ai")
        self.assertEqual(tool_step("mcp__linear__create_issue", {}), "mcp linear.create_issue")
        self.assertEqual(tool_step("Edit", {"file_path": "/a/b.py", "old_string": "x", "new_string": "SECRET"}), "Edit: b.py")
        self.assertTrue(len(short("y " * 200)) <= 72)


class Base(unittest.TestCase):
    def setUp(self):
        helpers.reset_pc_state()
        for d in (BIN, helpers.HOME / ".grokbot", helpers.HOME / ".hermes"):
            shutil.rmtree(d, ignore_errors=True)
        PROJ.mkdir(parents=True, exist_ok=True)
        LOG.unlink(missing_ok=True)
        self.old_path = os.environ["PATH"]
        os.environ["PATH"] = f"{BIN}:{self.old_path}"

    def tearDown(self):
        os.environ["PATH"] = self.old_path
        for d in (BIN, helpers.HOME / ".grokbot", helpers.HOME / ".hermes"):
            shutil.rmtree(d, ignore_errors=True)
        helpers.reset_pc_state()

    def one(self, cfg=None, agent=None):
        rows = [a for a in agents.snapshot(cfg or on())["agents"] if agent is None or a["agent"] == agent]
        self.assertEqual(len(rows), 1, rows)
        return rows[0]



class Monitor(Base):
    def test_t3_active_metadata_is_readonly_private_and_clears_finished_threads(self):
        with tempfile.TemporaryDirectory(dir=helpers.TMP) as root:
            from pathlib import Path
            from omarchy_link.agents import t3
            path = Path(root) / "userdata" / "statev2.sqlite"
            path.parent.mkdir()
            db = sqlite3.connect(path)
            db.executescript("""
                CREATE TABLE projection_projects(project_id TEXT PRIMARY KEY, workspace_root TEXT);
                CREATE TABLE orchestration_v2_projection_threads(thread_id TEXT PRIMARY KEY, project_id TEXT,
                    title TEXT, archived_at TEXT, deleted_at TEXT);
                CREATE TABLE orchestration_v2_projection_runs(thread_id TEXT, status TEXT, requested_at TEXT);
                CREATE TABLE orchestration_v2_projection_runtime_requests(thread_id TEXT, created_at TEXT,
                    status TEXT, resolved_at TEXT, kind TEXT);
                CREATE TABLE messages(secret TEXT);
                INSERT INTO messages VALUES('PRIVATE CHAT CONTENT');
            """)
            db.execute("INSERT INTO projection_projects VALUES('p',?)", (str(PROJ),))
            db.executemany("INSERT INTO orchestration_v2_projection_threads VALUES(?,?,?,?,?)", [
                (SID, 'p', 'Fix /home/private/code.py --token=' + SECRET, None, None),
                ('finished', 'p', 'Finished private title', None, None),
                ('archived', 'p', 'Archived private title', now(), None)])
            db.executemany("INSERT INTO orchestration_v2_projection_runs VALUES(?,?,?)", [
                (SID, 'running', now(-20)), (SID, 'queued', now()),
                ('finished', 'completed', now()), ('archived', 'running', now())])
            db.commit()
            original = sqlite3.connect
            def readonly(*args, **kwargs):
                self.assertIn('mode=ro', args[0])
                connection = original(*args, **kwargs)
                connection.set_authorizer(lambda op, table, column, *rest:
                    sqlite3.SQLITE_DENY if op == sqlite3.SQLITE_READ and table == 'messages' else sqlite3.SQLITE_OK)
                return connection
            with patch.dict(os.environ, T3CODE_HOME=root), patch.object(t3.time, 'monotonic', return_value=0) as clock, \
                    patch.object(t3.sqlite3, 'connect', side_effect=readonly):
                a = self.one(on(agents=['t3-code']))
                self.assertEqual((a['status'], a['project'], a['canAsk']), ('running', 'shop', False))
                public = json.dumps(agents.public(agents.snapshot(on(agents=['t3-code']))))
                for private in [SID, str(PROJ), SECRET, '/home/private', 'PRIVATE CHAT CONTENT', 'Finished private title', 'Archived private title']:
                    self.assertNotIn(private, public)
                opaque = a['id']
                db.execute('INSERT INTO orchestration_v2_projection_runtime_requests VALUES(?,?,?,?,?)',
                           (SID, now(), 'pending', None, 'user_input'))
                db.commit(); clock.return_value += 6
                a = self.one(on(agents=['t3-code']))
                self.assertEqual((a['status'], a['id'], a['needsInput']), ('waiting', opaque, True))
                db.execute("UPDATE orchestration_v2_projection_runtime_requests SET status='resolved'")
                db.execute("UPDATE orchestration_v2_projection_runs SET status='completed' WHERE thread_id=?", (SID,))
                db.commit(); clock.return_value += 6
                self.assertEqual(self.one(on(agents=['t3-code']))['status'], 'idle')
                db.execute("UPDATE orchestration_v2_projection_runs SET status='running' WHERE thread_id=?", (SID,))
                db.commit(); clock.return_value += 6
                self.assertEqual(self.one(on(agents=['t3-code']))['status'], 'running')
                old = time.time() - 121
                os.utime(path, (old, old)); clock.return_value += 6
                self.assertEqual(self.one(on(agents=['t3-code']))['status'], 'error')
                db.execute('DROP TABLE orchestration_v2_projection_runs'); db.commit(); clock.return_value += 6
                self.assertEqual(self.one(on(agents=['t3-code']))['status'], 'error')
            db.close()

    def test_off_by_default(self):
        claude_session()
        snap = agents.snapshot(config.load_config())
        self.assertEqual((snap["enabled"], snap["agents"]), (False, []))
        self.assertIn("off", cli("agents").stdout)

    def test_claude_running_waiting_done_and_privacy(self):
        claude_session()
        a = self.one()
        self.assertEqual((a["agentName"], a["project"], a["status"], a["step"]), ("Claude", "shop", "running", "Read: .env"))
        self.assertEqual([s["text"] for s in a["steps"]][:2], ["prompt", "Bash: export ANTHROPIC_API_KEY=••• && npm test -- --token=•••"])
        self.assertEqual(a["steps"][1]["status"], "error")
        pub = json.dumps(agents.public(agents.snapshot(on())))
        for leak in (SECRET, "hunter2", "secret plan", "FAIL login", str(PROJ), SID, "abcdef123456"):
            self.assertNotIn(leak, pub)
        old = time.time() - 60  # a tool call that has not returned for a minute: a permission prompt
        os.utime(claude_file(), (old, old))
        a = self.one()
        self.assertEqual((a["status"], a["needsInput"], a["question"]), ("waiting", True, "Allow Read: .env?"))
        claude_session(finished=True, pending=False)
        a = self.one()
        self.assertEqual((a["status"], a["needsInput"]), ("done", False))
        self.assertTrue(all(s["status"] in ("ok", "error") for s in a["steps"]))

    def test_claude_hooks(self):
        claude_session(finished=True, pending=False)
        payload = {"hook_event_name": "Notification", "session_id": SID, "cwd": str(PROJ),
                   "message": f"Claude needs your permission to use Bash with {SECRET}"}
        cli("agents", "hook", input_text=json.dumps(payload))
        a = self.one()
        self.assertEqual((a["status"], a["needsInput"]), ("waiting", True))
        self.assertTrue(a["question"].startswith("Claude needs your permission to use Bash"))
        self.assertNotIn(SECRET, a["question"])
        self.assertEqual(cli("agents", "hook", input_text="not json").returncode, 0)  # never breaks Claude
        settings = helpers.HOME / ".claude/settings.json"
        settings.write_text(json.dumps({"model": "opus", "hooks": {"Stop": [{"hooks": [{"type": "command", "command": "say done"}]}]}}))
        cli("agents", "hooks", "install")
        cli("agents", "hooks", "install")  # idempotent
        h = json.loads(settings.read_text())["hooks"]
        self.assertEqual(len(h["Stop"]), 2)
        self.assertEqual(sum("agents hook" in json.dumps(x) for x in h["PreToolUse"]), 1)
        cli("agents", "hooks", "remove")
        self.assertEqual(json.loads(settings.read_text()),
                         {"model": "opus", "hooks": {"Stop": [{"hooks": [{"type": "command", "command": "say done"}]}]}})

    def test_codex(self):
        d = helpers.HOME / ".codex/sessions/2026/10/19"
        d.mkdir(parents=True)
        f = d / "rollout-2026-10-19T10-00-00-0199aaaa-bbbb-7ccc-8ddd-eeeeffff0000.jsonl"
        recs = [{"timestamp": now(-30), "type": "session_meta", "payload": {"id": "0199aaaa-bbbb-7ccc-8ddd-eeeeffff0000", "cwd": "/home/user/work/api"}},
                {"timestamp": now(-20), "type": "event_msg", "payload": {"type": "user_message", "message": "deploy it"}},
                {"timestamp": now(-15), "type": "response_item", "payload": {"type": "function_call", "name": "shell", "call_id": "c1",
                 "arguments": json.dumps({"command": ["bash", "-lc", "git push https://ghp_abcdefghijklmnopqrstuvwxyz12@github.com/x"]})}},
                {"timestamp": now(-14), "type": "event_msg", "payload": {"type": "exec_approval_request", "call_id": "c1",
                 "command": ["bash", "-lc", "git push https://ghp_abcdefghijklmnopqrstuvwxyz12@github.com/x"]}}]
        f.write_text("\n".join(map(json.dumps, recs)) + "\n")
        a = self.one(agent="codex")
        self.assertEqual((a["project"], a["status"], a["needsInput"]), ("api", "waiting", True))
        self.assertIn("git push", a["question"])
        self.assertNotIn("ghp_", json.dumps(a))
        with open(f, "a") as fh:
            fh.write(json.dumps({"timestamp": now(-2), "type": "response_item", "payload": {"type": "function_call_output", "call_id": "c1",
                                 "output": json.dumps({"output": "ok", "metadata": {"exit_code": 0}})}}) + "\n")
            fh.write(json.dumps({"timestamp": now(-1), "type": "event_msg", "payload": {"type": "task_complete"}}) + "\n")
        a = self.one(agent="codex")
        self.assertEqual((a["status"], a["steps"][-1]["status"]), ("done", "ok"))

    def test_grok_hermes_grokbot_and_filters(self):
        g = helpers.HOME / ".grok/sessions/%2Fhome%2Fuser%2Fnotes/0199bbbb-0000-7000-8000-000000000001"
        g.mkdir(parents=True)
        (g / "summary.json").write_text(json.dumps({"title": "Tidy notes"}))
        (g / "updates.jsonl").write_text("\n".join(json.dumps(x) for x in [
            {"timestamp": now(-9), "update": {"sessionUpdate": "user_message_chunk", "content": {"text": "tidy"}}},
            {"timestamp": now(-5), "update": {"sessionUpdate": "tool_call", "toolCallId": "g1", "title": "Run tests",
                                              "kind": "execute", "status": "pending", "rawInput": {"command": "pytest -q"}}}]) + "\n")
        (helpers.HOME / ".grok/active_sessions.json").write_text(json.dumps({g.name: {"pid": os.getpid()}}))
        a = self.one(agent="grok")
        self.assertEqual((a["project"], a["status"], a["step"], a["title"]), ("notes", "running", "execute: pytest -q", "Tidy notes"))
        hdir = helpers.HOME / ".hermes"
        hdir.mkdir()
        con = sqlite3.connect(hdir / "state.db")
        con.executescript("""CREATE TABLE sessions (id TEXT PRIMARY KEY, source TEXT, title TEXT, started_at REAL,
                             ended_at REAL, end_reason TEXT, cwd TEXT);
                             CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT, role TEXT,
                             content TEXT, tool_call_id TEXT, tool_calls TEXT, tool_name TEXT, timestamp REAL, finish_reason TEXT);""")
        t = time.time()
        con.execute("INSERT INTO sessions VALUES ('20261019_100000_ab12', 'cli', 'blog', ?, NULL, NULL, '/home/user/blog')", (t - 30,))
        con.execute("INSERT INTO messages (session_id, role, content, timestamp) VALUES ('20261019_100000_ab12', 'user', 'publish', ?)", (t - 20,))
        con.execute("INSERT INTO messages (session_id, role, tool_calls, timestamp) VALUES ('20261019_100000_ab12', 'assistant', ?, ?)",
                    (json.dumps([{"id": "h1", "function": {"name": "terminal", "arguments": json.dumps({"command": "hugo deploy"})}}]), t - 5))
        con.commit()
        con.close()
        a = self.one(agent="hermes")
        self.assertEqual((a["project"], a["status"], a["step"], a["canAsk"]), ("blog", "running", "terminal: hugo deploy", True))
        (helpers.HOME / ".grokbot").mkdir()
        (helpers.HOME / ".grokbot/local-exec-daemon.json").write_text(json.dumps({"pid": os.getpid(), "inflightCount": 2}))
        (helpers.HOME / ".grokbot/local-exec-daemon-connection.json").write_text('{"token": "NEVER-READ"}')
        a = self.one(agent="grok-bot")
        self.assertEqual((a["status"], a["step"], a["canAsk"]), ("running", "2 local tool calls", False))
        self.assertNotIn("NEVER-READ", json.dumps(agents.snapshot(on())))
        only = agents.snapshot(on(agents=["hermes", "grok"], hideProjects=["notes"]))["agents"]
        self.assertEqual([x["agent"] for x in only], ["hermes"])


class OverLink(Base):
    def start(self, conf, agents_on_tv=True):
        helpers.HOME.joinpath(".config/omarchy-link").mkdir(parents=True, exist_ok=True)
        config.CONFIG_FILE.write_text(json.dumps(conf))
        tv = MockTV(helpers.TMP / f"tv-{self.id().rsplit('.', 1)[-1]}", code="123456").start()
        self.addCleanup(tv.close)
        if agents_on_tv and not hasattr(tv, "wait_event"):
            self.skipTest("this TV harness has no agent events yet")
        cli("pair", f"127.0.0.1:{tv.port}", "--code", tv.code)
        sess = subprocess.Popen([helpers.CLI, "session"], stdin=subprocess.PIPE, stdout=subprocess.DEVNULL,
                                stderr=subprocess.DEVNULL, text=True)

        def stop():
            sess.stdin.close()
            sess.wait(5)
        self.addCleanup(stop)
        return tv

    def test_activity_events_snapshot_and_throttle(self):
        claude_session()
        tv = self.start(on())
        d = tv.wait_event("agent_activity", lambda d: d.get("agents"))
        self.assertIsNotNone(d)
        a = d["agents"][0]
        self.assertEqual((a["id"], a["status"], a["step"]), (f"claude-code:{SID[:12]}", "running", "Read: .env"))
        for k in ("session", "cwd", "live"):
            self.assertNotIn(k, a)
        for _ in range(100):
            if tv.config().get("link", {}).get("agents"):
                break
            time.sleep(0.1)
        self.assertEqual(tv.config()["link"]["agents"]["agents"][0]["id"], a["id"])  # PUT /v1/agents, GET returns it
        start, t0 = len(tv.seen), time.time()
        for i in range(12):  # the agent writes a lot: the TV still gets at most 2 events per second
            with open(claude_file(), "a") as fh:
                fh.write(json.dumps({"type": "assistant", "cwd": str(PROJ), "timestamp": now(), "message": {
                    "stop_reason": "tool_use", "content": [{"type": "tool_use", "id": f"x{i}", "name": "Grep",
                                                            "input": {"pattern": f"step{i}"}}]}}) + "\n")
            time.sleep(0.25)
        time.sleep(1.2)
        n = sum(1 for m in tv.seen[start:] if m["type"] == "agent_activity")
        self.assertLessEqual(n, int((time.time() - t0) * 2) + 1)
        self.assertGreaterEqual(n, 2)
        tv.broadcast("agent_snapshot", {})
        self.assertIsNotNone(tv.wait_event("agent_activity", lambda d: d.get("agents"), after=len(tv.seen)))

    def test_ask_rules_and_claude_resume(self):
        claude_session(finished=True, pending=False)
        stub("claude", f"""#!/usr/bin/env python3
import json, os, sys
open({str(LOG)!r}, "a").write(json.dumps({{"argv": sys.argv[1:], "cwd": os.getcwd(), "stdin": sys.stdin.read()}}) + "\\n")
print("Done: login fixed. key {SECRET}")
""")
        tv = self.start(on())
        aid = f"claude-code:{SID[:12]}"
        self.assertIsNotNone(tv.wait_event("agent_activity", lambda d: d.get("agents")))
        r = tv.agent_ask(aid, "now run the e2e tests")
        self.assertEqual(r[-1][0], "agent_ask_error")
        self.assertEqual(r[-1][1]["error"], "disabled")  # agentControlFromTv defaults to false
        self.assertFalse(LOG.exists())
        config.CONFIG_FILE.write_text(json.dumps({**on(), "agentControlFromTv": True, "agentConfirm": False}))
        time.sleep(1.2)
        r = tv.agent_ask(aid, "now run the e2e tests")
        self.assertEqual([k for k, _ in r], ["agent_ask_state", "agent_answer"], r)
        self.assertEqual(r[0][1]["state"], "running")
        self.assertTrue(r[1][1]["text"].startswith("Done: login fixed."))
        self.assertNotIn(SECRET, r[1][1]["text"])
        call = json.loads(LOG.read_text().splitlines()[-1])
        self.assertEqual(call["argv"], ["-p", "--resume", SID, "--output-format", "text", "--permission-mode", "default"])
        self.assertEqual((call["cwd"], call["stdin"]), (str(PROJ), "now run the e2e tests"))
        for bad, code in (("nope:1", "not_found"), (aid, "bad_request")):
            r = tv.agent_ask(bad, "" if bad == aid else "hi")
            self.assertEqual(r[-1][1]["error"], code)
        claude_session(pending=True)  # working right now: no second writer
        time.sleep(1.2)
        self.assertEqual(tv.agent_ask(aid, "hi")[-1][1]["error"], "busy")
        old = time.time() - 60
        os.utime(claude_file(), (old, old))  # waiting on a terminal prompt
        self.assertEqual(tv.agent_ask(aid, "yes")[-1][1]["error"], "unsupported")
        (helpers.HOME / ".grokbot").mkdir()
        (helpers.HOME / ".grokbot/local-exec-daemon.json").write_text(json.dumps({"pid": os.getpid()}))
        r = tv.agent_ask("grok-bot:local", "hello")
        self.assertEqual((r[-1][1]["error"], "no local input API" in r[-1][1]["message"]), ("unsupported", True))

    def test_ask_needs_pc_confirmation(self):
        claude_session(finished=True, pending=False)
        stub("claude", "#!/bin/sh\ncat >/dev/null\necho ok\n")
        stub("omarchy-notification-send", f"#!/bin/sh\necho \"$@\" >> {LOG}\n")
        tv = self.start({**on(), "agentControlFromTv": True})
        aid = f"claude-code:{SID[:12]}"
        self.assertIsNotNone(tv.wait_event("agent_activity", lambda d: d.get("agents")))
        rid_box = {}

        def approve_soon():
            for _ in range(100):
                if LOG.exists() and "agents approve" in LOG.read_text():
                    tok = LOG.read_text().split("agents approve ")[1].split()[0]
                    rid_box["token"] = tok
                    self.assertIn("TV asks Claude (shop): deploy please", LOG.read_text())
                    cli("agents", "approve", tok)
                    return
                time.sleep(0.1)
        import threading
        th = threading.Thread(target=approve_soon)
        th.start()
        r = tv.agent_ask(aid, "deploy please")
        th.join()
        self.assertEqual([k for k, _ in r], ["agent_ask_state", "agent_ask_state", "agent_answer"], r)
        self.assertEqual(r[0][1]["state"], "awaiting_approval")
        self.assertEqual(r[-1][1]["text"], "ok")
        self.assertEqual(cli("agents", "approve", rid_box["token"], check=False).returncode, 1)  # one-shot

    def test_theme_prompt(self):
        tv = self.start({}, agents_on_tv=False)
        helpers.set_theme("tokyo-night", helpers.TOKYO)
        cli("sync-theme")
        self.assertNotIn("prompt", tv.config())
        self.assertEqual(cli("prompt", "set", "User!", check=False).returncode, 1)
        self.assertEqual(cli("prompt", "set", "a" * 17, check=False).returncode, 1)
        cli("prompt", "set", "user", "omarchy-pc", "--now")
        self.assertEqual(tv.config()["prompt"], {"user": "user", "host": "omarchy-pc"})
        cli("prompt", "set", "user.p")  # changes the fingerprint: the next sync resends
        cli("sync-theme")
        self.assertEqual(tv.config()["prompt"], {"user": "user.p", "host": "omarchy-pc"})
        cli("prompt", "clear")
        self.assertEqual(json.loads(cli("prompt", "show").stdout), {})


if __name__ == "__main__":
    unittest.main()
