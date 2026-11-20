"""v2.22 omarchy-link forget-tv: the TV is leaving, nothing stays authed."""
import json
import unittest

import helpers
from helpers import HOME, cli
from omarchy_link import decommission


class Strip(unittest.TestCase):
    def test_claude_hooks_keep_the_users_own(self):
        s = {"model": "x", "hooks": {
            "Stop": [{"hooks": [{"type": "command", "command": "omarchy-link notify -q done"},
                                {"type": "command", "command": "my-own-thing"}]}],
            "PermissionRequest": [{"matcher": "*", "hooks": [{"type": "command", "command": "'/opt/tv link' agents permission --speak"}]}],
            "PreToolUse": [{"matcher": "*", "hooks": [{"type": "command", "command": "/x/otl agents hook"}]}]}}
        out, n = decommission.strip_claude_hooks(json.dumps(s))
        self.assertEqual(n, 3)
        d = json.loads(out)
        self.assertEqual(d["hooks"], {"Stop": [{"hooks": [{"type": "command", "command": "my-own-thing"}]}]})
        self.assertEqual(d["model"], "x")
        self.assertEqual(decommission.strip_claude_hooks('{"model": "x"}')[1], 0)

    def test_codex(self):
        t = ('model = "o3"\nnotify = ["omarchy-link", "notify", "-q", "--from", "Codex"]\n\n'
             '[mcp_servers.omarchytv]\ncommand = "omarchy-link"\nargs = ["mcp"]\n\n'
             '[mcp_servers.omarchytv.env]\nX = "1"\n\n[mcp_servers.other]\ncommand = "other"\n')
        out, n = decommission.strip_codex(t)
        self.assertEqual(n, 3)
        self.assertNotIn("omarchy", out)
        self.assertIn('[mcp_servers.other]\ncommand = "other"', out)
        self.assertIn('model = "o3"', out)

    def test_hermes(self):
        t = ("model: x\nmcp_servers:\n  other:\n    command: other\n  omarchytv:\n    command: \"omarchy-link\"\n"
             "    args: [\"mcp\"]\n  third:\n    command: t\nskills: []\n")
        out, n = decommission.strip_hermes(t)
        self.assertEqual(n, 1)
        self.assertEqual(out, "model: x\nmcp_servers:\n  other:\n    command: other\n  third:\n    command: t\nskills: []\n")


class ForgetTv(unittest.TestCase):
    def setUp(self):
        from mock_tv import MockTV
        helpers.reset_pc_state()
        helpers.set_theme("tokyo-night", helpers.TOKYO)
        self.tv = MockTV(helpers.TMP / "tv-forget", code="123456").start()
        cli("pair", f"127.0.0.1:{self.tv.port}", "--code", "123456")

    def tearDown(self):
        self.tv.close()

    def test_forget_tv_revokes_unpairs_and_cleans(self):
        cli("token", "add", "nas", "--scope", "notify", "--expire-days", "30")
        cli("token", "add", "clock", "--scope", "read", "--expire-days", "0")
        self.assertIn("never expires", cli("token", "add", "wall", "--expire-days", "0").stdout)
        settings = HOME / ".claude/settings.json"
        settings.parent.mkdir(parents=True, exist_ok=True)
        settings.write_text(json.dumps({"hooks": {"Stop": [{"hooks": [{"type": "command", "command": "omarchy-link notify -q done"}]}]}}))
        codex = HOME / ".codex/config.toml"
        codex.parent.mkdir(parents=True, exist_ok=True)
        codex.write_text('[mcp_servers.omarchytv]\ncommand = "omarchy-link"\n')
        tokfile = HOME / ".config/omarchy-tv/mcp-token"
        tokfile.parent.mkdir(parents=True, exist_ok=True)
        tokfile.write_text("secret")
        dry = cli("forget-tv", "--dry-run").stdout
        self.assertIn("revoke every app token", dry)
        self.assertIn("Codex", dry)
        self.assertEqual(len(self.tv.app_tokens), 3)   # dry run changed nothing
        self.assertEqual(cli("forget-tv", check=False, input_text="").returncode, 2)   # no tty, no --yes
        out = cli("forget-tv", "--yes").stdout
        self.assertIn("3 app token(s) revoked", out)
        self.assertIn("unpaired", out)
        self.assertIn("Tailscale admin console", out)
        self.assertEqual(self.tv.app_tokens, [])
        self.assertNotIn("omarchy-link", settings.read_text())
        self.assertTrue((HOME / ".claude/settings.json.omarchy-link.bak").is_file())
        self.assertNotIn("omarchytv", codex.read_text())
        self.assertFalse(tokfile.exists())
        self.assertIn("Nothing to forget", cli("forget-tv", "--yes").stdout)


if __name__ == "__main__":
    unittest.main()
