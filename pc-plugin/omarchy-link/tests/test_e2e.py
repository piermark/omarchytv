"""PC side against the mock TV: pairing, auth, remote, theme, widgets, events."""
import base64
import http.client
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
from omarchy_link import client as link, config, crypto, mdns, netcheck


def config_not_paired():
    from omarchy_link import cli as c
    return c.EXIT_NOT_PAIRED


class E2E(unittest.TestCase):
    def setUp(self):
        helpers.reset_pc_state()
        helpers.set_theme("tokyo-night", helpers.TOKYO)
        self.tvdir = helpers.TMP / f"tv-{self.id().rsplit('.', 1)[-1]}"
        self.tv = MockTV(self.tvdir, code="123456").start()
        self.target = f"127.0.0.1:{self.tv.port}"

    def tearDown(self):
        self.tv.close()

    def pair(self):
        cli("pair", self.target, "--code", self.tv.code)
        return config.Pairing.load()

    def tvconf(self):
        return json.loads((self.tvdir / "files/omarchytv.json").read_text())

    # ------------------------------------------------------------ pairing
    def test_pair_and_secret_storage(self):
        p = self.pair()
        self.assertTrue(p.paired)
        self.assertTrue(config.is_private_file(config.PAIRING_FILE))
        self.assertEqual(oct(os.stat(config.PAIRING_FILE.parent).st_mode & 0o777), "0o700")
        # token derived on both sides, never sent; it is not in the TV's public config
        tv_token = self.tv.pcs[p.pc_id]["token"]
        self.assertEqual(tv_token, p.tv["token"])
        self.assertNotIn(tv_token, (self.tvdir / "files/omarchytv.json").read_text())
        self.assertNotIn("123456", (self.tvdir / "files/omarchytv.json").read_text())
        self.assertEqual(self.tvconf()["link"]["pcs"][0]["name"], p.pc_name)

    def test_wrong_code_then_rotation(self):
        for i in range(5):
            r = cli("pair", self.target, "--code", "000000", check=False)
            self.assertEqual(r.returncode, 1)
            self.assertIn("wrong code", r.stderr)
        self.assertNotEqual(self.tv.code, "123456")  # rotated after 5 failures
        self.assertEqual(cli("pair", self.target, "--code", "123456", check=False).returncode, 1)
        self.assertEqual(self.tv.pcs, {})

    def test_code_single_use(self):
        self.pair()
        helpers.reset_pc_state()
        self.assertEqual(cli("pair", self.target, "--code", "123456", check=False).returncode, 1)

    def test_pair_with_rate_limit(self):
        # The launcher allows pair/* once per 2 s per peer, start and confirm together: the PC waits once.
        self.tv.pair_limit = True
        p = self.pair()
        self.assertTrue(p.paired)
        self.assertEqual(len(self.tv.pcs), 1)

    def test_pairing_closed(self):
        self.tv.pairing_open = False
        r = cli("pair", self.target, "--code", "123456", check=False)
        self.assertIn("Connect Omarchy Linux", r.stderr)

    def test_refuses_public_address(self):
        r = cli("pair", "8.8.8.8:47800", "--code", "123456", check=False)
        self.assertIn("not a LAN address", r.stderr)
        self.assertFalse(netcheck.is_lan_address("100.64.1.1"))  # CGNAT is not your LAN
        self.assertTrue(netcheck.is_lan_address("192.168.1.20"))
        self.assertTrue(netcheck.is_lan_address("fe80::1%wlan0"))
        self.assertFalse(netcheck.is_lan_address("2001:db8::1"))

    # ------------------------------------------------------------ auth
    def raw(self, method, path, body=b"", headers=None):
        c = http.client.HTTPConnection("127.0.0.1", self.tv.port, timeout=5)
        c.request(method, path, body=body or None, headers=headers or {})
        r = c.getresponse()
        data = r.read()
        c.close()
        return r.status, json.loads(data or b"{}")

    def test_no_unauthenticated_endpoints(self):
        for method, path in [("GET", "/v1/status"), ("GET", "/v1/apps"), ("POST", "/v1/remote/key"),
                             ("PUT", "/v1/theme"), ("PUT", "/v1/wallpaper"), ("PUT", "/v1/widgets"),
                             ("PUT", "/v1/ai"), ("POST", "/v1/unpair"), ("GET", "/v1/nope")]:
            status, obj = self.raw(method, path, b"{}")
            self.assertEqual(status, 401, path)
        status, info = self.raw("GET", "/v1/info")
        self.assertEqual(status, 200)
        self.assertEqual(set(info), {"service", "protocol", "tvId", "name", "pairingOpen"})

    def test_tamper_replay_skew(self):
        p = self.pair()
        token = base64.b64decode(p.tv["token"])
        body = b'{"key":"home"}'
        auth, _ = crypto.sign_request(token, p.pc_id, "POST", "/v1/remote/key", body, int(time.time()))
        self.assertEqual(self.raw("POST", "/v1/remote/key", b'{"key":"mute"}', {"Authorization": auth})[0], 401)
        self.assertEqual(self.raw("POST", "/v1/remote/key", body, {"Authorization": auth})[0], 200)
        self.assertEqual(self.raw("POST", "/v1/remote/key", body, {"Authorization": auth})[1]["error"], "replay")
        old, _ = crypto.sign_request(token, p.pc_id, "POST", "/v1/remote/key", body, int(time.time()) - 600)
        self.assertEqual(self.raw("POST", "/v1/remote/key", body, {"Authorization": old})[1]["error"], "clock_skew")
        # the client recovers from a skewed clock using X-OTV-Time
        c = link.Client(p.tv, p.pc_id)
        c.skew = -900
        self.assertTrue(c.status()["capabilities"])

    def test_client_rejects_unsigned_answers(self):
        p = self.pair()

        class Fake(BaseHTTPRequestHandler):
            def do_GET(self):
                body = b'{"foreground":{"label":"fake"}}'
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *a):
                pass
        srv = ThreadingHTTPServer(("127.0.0.1", 0), Fake)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        try:
            fake = dict(p.tv, port=srv.server_address[1])
            with self.assertRaises(link.LinkError) as e:
                link.Client(fake, p.pc_id).status()
            self.assertEqual(e.exception.code, "bad_response_sig")
        finally:
            srv.shutdown()

    # ------------------------------------------------------------ features
    def test_remote_and_status(self):
        self.pair()
        cli("launch", "com.netflix.ninja")
        w = json.loads(cli("status", "--waybar").stdout)
        self.assertEqual(w["class"], "connected")
        self.assertIn("Netflix", w["text"])
        cli("key", "home")
        self.assertIn("OmarchyTV", json.loads(cli("status", "--waybar").stdout)["text"])
        self.tv.set_state(screensaver=True)
        self.assertEqual(json.loads(cli("status", "--waybar").stdout)["class"], "screensaver")
        self.assertEqual(cli("key", "reboot", check=False).returncode, 2)
        self.assertEqual(cli("launch", "com.not.installed", check=False).returncode, 1)

    def test_notify_for_agent_hooks(self):   # v2.21
        self.assertEqual(cli("notify", "hi", check=False).returncode, config_not_paired())
        self.assertEqual(cli("notify", "-q", "hi").returncode, 0)
        self.pair()
        self.assertEqual(cli("notify", "build", "done", check=False).returncode, 4)   # agent alerts off on the TV
        self.tv.update_config(lambda j: j.update(agentAlert=True))
        cli("notify", "build   done", "--from", "Claude Code")
        self.assertEqual(self.tv.notes[-1], ("Claude Code", "build done"))
        # v2.22: the remote picked "alerts only": info is dropped (exit 0, a hint), an alert still shows
        self.tv.update_config(lambda j: j.update(agentAlertMin="alert"))
        n = len(self.tv.notes)
        r = cli("notify", "green", "--from", "CI")
        self.assertEqual((r.returncode, len(self.tv.notes)), (0, n))
        self.assertIn("filtered", r.stderr)
        self.assertEqual(cli("notify", "-q", "green").stderr, "")
        cli("notify", "red", "--level", "alert", "--from", "CI")
        self.assertEqual(self.tv.notes[-1], ("CI", "red"))
        self.tv.update_config(lambda j: j.pop("agentAlertMin", None))

    def test_app_tokens_ask_page_and_logs(self):   # v2.21
        self.pair()
        out = cli("token", "add", "My", "dashboard", "--scope", "notify").stdout.splitlines()
        self.assertIn("My dashboard (notify, expires after 90 days unused)", out[0])
        self.assertTrue(out[1].startswith("otva_"))
        self.assertIn("My dashboard", cli("token").stdout)
        # v2.22: a token can be held to a level ("hermes-nas" may warn, never alert)
        out = cli("token", "add", "hermes-nas", "--scope", "notify", "--max-level", "warn").stdout
        self.assertIn("hermes-nas (notify, at most warn, expires after 90 days unused)", out)
        self.assertIn("(at most warn)", cli("token").stdout)
        self.assertIn("00000002: at most info", cli("token", "level", "00000002", "info").stdout)
        self.assertIn("(at most info)", cli("token").stdout)
        self.assertEqual(cli("token", "level", "00000002", "loud", check=False).returncode, 2)
        self.assertEqual(cli("token", "floor", "00000002", "alert", check=False).returncode, 1)   # above the ceiling (info)
        cli("token", "level", "00000002", "alert")
        cli("token", "floor", "00000002", "warn")
        self.assertIn("(at least warn)", cli("token").stdout)
        cli("token", "revoke", "00000002")
        cli("token", "revoke", "00000001")
        self.assertIn("No app tokens", cli("token").stdout)
        self.assertEqual(cli("ask", "Deploy?", check=False).returncode, 4)
        self.tv.update_config(lambda j: j.update(agentAlert=True))
        self.tv.answer_with = "Not now"
        r = cli("ask", "Deploy?", "--choice", "Yes", "--choice", "Not now", "--timeout", "10")
        self.assertEqual(r.stdout.strip(), "Not now")
        cli("page", "http://192.168.1.20:8080/")
        self.assertEqual(self.tv.page, "http://192.168.1.20:8080/")
        cli("page", "http://192.168.1.20:8080/", "http://192.168.1.21:3000/")
        self.assertEqual(self.tv.pages, ["http://192.168.1.20:8080/", "http://192.168.1.21:3000/"])
        self.assertEqual(cli("page", "--close").stdout.strip(), "closed")
        self.assertIn("question from PC", cli("tv", "logs").stdout)
        self.assertEqual(cli("radio").stdout.strip(), "nothing playing")
        self.assertIn("Hyprland Nights", cli("radio", "play").stdout)
        cli("open", "radiomarchy")
        self.assertEqual(self.tv.opened, "radiomarchy")
        cli("pages", "add", "dubai", "Dubai flats", "http://192.168.1.20:8080/", "--icon", "D", "--badge", "2")
        cli("pages", "badge", "dubai", "5")
        self.assertIn("Dubai flats (5)", cli("pages").stdout)
        cli("pages", "show", "dubai")
        self.assertEqual(self.tv.page, "http://192.168.1.20:8080/")
        cli("pages", "remove", "dubai")
        self.assertIn("No pages", cli("pages").stdout)
        bk = helpers.TMP / "tv-backup.json"
        cli("tv", "backup", str(bk))
        saved = json.loads(bk.read_text())
        saved["wallpaper"] = "plain"
        saved["link"] = {"evil": True}
        bk.write_text(json.dumps(saved))
        cli("tv", "restore", str(bk))
        self.assertEqual(self.tvconf()["wallpaper"], "plain")
        self.assertNotIn("evil", self.tvconf().get("link", {}))
        self.tv.notes = []
        r = cli("watch", "--", "sh", "-c", "echo building; echo done-ok", check=False)
        self.assertEqual(r.returncode, 0)
        self.assertIn("done-ok", r.stdout)
        self.assertIn("done-ok", self.tv.notes[-1][1])
        self.assertEqual(cli("watch", "--", "sh", "-c", "exit 3", check=False).returncode, 3)
        # v2.22: --only-fail keeps green runs off the TV, failures still show
        n = len(self.tv.notes)
        r = cli("watch", "--only-fail", "--", "sh", "-c", "echo green", check=False)
        self.assertEqual((r.returncode, len(self.tv.notes)), (0, n))
        self.assertIn("green", r.stdout)
        self.assertEqual(cli("watch", "--quiet-ok", "--", "sh", "-c", "echo red; exit 4", check=False).returncode, 4)
        self.assertEqual(len(self.tv.notes), n + 1)
        self.assertIn("red", self.tv.notes[-1][1])
        cli("notify", '{"type":"agent-turn-complete","last-assistant-message":"Refactor finished"}', "--from", "Codex")
        self.assertEqual(self.tv.notes[-1], ("Codex", "Refactor finished"))
        # v2.21: Link after boot and the Home wallpaper, from the PC
        self.assertIn("auto -> off", cli("tv", "keepalive").stdout)
        self.assertIn("on -> on", cli("tv", "keepalive", "on").stdout)
        self.assertEqual(cli("tv", "keepalive", "sometimes", check=False).returncode, 2)
        self.assertIn("last-horizon", cli("tv", "home").stdout)
        self.assertIn("looping (light 540p copy)", cli("tv", "home").stdout)
        self.tv.watching = True
        self.assertIn("held", cli("notify", "--title", "Build", "green").stderr)

    def test_status_offline_and_unpaired(self):
        self.assertEqual(json.loads(cli("status", "--waybar").stdout)["class"], "unpaired")
        self.pair()
        self.tv.close()
        self.assertEqual(json.loads(cli("status", "--waybar").stdout)["class"], "offline")
        self.tv = MockTV(self.tvdir, code="123456").start()  # for tearDown

    def test_theme_sync(self):
        self.pair()  # pairing pushes the theme once
        conf = self.tvconf()
        self.assertEqual(conf["theme"]["accent"], "#7aa2f7")
        self.assertEqual(conf["theme"]["accent2"], "#bb9af7")
        self.assertEqual(conf["theme"]["name"], "tokyo-night")
        wp = conf["wallpaper"]
        dims = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "stream=width,height", "-of", "csv=p=0", wp],
                              capture_output=True, text=True).stdout.strip()
        self.assertEqual(dims, "3840,2160")
        n = len(self.tv.log)
        self.assertIn("Nothing to send", cli("sync-theme").stdout)  # unchanged -> no traffic
        self.assertEqual(len(self.tv.log), n)
        helpers.set_theme("gruvbox", helpers.GRUV, "0x282828", "1920x1200")
        cli("sync-theme", "--quiet")
        conf = self.tvconf()
        self.assertEqual(conf["theme"]["accent"], "#d79921")
        dims = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "stream=width,height", "-of", "csv=p=0",
                               conf["wallpaper"]], capture_output=True, text=True).stdout.strip()
        self.assertEqual(dims, "1920,1080")  # cropped to 16:9, never upscaled
        # wallpaper not re-sent when the TV already has it
        cli("sync-theme", "--force")
        self.assertEqual(sum(1 for m, p, _ in self.tv.log if p == "/v1/wallpaper" and m == "PUT"), 2)

    def test_pc_without_a_theme_leaves_the_tv_theme(self):
        # macOS (or a Linux without Omarchy): nothing under ~/.local/state/omarchy/current
        shutil.rmtree(helpers.OMARCHY, ignore_errors=True)
        self.pair()
        self.assertNotIn("theme", self.tvconf())
        self.assertIn("This PC has no Omarchy theme", cli("sync-theme", "--force").stdout)
        self.assertEqual([p for m, p, _ in self.tv.log if p in ("/v1/theme", "/v1/wallpaper")], [])

    def test_hook_script(self):
        self.pair()
        cli("hook", "add")
        hook = helpers.HOME / ".config/omarchy/hooks/theme-set.d/omarchy-link"
        self.assertIn(str(helpers.ROOT), hook.read_text())
        helpers.set_theme("gruvbox", helpers.GRUV, "0x282828", "1920x1200")
        subprocess.run(["bash", str(hook), "gruvbox"], check=True, timeout=10)  # what omarchy-hook does
        for _ in range(100):
            if self.tvconf()["theme"]["name"] == "gruvbox":
                break
            time.sleep(0.1)
        self.assertEqual(self.tvconf()["theme"]["name"], "gruvbox")
        cli("hook", "remove")
        self.assertFalse(hook.exists())

    def test_widgets(self):
        self.pair()
        wfile = helpers.TMP / "widgets.json"
        wfile.write_text(json.dumps({
            "panes": [{"id": "deploys", "title": "Deploys", "lines": [{"label": "api", "value": "green"}]}],
            "shortcuts": [{"id": "lofi", "label": "Lofi", "action": {"type": "view", "uri": "https://www.youtube.com/watch?v=jfKfPfyJRdk", "package": "com.google.android.youtube.tv"}},
                          {"id": "netflix", "label": "Netflix", "action": {"type": "app", "package": "com.netflix.ninja"}}]}))
        cli("push-widgets", str(wfile))
        link_conf = self.tvconf()["link"]
        self.assertEqual([p["id"] for p in link_conf["panes"]], ["pc", "deploys"])
        self.assertEqual(len(link_conf["shortcuts"]), 2)
        wfile.write_text(json.dumps({"shortcuts": [{"id": "x", "action": {"type": "pc", "command": "rm"}}]}))
        self.assertIn("not defined", cli("push-widgets", str(wfile), check=False).stderr)
        wfile.write_text(json.dumps({"shortcuts": [{"id": "x", "action": {"type": "view", "uri": "intent://evil"}}]}))
        self.assertEqual(cli("push-widgets", str(wfile), check=False).returncode, 1)

    def test_events_stream(self):
        p = self.pair()
        c = link.Client(p.tv, p.pc_id)
        s = c.events()
        hello = s.recv()
        self.assertEqual(hello["type"], "hello")
        self.tv.open_app("com.disney.disneyplus")
        ev = s.recv()
        self.assertEqual((ev["type"], ev["data"]["foreground"]["label"]), ("status", "Disney+"))
        s.send("ping")
        # a forged frame (wrong key) is rejected by the client
        conn, key, seq, lock = self.tv.clients[0]
        with lock:
            seq[0] += 1
            conn.send_text(crypto.seal_message(b"z" * 32, json.dumps({"seq": seq[0], "type": "shortcut",
                                                                       "data": {"command": "x"}}).encode()))
        with self.assertRaises(ValueError):
            s.recv()
        s.close()

    def test_shortcut_runs_only_configured_actions(self):
        p = self.pair()
        marker = helpers.TMP / "action-ran"
        config.write_json_private(config.CONFIG_FILE, {"actions": {
            "touch": {"label": "Touch", "exec": ["touch", str(marker)], "confirm": False}}})
        sess = subprocess.Popen([helpers.CLI, "session"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        try:
            for _ in range(50):
                if self.tv.clients:
                    break
                time.sleep(0.1)
            self.tv.broadcast("shortcut", {"command": "rm -rf"})
            self.tv.broadcast("shortcut", {"command": "touch"})
            for _ in range(50):
                if marker.exists():
                    break
                time.sleep(0.1)
            self.assertTrue(marker.exists())
        finally:
            sess.stdin.close()
            sess.wait(5)

    def test_unpair(self):
        p = self.pair()
        cli("unpair")
        self.assertNotIn(p.pc_id, self.tv.pcs)
        self.assertFalse(config.Pairing.load().paired)
        self.assertEqual(self.tvconf()["link"]["pcs"], [])

    def test_discovery_over_mdns(self):
        self.tv.responder = mdns.Responder(self.tv.name, self.tv.port, {"v": "1", "id": self.tv.tv_id,
                                                                        "name": self.tv.name, "pair": "1"},
                                           "127.0.0.1", bind=("127.0.0.1", 0), multicast=False).start()
        os.environ["OMARCHY_LINK_MDNS"] = f"127.0.0.1:{self.tv.responder.bound[1]}"
        try:
            devs = json.loads(cli("discover", "--json").stdout)
            self.assertEqual(devs[0]["id"], self.tv.tv_id)
            self.assertEqual(devs[0]["port"], self.tv.port)
            cli("pair", "--code", self.tv.code)  # no address: picks the discovered TV
            self.assertTrue(config.Pairing.load().paired)
        finally:
            del os.environ["OMARCHY_LINK_MDNS"]


if __name__ == "__main__":
    unittest.main()
