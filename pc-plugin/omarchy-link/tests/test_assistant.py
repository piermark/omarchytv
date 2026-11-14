"""Assistant relay and key-free credits: fake signed-in CLIs, fake API/ollama servers, mock TV."""
import json
import os
import shutil
import subprocess
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import helpers
from helpers import cli
from mock_tv import MockTV
from omarchy_link import ai, assistant, clis, config

FAKEBIN = helpers.TMP / "fakebin"
LOG = helpers.TMP / "fake-cli.log"

FAKE_CLAUDE = r'''#!/usr/bin/env python3
import json, os, sys
a = sys.argv[1:]
if a[:2] == ["-p", "/usage"]:
    print("Current session: 23% used · resets 3:45pm (Europe/Rome)")
    print("Current week (all models): 41% used · resets Oct 23 at 11pm (Europe/Rome)")
    print("")
    print("What's contributing to your limits usage?")
    sys.exit(0)
prompt = a[a.index("-p") + 1]
with open(os.environ["FAKE_LOG"], "a") as f:
    f.write(json.dumps({"cli": "claude", "argv": a, "cwd": os.getcwd(), "files": os.listdir(".")}) + "\n")
reply = "You asked: " + prompt.splitlines()[-1]
if "Conversation so far" in prompt:
    reply += " (remembering)"
for chunk in (reply[:10], reply[10:]):
    print(json.dumps({"type": "stream_event", "event": {"type": "content_block_delta",
                      "delta": {"type": "text_delta", "text": chunk}}}), flush=True)
print(json.dumps({"type": "result", "result": reply, "is_error": False}))
'''
FAKE_CODEX = r'''#!/usr/bin/env python3
import json, sys
a = sys.argv[1:]
if a[:2] == ["login", "status"]:
    print("Logged in using ChatGPT"); sys.exit(0)
assert a[0] == "exec" and "read-only" in a and "mcp_servers={}" in a
print(json.dumps({"type": "thread.started", "thread_id": "t"}))
print(json.dumps({"type": "item.completed", "item": {"id": "i", "type": "agent_message", "text": "**Codex** says hi"}}))
print(json.dumps({"type": "turn.completed", "usage": {}}))
'''
FAKE_GROK = r'''#!/usr/bin/env python3
import json, sys
assert "-p" in sys.argv and "--max-turns" in sys.argv
print(json.dumps({"result": "Grok here", "session_id": "x"}))
'''


def _write_bin(name, body):
    FAKEBIN.mkdir(parents=True, exist_ok=True)
    p = FAKEBIN / name
    p.write_text(body)
    p.chmod(0o755)


class FakeAPI(BaseHTTPRequestHandler):
    seen = []

    def log_message(self, *a):
        pass

    def do_GET(self):
        if self.path == "/api/tags":
            return self._send(200, b'{"models":[{"name":"llama-test"}]}', "application/json")
        self._send(404, b"{}", "application/json")

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        FakeAPI.seen.append((self.path, {k.lower(): v for k, v in self.headers.items()}, body))
        if self.path == "/v1/chat/completions":
            chunks = [{"choices": [{"delta": {"content": "Hi from "}}]}, {"choices": [{"delta": {"content": "compat"}}]}]
            data = "".join(f"data: {json.dumps(c)}\n\n" for c in chunks) + "data: [DONE]\n\n"
            return self._send(200, data.encode(), "text/event-stream")
        if self.path == "/v1/messages":
            evs = [{"type": "message_start"}, {"type": "content_block_delta", "delta": {"type": "text_delta", "text": "Hi from "}},
                   {"type": "content_block_delta", "delta": {"type": "text_delta", "text": "claude api"}}, {"type": "message_stop"}]
            data = "".join(f"event: {e['type']}\ndata: {json.dumps(e)}\n\n" for e in evs)
            return self._send(200, data.encode(), "text/event-stream")
        if self.path == "/api/chat":
            lines = [{"message": {"content": "Local "}}, {"message": {"content": "llama"}}, {"done": True}]
            return self._send(200, "".join(json.dumps(l) + "\n" for l in lines).encode(), "application/x-ndjson")
        self._send(404, b"{}", "application/json")

    def _send(self, code, data, ctype):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


