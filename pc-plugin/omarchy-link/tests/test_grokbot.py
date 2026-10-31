"""Bridge to @tcballard's Grok Bot Desktop Companion with a mocked IPC: a fake `omarchy-shell` on PATH
logs every call, and a fake checkout (manifest.json + bin/grokbot-ask + data/bots.json) answers on stdout."""
import json
import os
import shutil
import subprocess
import unittest

import helpers
from helpers import cli
from mock_tv import MockTV
from omarchy_link import config, grokbot

BIN = helpers.TMP / "gbbin"
LOG = helpers.TMP / "omarchy-shell.log"
CHECKOUT = helpers.HOME / ".local/share/omarchy/plugins" / grokbot.COMPANION_ID
OMA = helpers.HOME / ".config/omarchy"
BOTS = {"bots": [
    {"id": "e0195a7d-f804-4688-9191-e2cf3574a6d1", "name": "Bob", "shortName": "Bob", "avatarShape": "teardrop",
     "avatarColor": "orange", "isPrimary": True},
    {"id": "11111111-2222-3333-4444-555555555555", "name": "OmaBot", "shortName": "OmaBot", "avatarShape": "blob",
     "avatarColor": "green"},
    {"id": "66666666-7777-8888-9999-000000000000", "name": "Inbox Admin", "shortName": "Inbox", "avatarShape": "tablet",
     "avatarColor": "cyan"},
]}

FAKE_SHELL = f"""#!/bin/sh
printf '%s\\n' "$*" >> {LOG}
case "$2" in
  ping|bot|say|sayLink) echo ok ;;
  ask) if [ -f {helpers.TMP}/gb-busy ]; then echo busy; else echo ok; fi ;;
  *) echo error ;;
esac
"""

# Stands in for his bin/grokbot-ask: reads the JSON request, answers on stdout. Records what it got
# (and whether it saw a key it must not see) so the tests can check the contract.
FAKE_HELPER = f"""#!/usr/bin/python3
import json, os, sys
req = json.loads(sys.stdin.read())
open({str(helpers.TMP / 'helper-req.json')!r}, 'w').write(json.dumps({{"req": req, "path": os.environ.get("PATH"),
    "leak": os.environ.get("OMARCHY_TEST_SECRET")}}))
if req["prompt"] == "fail":
    print("webhook URL is missing or not https", file=sys.stderr); sys.exit(2)
if req["prompt"] == "later":
    print("Sent to " + req["botName"] + "."); sys.exit(10)
if req["prompt"] == "video":
    print("Here it is: https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42 enjoy"); sys.exit(0)
print(req["botName"] + " says: " + req["prompt"][::-1])
"""


def write(p, text, mode=0o644):
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text)
    p.chmod(mode)


class Base(unittest.TestCase):
    def setUp(self):
        helpers.reset_pc_state()
        for d in (BIN, CHECKOUT, OMA):
            shutil.rmtree(d, ignore_errors=True)
        for f in (LOG, helpers.TMP / "helper-req.json", helpers.TMP / "gb-busy"):
            f.unlink(missing_ok=True)
        self.old_path = os.environ["PATH"]
        os.environ["PATH"] = f"{BIN}:{self.old_path}"
        os.environ["OMARCHY_TEST_SECRET"] = "must-not-reach-the-helper"

    def tearDown(self):
        os.environ["PATH"] = self.old_path
        os.environ.pop("OMARCHY_TEST_SECRET", None)
        for d in (BIN, CHECKOUT, OMA):
            shutil.rmtree(d, ignore_errors=True)
        helpers.reset_pc_state()

    def ipc_on(self):
        write(BIN / "omarchy-shell", FAKE_SHELL, 0o755)

    def checkout(self):
        write(CHECKOUT / "manifest.json", json.dumps({"id": grokbot.COMPANION_ID, "version": "0.1.0"}))
        write(CHECKOUT / "bin/grokbot-ask", FAKE_HELPER, 0o755)
        write(CHECKOUT / "data/bots.json", json.dumps(BOTS))

    def settings(self, **entry):
        write(OMA / "shell.json", json.dumps({"plugins": [{"id": "other"}, {"id": grokbot.COMPANION_ID, **entry}]}))

    def calls(self):
        return LOG.read_text().splitlines() if LOG.exists() else []


