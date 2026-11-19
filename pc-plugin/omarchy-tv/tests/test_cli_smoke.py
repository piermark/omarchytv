"""v2.21 the omarchy-tv command itself, end to end: no path may crash with a traceback (review of b734b33)."""
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

BIN = Path(__file__).resolve().parents[1] / "bin" / "omarchy-tv"


def run(args, stdin="", timeout=30):
    home = tempfile.mkdtemp()
    env = dict(os.environ, HOME=home, XDG_CONFIG_HOME=os.path.join(home, ".config"), XDG_STATE_HOME=os.path.join(home, ".state"),
               PATH="/usr/bin:/bin")
    return subprocess.run([sys.executable, str(BIN)] + args, input=stdin, capture_output=True, text=True, timeout=timeout, env=env)


class CliSmokeTest(unittest.TestCase):
    def assertNoTraceback(self, r):
        self.assertNotIn("Traceback", r.stderr + r.stdout, r.stderr[-2000:])
        self.assertNotIn("cannot access local variable", r.stderr + r.stdout)

    def test_mcp_stdio_works_before_pairing(self):
        lines = [{"jsonrpc": "2.0", "id": 1, "method": "initialize"},
                 {"jsonrpc": "2.0", "method": "notifications/initialized"},
                 {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
                 {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "tv_status", "arguments": {}}}]
        for extra in ([], ["--bridge"], ["--scope", "notify"]):
            r = run(["mcp", "--stdio"] + extra, "\n".join(json.dumps(x) for x in lines) + "\n")
            self.assertNoTraceback(r)
            out = [json.loads(l) for l in r.stdout.splitlines()]   # stdout carries JSON only
            self.assertEqual([o["id"] for o in out], [1, 2, 3])
            self.assertTrue(out[1]["result"]["tools"])

    def test_commands_that_need_the_tv_fail_cleanly(self):
        for args in (["ls"], ["plugins", "list"], ["office", "list"], ["send", "/nonexistent"]):
            r = run(args)
            self.assertNoTraceback(r)

    def test_mcp_flag_mistakes_are_explained(self):
        for args in (["mcp", "--tailscale", "--tunnel"], ["mcp", "--cloudflare", "x", "--access-team", "t"],
                     ["mcp", "--scope", "everything"], ["mcp", "--tailscale", "--cloudflare"], ["mcp", "--cloudflare"],
                     ["mcp", "--http", "--port"], ["mcp", "--http", "--port", "abc"],
                     ["mcp", "--cloudflare", "--access-team", "t", "--access-aud", "a"]):
            r = run(args)
            self.assertNoTraceback(r)
            self.assertEqual(r.returncode, 2, args)


if __name__ == "__main__":
    unittest.main()