class Base(unittest.TestCase):
    def setUp(self):
        helpers.reset_pc_state()
        shutil.rmtree(FAKEBIN, ignore_errors=True)
        shutil.rmtree(helpers.HOME / ".claude", ignore_errors=True)
        shutil.rmtree(helpers.HOME / ".grok", ignore_errors=True)
        shutil.rmtree(helpers.HOME / ".codex", ignore_errors=True)
        FAKEBIN.mkdir(parents=True)
        self.old_env = dict(os.environ)
        os.environ["PATH"] = f"{FAKEBIN}:{os.environ['PATH']}"
        os.environ["FAKE_LOG"] = str(LOG)
        os.environ["OLLAMA_HOST"] = "127.0.0.1:9"  # nothing listens: no ollama unless a test sets it
        LOG.unlink(missing_ok=True)
        clis.clear_cache()

    def tearDown(self):
        os.environ.clear()
        os.environ.update(self.old_env)
        shutil.rmtree(FAKEBIN, ignore_errors=True)
        helpers.reset_pc_state()  # no signed-in CLIs / Claude logs left for later modules

    def signed_in_clis(self):
        _write_bin("claude", FAKE_CLAUDE)
        _write_bin("codex", FAKE_CODEX)
        _write_bin("grok", FAKE_GROK)
        (helpers.HOME / ".claude").mkdir(parents=True, exist_ok=True)
        (helpers.HOME / ".claude/.credentials.json").write_text("{}")
        (helpers.HOME / ".grok").mkdir(parents=True, exist_ok=True)
        (helpers.HOME / ".grok/auth.json").write_text("{}")

    def settings(self, data):
        config.write_json_private(assistant.ASSISTANT_FILE, {"minIntervalSeconds": 0, **data})

    def server(self):
        srv = ThreadingHTTPServer(("127.0.0.1", 0), FakeAPI)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        self.addCleanup(srv.shutdown)
        FakeAPI.seen = []
        return f"http://127.0.0.1:{srv.server_address[1]}"