class Detect(Base):
    def test_not_installed(self):
        self.assertFalse(grokbot.detect({})["installed"])
        self.assertIsNone(grokbot.current({}))
        with self.assertRaises(grokbot.GrokbotError) as e:
            grokbot.ask({}, "hi")
        self.assertEqual(e.exception.code, "not_installed")

    def test_default_bot_is_bob_from_settings_entry(self):
        self.settings(enabled=True)
        cur = grokbot.current({})
        self.assertEqual((cur["name"], cur["shape"], cur["color"], cur["fill"]), ("Bob", "drop", "orange", "#FF6700"))
        self.assertEqual(cur["mode"], "offline")
        self.assertIn("v0.1.0", cur["label"])

    def test_bot_from_roster_and_custom_appearance(self):
        self.checkout()
        self.settings(botId=BOTS["bots"][1]["id"])
        cur = grokbot.current({})
        self.assertEqual((cur["name"], cur["shape"], cur["color"], cur["mode"]), ("OmaBot", "bean", "green", "relay"))
        self.settings(botId=BOTS["bots"][2]["id"], syncAppearance=False, shape="hex", color="violet")
        cur = grokbot.current({})
        self.assertEqual((cur["shortName"], cur["shape"], cur["fill"]), ("Inbox", "hexagon", "#9159FE"))

    def test_user_roster_wins_and_bad_values_are_clamped(self):
        self.settings(botId="x1")
        write(OMA / "grokbot-bots.json", json.dumps({"bots": [{"id": "x1", "name": "Zed\x07\n", "avatarShape": "nope",
                                                               "avatarColor": "plaid"}]}))
        cur = grokbot.current({})
        self.assertEqual((cur["name"], cur["shape"], cur["color"]), ("Zed", "bean", "orange"))

    def test_configured_checkout_dir(self):
        alt = helpers.TMP / "elsewhere"
        shutil.rmtree(alt, ignore_errors=True)
        write(alt / "manifest.json", json.dumps({"id": grokbot.COMPANION_ID}))
        write(alt / "bin/grokbot-ask", FAKE_HELPER, 0o755)
        self.assertEqual(grokbot.detect({"grokbotDesktopDir": str(alt)})["checkout"], str(alt))
        write(alt / "manifest.json", json.dumps({"id": "someone.else"}))
        self.assertIsNone(grokbot.detect({"grokbotDesktopDir": str(alt)})["checkout"])

    def test_group_writable_helper_is_not_run(self):
        self.checkout()
        (CHECKOUT / "bin/grokbot-ask").chmod(0o777)
        self.assertFalse(grokbot.detect({})["relay"])


class Ask(Base):
    def test_relay_reply_comes_back_and_is_mirrored_in_the_bubble(self):
        self.checkout()
        self.ipc_on()
        self.settings()
        r = grokbot.ask({}, "hello\x1b there")
        self.assertEqual(r, {"bot": "Bob", "text": "Bob says: ereht olleh", "mode": "relay", "links": []})
        got = json.loads((helpers.TMP / "helper-req.json").read_text())
        self.assertEqual(got["req"]["botId"], BOTS["bots"][0]["id"])
        self.assertEqual(got["path"], "/usr/bin:/bin")
        self.assertIsNone(got["leak"])                     # cleared environment
        self.assertEqual(self.calls(), [f"{grokbot.COMPANION_ID} say Bob Bob says: ereht olleh"])

    def test_named_bot_switches_the_companion_first(self):
        self.checkout()
        self.ipc_on()
        r = grokbot.ask({}, "status?", "inbox")
        self.assertEqual(r["bot"], "Inbox")
        self.assertEqual(self.calls()[0], f"{grokbot.COMPANION_ID} bot {BOTS['bots'][2]['id']}")
        with self.assertRaises(grokbot.GrokbotError) as e:
            grokbot.ask({}, "hi", "Nobody")
        self.assertEqual(e.exception.code, "unknown_bot")

    def test_helper_failure_is_reported(self):
        self.checkout()
        with self.assertRaises(grokbot.GrokbotError) as e:
            grokbot.ask({}, "fail")
        self.assertEqual(e.exception.code, "ask_failed")
        self.assertIn("webhook URL", str(e.exception))

    def test_ipc_only_mode_desktop_reply(self):
        self.settings()
        self.ipc_on()
        r = grokbot.ask({}, "what's on my calendar?")
        self.assertEqual((r["mode"], r["text"]), ("ipc", ""))
        self.assertEqual(self.calls(), [f"{grokbot.COMPANION_ID} ask what's on my calendar?"])
        (helpers.TMP / "gb-busy").write_text("1")
        with self.assertRaises(grokbot.GrokbotError) as e:
            grokbot.ask({}, "again")
        self.assertEqual(e.exception.code, "busy")

    def test_empty_and_long(self):
        self.settings()
        self.ipc_on()
        with self.assertRaises(grokbot.GrokbotError):
            grokbot.ask({}, " \n ")
        grokbot.ask({}, "x" * 5000)
        self.assertEqual(len(self.calls()[0].split(" ask ", 1)[1]), grokbot.ASK_LIMIT)

    def test_shell_metacharacters_stay_one_argument(self):
        self.settings()
        self.ipc_on()
        grokbot.ask({}, "hi; touch /tmp/pwned-gb $(id)")
        self.assertFalse(os.path.exists("/tmp/pwned-gb"))
        self.assertTrue(self.calls()[0].endswith("ask hi; touch /tmp/pwned-gb $(id)"))