class AssistantUnit(Base):
    def test_detects_signed_in_clis_first_then_api(self):
        self.signed_in_clis()
        _write_bin("gemini", "#!/bin/sh\nexit 1\n")  # installed but never signed in -> hidden
        os.environ["TEST_XAI"] = "k"
        self.settings({"providers": [{"type": "xai_api", "id": "grok-api", "key": "env:TEST_XAI"}]})
        lst = assistant.Assistant().listing()
        ids = [p["id"] for p in lst["providers"]]
        self.assertEqual(ids, ["claude-code", "codex", "grok-cli", "grok-api"])
        self.assertEqual(lst["default"], "claude-code")
        self.assertEqual([p["kind"] for p in lst["providers"]], ["cli", "cli", "cli", "api"])
        self.assertNotIn("TEST_XAI", json.dumps(lst))
        self.assertTrue(all(set(p) == {"id", "name", "kind", "model", "streaming", "provider"} for p in lst["providers"]))

    def test_ask_streams_remembers_and_runs_cli_safely(self):
        self.signed_in_clis()
        self.settings({})
        a = assistant.Assistant()
        deltas = []
        r1 = a.ask("what time is it in Rome", on_delta=deltas.append)
        self.assertEqual(r1["provider"], "claude-code")
        self.assertEqual("".join(deltas), "You asked: what time is it in Rome")
        r2 = a.ask("and in Tokyo", conversation=r1["conversationId"])
        self.assertIn("(remembering)", r2["text"])
        r3 = a.ask("and in Tokyo")  # a new conversation has no memory
        self.assertNotIn("remembering", r3["text"])
        call = json.loads(LOG.read_text().splitlines()[0])
        argv = call["argv"]
        self.assertEqual(argv[argv.index("--tools") + 1], "")
        self.assertIn("--strict-mcp-config", argv)
        self.assertIn("three short sentences", argv[argv.index("--system-prompt") + 1])
        self.assertEqual(call["files"], [])  # empty private working dir
        self.assertIn("omarchy-link-ask-", call["cwd"])

    def test_override_unknown_and_cleanup(self):
        self.signed_in_clis()
        self.settings({"default": "grok-cli"})
        a = assistant.Assistant()
        self.assertEqual(a.ask("hi")["text"], "Grok here")
        self.assertEqual(a.ask("hi", provider="codex")["text"], "Codex says hi")  # markdown stripped
        with self.assertRaises(assistant.NoProvider):
            a.ask("hi", provider="grok-bot")
        with self.assertRaises(ValueError):
            a.ask("x" * 2001)
        with self.assertRaises(ValueError):
            a.ask("hi", conversation="../../etc")

    def test_rate_limit(self):
        self.signed_in_clis()
        config.write_json_private(assistant.ASSISTANT_FILE, {})
        a = assistant.Assistant()
        a.ask("one")
        with self.assertRaises(assistant.RateLimited):
            a.ask("two")

    def test_no_provider(self):
        self.settings({})
        with self.assertRaises(assistant.NoProvider):
            assistant.Assistant().ask("hi")

    def test_api_fallbacks_and_ollama(self):
        base = self.server()
        os.environ["TEST_KEY"] = "sk-test"
        self.settings({"providers": [
            {"type": "openai_compatible", "id": "lmstudio", "baseUrl": base + "/v1", "model": "m", "key": "env:TEST_KEY"},
            {"type": "anthropic_api", "id": "claude-api", "baseUrl": base, "key": "env:TEST_KEY"}]})
        a = assistant.Assistant()
        deltas = []
        self.assertEqual(a.ask("hi", provider="lmstudio", on_delta=deltas.append)["text"], "Hi from compat")
        self.assertEqual(deltas, ["Hi from ", "compat"])
        self.assertEqual(a.ask("hi", provider="claude-api")["text"], "Hi from claude api")
        path, headers, body = FakeAPI.seen[0]
        self.assertEqual(headers["authorization"], "Bearer sk-test")
        self.assertEqual(body["messages"][0]["role"], "system")
        path, headers, body = FakeAPI.seen[1]
        self.assertEqual(headers["x-api-key"], "sk-test")
        self.assertIn("three short sentences", body["system"])
        os.environ["OLLAMA_HOST"] = base
        clis.clear_cache()
        lst = a.listing()
        self.assertEqual(lst["providers"][0]["id"], "ollama")  # local before api
        self.assertEqual(lst["providers"][0]["model"], "llama-test")
        self.assertEqual(a.ask("hi", provider="ollama")["text"], "Local llama")

    def test_hermes_and_grok_bot_providers(self):
        _write_bin("hermes", (
            "#!/usr/bin/env python3\nimport sys\n"
            "a = sys.argv[1:]\n"
            "assert a[:2] == ['chat', '-q'] and '--oneshot' in a and '--quiet' in a, a\n"
            "model = a[a.index('-m') + 1] if '-m' in a else ''\n"
            "print('Hermes says hi' + (' (' + model + ')' if model else ''))\n"
            "print()\n"
            "print('session_id: 20261028_abc123')\n"
        ))
        _write_bin("grok-bot", "#!/usr/bin/env python3\nimport json, sys\nassert '-p' in sys.argv\nprint(json.dumps({'result': 'Grok Bot says hi'}))\n")
        (helpers.HOME / ".hermes").mkdir(parents=True, exist_ok=True)
        (helpers.HOME / ".hermes/auth.json").write_text("{}")
        (helpers.HOME / ".grok-bot").mkdir(parents=True, exist_ok=True)
        (helpers.HOME / ".grok-bot/auth.json").write_text("{}")
        clis.clear_cache()
        self.settings({})
        a = assistant.Assistant()
        ids = [p["id"] for p in a.listing()["providers"]]
        self.assertIn("hermes-cli", ids)
        self.assertIn("grok-bot", ids)
        res = a.ask("hi", provider="hermes-cli")
        self.assertEqual(res["text"], "Hermes says hi")
        self.assertNotIn("session_id", res["text"])
        self.assertEqual(a.ask("hi", provider="grok-bot")["text"], "Grok Bot says hi")
        # Non-streaming CLI providers also print their final text in `omarchy-link ai ask`
        out = cli("ai", "ask", "--provider", "hermes-cli", "hi").stdout
        self.assertIn("Hermes says hi", out)
        self.assertNotIn("session_id", out)
        self.assertIn("[hermes-cli]", out)
        # Streaming providers print deltas once without duplicating final text
        self.signed_in_clis()
        clis.clear_cache()
        out_stream = cli("ai", "ask", "--provider", "claude-code", "hello").stdout
        self.assertEqual(out_stream.count("You asked: hello"), 1)

    def test_plain_http_only_on_lan(self):
        assistant.check_url("https://api.x.ai/v1")
        assistant.check_url("http://127.0.0.1:1234/v1")
        with self.assertRaises(ai.ProviderError):
            assistant.check_url("http://8.8.8.8/v1")
        with self.assertRaises(ai.ProviderError):
            assistant.check_url("file:///etc/passwd")