class V010(Base):
    """What his v0.1.0 release added: async webhook answers, links, dispatch settings, shapes, palette."""

    def test_exit_10_is_sent_not_failed(self):
        self.checkout()
        self.ipc_on()
        self.settings()
        r = grokbot.ask({}, "later")
        self.assertEqual(r["mode"], "sent")
        self.assertEqual(r["text"], "")
        self.assertEqual(r["note"], "Sent to Bob.")
        self.assertEqual(self.calls(), [])        # nothing mirrored: his `reply` brings the answer to the bubble

    def test_video_link_is_offered_and_mirrored_with_saylink(self):
        self.checkout()
        self.ipc_on()
        self.settings()
        r = grokbot.ask({}, "video")
        self.assertEqual(r["links"][0]["kind"], "youtube")
        self.assertEqual(r["links"][0]["videoId"], "dQw4w9WgXcQ")
        self.assertEqual(r["links"][0]["position"], 42.0)
        self.assertTrue(self.calls()[0].startswith(f"{grokbot.COMPANION_ID} sayLink Bob "))

    def test_links_only_http(self):
        got = grokbot.links("a javascript:alert(1) b file:///etc/passwd c https://ok.example/x.mp4, d http://a.b")
        self.assertEqual([l["url"] for l in got], ["https://ok.example/x.mp4", "http://a.b"])
        self.assertEqual(got[0]["kind"], "video")
        self.assertEqual(got[1]["kind"], "url")

    def test_dispatch_from_desktop_json_and_shell_entry_wins(self):
        self.checkout()
        self.assertEqual(grokbot.detect({})["dispatch"], "agent")
        write(OMA / "grokbot-desktop.json", json.dumps({"dispatch": "webhook", "webhookUrl": "https://x"}))
        self.assertEqual(grokbot.dispatch_mode(), "webhook")
        self.settings(dispatch="agent")
        self.assertEqual(grokbot.dispatch_mode(), "agent")

    def test_avatar_shape_and_colour_aliases(self):
        self.assertEqual(grokbot.avatar_shape("drop"), "teardrop")
        self.assertEqual(grokbot.avatar_shape("HEX"), "hex")
        self.assertEqual(grokbot.avatar_shape("nope"), "blob")
        self.assertEqual(len(grokbot.AVATAR_SHAPES), 14)
        self.assertEqual(grokbot.color_name("purple"), "violet")
        self.checkout()
        self.settings()
        self.assertEqual(grokbot.current({})["avatarShape"], "teardrop")

    def test_sync_roster_runs_his_helper(self):
        self.checkout()
        with self.assertRaises(grokbot.GrokbotError):
            grokbot.sync_roster({})
        write(CHECKOUT / "bin/grokbot-sync", "#!/usr/bin/python3\nprint('synced 1 bot')\n", 0o755)
        r = grokbot.sync_roster({})
        self.assertEqual(r["message"], "synced 1 bot")


class Version(Base):
    """The bridge targets his v0.1.0 release and still accepts older checkouts."""

    def test_reads_manifest_then_version_file(self):
        self.checkout()
        self.assertEqual(grokbot.detect({})["version"], "0.1.0")
        self.assertEqual(grokbot.detect({})["compat"], "target")
        write(CHECKOUT / "manifest.json", json.dumps({"id": grokbot.COMPANION_ID}))
        write(CHECKOUT / "VERSION", "0.1.0\n")
        self.assertEqual(grokbot.companion_version(CHECKOUT), "0.1.0")
        (CHECKOUT / "VERSION").unlink()
        info = grokbot.detect({})
        self.assertIsNone(info["version"])
        self.assertEqual(info["compat"], "branch")       # an older branch checkout without VERSION
        self.assertTrue(info["relay"])

    def test_compatibility_levels(self):
        c = grokbot.compatibility
        self.assertEqual(c("0.1.0"), "target")
        self.assertEqual(c("v0.1.3"), "target")
        self.assertEqual(c("0.0.9"), "branch")
        self.assertEqual(c("0.2.0"), "newer")
        self.assertEqual(c("1.0.0"), "unsupported")
        self.assertEqual(grokbot.UPSTREAM_TAG, "v0.1.0")
        self.assertEqual(grokbot.UPSTREAM_REF, "v0.1.0")

    def test_new_major_disables_the_helper_relay(self):
        self.checkout()
        self.ipc_on()
        write(CHECKOUT / "manifest.json", json.dumps({"id": grokbot.COMPANION_ID, "version": "1.0.0"}))
        info = grokbot.detect({})
        self.assertEqual(info["compat"], "unsupported")
        self.assertFalse(info["relay"])
        self.assertEqual(grokbot.current({})["mode"], "ipc")

    def test_real_checkout_reports_target(self):
        src = os.environ.get("GROKBOT_DESKTOP_SRC", "/tmp/gb")
        if not os.path.isfile(os.path.join(src, "manifest.json")):
            self.skipTest("no local checkout of tcballard/omarchy-plugin-grokbot-desktop")
        from pathlib import Path
        v = grokbot.companion_version(Path(src))
        self.assertEqual(grokbot.compatibility(v), "target", v)


class UpstreamContract(Base):
    """Runs his real bin/grokbot-ask (from a local checkout, never vendored here) in agent mode with a stub
    agent, to prove the request I send is the one his helper accepts. Skipped without a checkout."""
    SRC = os.environ.get("GROKBOT_DESKTOP_SRC", "/tmp/gb")

    def test_real_helper_accepts_my_request(self):
        real = os.path.join(self.SRC, "bin/grokbot-ask")
        if not os.path.isfile(real):
            self.skipTest("no local checkout of tcballard/omarchy-plugin-grokbot-desktop")
        self.checkout()
        shutil.copy(real, CHECKOUT / "bin/grokbot-ask")
        (CHECKOUT / "bin/grokbot-ask").chmod(0o755)
        write(BIN / "stub-agent", "#!/bin/sh\nprintf 'stub agent got: '; tail -c 40\n", 0o755)
        self.settings(dispatch="agent", command=[str(BIN / "stub-agent")])
        r = grokbot.ask({}, "is the build green?")
        self.assertEqual(r["mode"], "relay")
        self.assertIn("is the build green?", r["text"])
        self.assertTrue(r["text"].startswith("stub agent got:"), r)


class OverLink(Base):
    def test_tile_mirror_and_question_from_tv(self):
        self.checkout()
        self.ipc_on()
        self.settings(botId=BOTS["bots"][1]["id"])
        helpers.HOME.joinpath(".config/omarchy-link").mkdir(parents=True, exist_ok=True)
        config.CONFIG_FILE.write_text(json.dumps({}))
        shutil.rmtree(helpers.TMP / "tv-grokbot", ignore_errors=True)   # no TV state from an earlier run
        tv = MockTV(helpers.TMP / "tv-grokbot", code="123456").start()
        self.addCleanup(tv.close)
        cli("pair", f"127.0.0.1:{tv.port}", "--code", tv.code)
        sess = subprocess.Popen([helpers.CLI, "session"], stdin=subprocess.PIPE, stdout=subprocess.DEVNULL,
                                stderr=subprocess.DEVNULL, text=True)

        def stop():
            sess.stdin.close()
            sess.wait(5)
        self.addCleanup(stop)
        import time
        end = time.time() + 15
        while time.time() < end and not (tv.config().get("plugins") or {}).get("grokbotBot"):
            time.sleep(0.1)
        bot = tv.config()["plugins"]["grokbotBot"]
        self.assertEqual((bot["name"], bot["shape"], bot["fill"]), ("OmaBot", "bean", "#00C972"))
        while time.time() < end and not tv.clients:
            time.sleep(0.05)
        m = tv.grokbot_ask("ping me")
        self.assertEqual(m["type"], "grokbot_answer", m)
        self.assertEqual(m["data"]["text"], "OmaBot says: em gnip")
        # the user switches bot on the desktop: the ticker pushes the new one
        self.settings(botId=BOTS["bots"][2]["id"])
        end = time.time() + 15
        while time.time() < end and tv.config()["plugins"]["grokbotBot"]["name"] != "Inbox Admin":
            time.sleep(0.2)
        self.assertEqual(tv.config()["plugins"]["grokbotBot"]["color"], "cyan")
        m = tv.grokbot_ask("x", bot="Nobody")
        self.assertEqual(m["data"]["error"], "unknown_bot")


if __name__ == "__main__":
    unittest.main()