class CreditsWithoutKeys(Base):
    def test_claude_usage_and_codex_logs_autodetected(self):
        self.signed_in_clis()
        day = helpers.HOME / ".codex/sessions/2026/10/18"
        day.mkdir(parents=True)
        future = int(time.time()) + 3600
        ev = {"timestamp": "2026-10-18T20:00:00Z", "type": "event_msg", "payload": {"type": "token_count", "rate_limits": {
            "primary": {"used_percent": 37.5, "window_minutes": 299, "resets_at": future},
            "secondary": {"used_percent": 12.0, "window_minutes": 10079, "resets_at": 1000}}}}
        (day / "rollout-a.jsonl").write_text(json.dumps(ev) + "\n" + json.dumps(
            {"type": "event_msg", "payload": {"type": "token_count", "rate_limits": None}}) + "\n")
        res = {e["name"]: e for e in ai.collect({"providers": []}, helpers.TMP / "absent.json")["ai"]}
        self.assertEqual(res["Claude session"]["used"], 23.0)
        self.assertEqual(res["Claude session"]["unit"], "%")
        self.assertTrue(res["Claude week"]["resetsAt"].endswith("Z"))
        self.assertEqual(res["Codex 5h"]["used"], 37.5)
        self.assertEqual(res["Codex week"]["used"], 0.0)  # its reset time has passed
        self.assertTrue(all(e["status"] == "ok" for e in res.values()))

    def test_claude_local_logs_fallback(self):
        proj = helpers.HOME / ".claude/projects/p"
        proj.mkdir(parents=True)
        now = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        line = {"timestamp": now, "requestId": "r1", "message": {"id": "m1", "usage": {"input_tokens": 100, "output_tokens": 50}}}
        (proj / "s.jsonl").write_text(json.dumps(line) + "\n" + json.dumps(line) + "\n")  # duplicate counted once
        res = ai.collect({"providers": [{"type": "claude_code"}], "autoDetect": False}, helpers.TMP / "absent.json")["ai"]
        self.assertEqual(res[0]["used"], 150)
        self.assertEqual(res[0]["unit"], "tokens")


class AssistantOverLink(Base):
    def test_tv_asks_pc_answers(self):
        self.signed_in_clis()
        self.settings({})
        tvdir = helpers.TMP / "tv-assistant"
        tv = MockTV(tvdir, code="123456").start()
        self.addCleanup(tv.close)
        helpers.set_theme("tokyo-night", helpers.TOKYO)
        cli("pair", f"127.0.0.1:{tv.port}", "--code", tv.code)
        sess = subprocess.Popen([helpers.CLI, "session"], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, text=True)
        try:
            conf = lambda: json.loads((tvdir / "files/omarchytv.json").read_text())
            for _ in range(100):
                if tv.clients and conf().get("link", {}).get("aiProviders") and conf().get("ai"):
                    break
                time.sleep(0.1)
            provs = conf()["link"]["aiProviders"]
            self.assertEqual(provs[0]["id"], "claude-code")
            self.assertIn("ai-ask", tv.status()["capabilities"])
            self.assertIn("Claude session", [e["name"] for e in conf()["ai"]])  # top-level "ai"
            self.assertNotIn("ai", conf()["link"])
            r = tv.ask("ciao, che ore sono", lang="it-IT")
            self.assertIn("final", r, r)
            self.assertEqual("".join(r["chunks"]), "You asked: ciao, che ore sono")
            self.assertTrue(r["final"]["done"])
            self.assertEqual(r["final"]["final"], "You asked: ciao, che ore sono")
            r2 = tv.ask("e a Tokyo", session=r["final"]["session"], provider="claude-code")
            self.assertIn("(remembering)", r2["final"]["final"])
            r3 = tv.ask("hi", provider="codex", session="s1")
            self.assertEqual(r3["final"]["text"], "Codex says hi")  # not streamed: whole text in the done message
            err = tv.ask("hi", provider="grok-bot")
            self.assertEqual(err["error"]["error"], "no_provider")
        finally:
            sess.stdin.close()
            sess.wait(5)


if __name__ == "__main__":
    unittest.main()
