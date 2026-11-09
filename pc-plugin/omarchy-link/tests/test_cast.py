"""Share to TV: classify, route choice, Google Cast via a catt stub, the Link cast messages with the mock TV."""
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
import unittest
from unittest import mock
import urllib.error
import urllib.request

import helpers
from helpers import cli
from mock_tv import MockTV
from omarchy_link import cast
from omarchy_link import cli as cli_mod

BIN = helpers.TMP / "castbin"
CATT_LOG = helpers.TMP / "catt.log"


def stub(name, body):
    BIN.mkdir(parents=True, exist_ok=True)
    p = BIN / name
    p.write_text(body)
    p.chmod(0o755)


def fake_catt(exit_code=0, stderr="", scan="192.168.1.30 - Living Room TV - Sony BRAVIA 4K\n"):
    stub("catt", f"""#!/usr/bin/env python3
import sys
open({str(CATT_LOG)!r}, "a").write(" ".join(sys.argv[1:]) + "\\n")
if sys.argv[1:] == ["scan"]:
    print({scan!r}, end="")
    sys.exit(0)
sys.stderr.write({stderr!r})
sys.exit({exit_code})
""")


class Classify(unittest.TestCase):
    def test_kinds(self):
        y = cast.classify("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=1m30s")
        self.assertEqual((y["kind"], y["videoId"], y["position"]), ("youtube", "dQw4w9WgXcQ", 90.0))
        self.assertEqual(cast.classify("https://youtu.be/dQw4w9WgXcQ?t=42")["position"], 42.0)
        self.assertEqual(cast.classify("https://youtube.com/shorts/dQw4w9WgXcQ")["kind"], "youtube")
        v = cast.classify("https://cdn.example.org/films/Big%20Buck.mp4")
        self.assertEqual((v["kind"], v["mime"], v["title"]), ("video", "video/mp4", "Big Buck.mp4"))
        self.assertEqual(cast.classify("https://live.example.org/s/index.m3u8")["kind"], "video")
        self.assertEqual(cast.classify("https://example.org/cover.jpg")["kind"], "image")
        self.assertEqual(cast.classify("https://example.org/a/page")["kind"], "url")
        for bad in ("javascript:alert(1)", "intent://x#Intent;end", "content://x", "ftp://h/x.mp4"):
            with self.assertRaises(cast.CastError):
                cast.classify(bad)
        d = helpers.TMP / "files"
        d.mkdir(exist_ok=True)
        (d / "clip.mkv").write_bytes(b"\x1a\x45\xdf\xa3" + b"0" * 100)
        (d / "notes.md").write_text("# Shopping\nmilk\n")
        (d / "x.zip").write_bytes(b"PK")
        f = cast.classify(f"file://{d}/clip.mkv")
        self.assertEqual((f["kind"], f["mime"], f["size"], f["title"]), ("video", "video/x-matroska", 104, "clip"))
        t = cast.classify(str(d / "notes.md"))
        self.assertEqual((t["kind"], t["text"], t["title"]), ("text", "# Shopping\nmilk\n", "notes.md"))
        with self.assertRaises(cast.CastError) as e:
            cast.classify(str(d / "x.zip"))
        self.assertEqual(e.exception.code, "unsupported_kind")
        self.assertEqual(cast.classify("hello there", allow_text=True)["kind"], "text")
        self.assertEqual([cast.seconds(x) for x in ("90", "1m30s", "1:30", "1:02:03", "2h")], [90, 90, 90, 3723, 7200])

    def test_routes(self):
        os.environ["PATH"], old = f"{BIN}:{os.environ['PATH']}", os.environ["PATH"]
        try:
            shutil.rmtree(BIN, ignore_errors=True)
            y = cast.classify("https://youtu.be/dQw4w9WgXcQ")
            self.assertEqual(cast.choose(y, "auto", {}, False), ["link"])  # no catt: Link only
            fake_catt()
            self.assertEqual(cast.choose(y, "auto", {}, False), ["cast", "link"])
            self.assertEqual(cast.choose(y, "auto", {"castPrefer": "link"}, False), ["link", "cast"])
            self.assertEqual(cast.choose(cast.text_item("hi"), "auto", {}, False), ["link"])
            f = {"kind": "video", "path": "/x.mp4", "size": 1}
            self.assertEqual(cast.choose(f, "auto", {}, False), ["link", "cast"])  # upload, no port
            self.assertEqual(cast.choose(f, "auto", {}, True), ["cast", "link"])
            self.assertEqual(cast.catt_argv(dict(y, position=90), "Living Room TV"),
                             ["catt", "-d", "Living Room TV", "cast", "-t", "90", y["url"]])
            self.assertEqual(cast.catt_argv(cast.classify("https://example.org/page"), None),
                             ["catt", "cast_site", "https://example.org/page"])
        finally:
            os.environ["PATH"] = old
            shutil.rmtree(BIN, ignore_errors=True)


class CastE2E(unittest.TestCase):
    def setUp(self):
        helpers.reset_pc_state()
        shutil.rmtree(BIN, ignore_errors=True)
        CATT_LOG.unlink(missing_ok=True)
        self.old_path = os.environ["PATH"]
        os.environ["PATH"] = f"{BIN}:{self.old_path}"
        self.files = helpers.TMP / "castfiles"
        self.files.mkdir(exist_ok=True)

    def tearDown(self):
        os.environ["PATH"] = self.old_path
        shutil.rmtree(BIN, ignore_errors=True)
        helpers.reset_pc_state()

    def tv(self, **kw):
        tv = MockTV(helpers.TMP / f"tv-{self.id().rsplit('.', 1)[-1]}", code="123456", **kw).start()
        self.addCleanup(tv.close)
        cli("pair", f"127.0.0.1:{tv.port}", "--code", tv.code)
        return tv

    def jcast(self, *args, check=True):
        r = cli("cast", "--json", *args, check=check)
        return json.loads(r.stdout.strip().splitlines()[-1]) if r.stdout.strip() else {"rc": r.returncode, "err": r.stderr}

    def test_google_cast_only(self):
        """No launcher at all: YouTube and links go to the Cast device via catt."""
        fake_catt()
        r = self.jcast("https://www.youtube.com/watch?v=dQw4w9WgXcQ", "--at", "1:30")
        self.assertEqual((r["via"], r["device"]), ("cast", "Living Room TV"))  # the one device found
        self.assertIn("-d Living Room TV cast -t 90 https://www.youtube.com/watch?v=dQw4w9WgXcQ", CATT_LOG.read_text())
        cli("cast", "--to", "Kitchen", "https://example.org/page")
        self.assertIn("-d Kitchen cast_site https://example.org/page", CATT_LOG.read_text())
        # local file: catt would open a port, so only with --serve
        (self.files / "a.mp4").write_bytes(b"x" * 10)
        r = cli("cast", "--via", "cast", str(self.files / "a.mp4"), check=False)
        self.assertEqual(r.returncode, 1)
        self.assertIn("--serve", r.stderr)
        cli("cast", "--via", "cast", "--serve", str(self.files / "a.mp4"))
        self.assertIn(f"cast {self.files / 'a.mp4'}", CATT_LOG.read_text())
        r = cli("cast", "--text", "hi", check=False)  # text needs the launcher
        self.assertEqual(r.returncode, 1)
        self.assertIn("OmarchyTV", r.stderr)
        cli("cast", "--stop")
        self.assertTrue(CATT_LOG.read_text().strip().endswith("stop"))

    def test_cast_falls_back_to_link(self):
        tv = self.tv()
        fake_catt(exit_code=1, stderr="Error: No devices found\n", scan="")
        r = self.jcast("https://youtu.be/dQw4w9WgXcQ")
        self.assertEqual((r["via"], r["app"]), ("link", "youtube"))
        self.assertEqual(tv.casts[-1]["videoId"], "dQw4w9WgXcQ")

    def test_link_kinds_and_upload(self):
        tv = self.tv()
        r = self.jcast("--via", "link", "https://example.org/page", "--title", "Docs")
        self.assertEqual((r["via"], r["app"], r["state"]), ("link", "browser", "opening"))
        c = tv.casts[-1]
        self.assertEqual((c["kind"], c["url"], c["title"]), ("url", "https://example.org/page", "Docs"))
        self.assertEqual(len(c["id"]), 16)
        self.assertEqual(self.jcast("--text", "Dinner is ready 🍝")["app"], "text")
        self.assertEqual(tv.casts[-1]["text"], "Dinner is ready 🍝")
        img = self.files / "photo.png"
        img.write_bytes(b"\x89PNG\r\n\x1a\n" + os.urandom(3000))
        r = self.jcast(str(img), "--wait")  # small file: uploaded over the signed channel, no port opened
        self.assertEqual((r["app"], r["state"]), ("viewer", "ended"))
        sha = hashlib.sha256(img.read_bytes()).hexdigest()
        self.assertEqual(tv.casts[-1]["file"], sha)
        self.assertNotIn("url", tv.casts[-1])
        self.assertEqual((tv.data_dir / "internal/cast" / sha).read_bytes(), img.read_bytes())
        cli("cast", "--control", "pause")
        cli("cast", "--stop")
        self.assertEqual([x["action"] for x in tv.cast_controls], ["pause", "stop"])

    def test_link_serves_big_file_only_with_consent(self):
        tv = self.tv(cast_upload_max=1024)
        big = self.files / "movie.mp4"
        big.write_bytes(os.urandom(300_000))
        r = cli("cast", "--via", "link", str(big), check=False)
        self.assertEqual(r.returncode, 1)
        self.assertIn("--serve", r.stderr)
        self.assertEqual(tv.casts, [])
        t0 = time.time()
        r = self.jcast("--via", "link", "--serve", str(big))
        self.assertEqual((r["app"], r["state"]), ("player", "ended"))  # the CLI waited, then closed the server
        self.assertLess(time.time() - t0, 20)
        c = tv.casts[-1]
        self.assertRegex(c["url"], r"^http://127\.0\.0\.1:\d+/cast/[A-Za-z0-9_-]{32}/movie\.mp4$")
        self.assertEqual(tv.cast_fetched[c["id"]], big.read_bytes())
        with self.assertRaises(OSError):  # gone after playback
            urllib.request.urlopen(c["url"], timeout=2)

    def test_file_server_rules(self):
        f = self.files / "song.mp3"
        f.write_bytes(bytes(range(256)) * 4)
        srv = cast.FileServer("127.0.0.1", "song.mp3", "audio/mpeg", path=str(f))
        self.addCleanup(srv.close)
        req = urllib.request.Request(srv.url, headers={"Range": "bytes=10-19"})
        with urllib.request.urlopen(req, timeout=5) as r:
            self.assertEqual((r.status, r.read(), r.headers["Content-Range"]), (206, f.read_bytes()[10:20], "bytes 10-19/1024"))
        with urllib.request.urlopen(srv.url, timeout=5) as r:
            self.assertEqual(r.read(), f.read_bytes())
        for bad in (srv.url.replace(srv.token, "x" * 32), srv.url.rsplit("/", 1)[0] + "/other.mp3"):
            with self.assertRaises(urllib.error.HTTPError) as e:
                urllib.request.urlopen(bad, timeout=5)
            self.assertEqual(e.exception.code, 404)
        other = cast.FileServer("127.0.0.1", "song.mp3", "audio/mpeg", path=str(f))
        other.allow = "192.168.1.99"  # only the TV's address may fetch
        self.addCleanup(other.close)
        with self.assertRaises(urllib.error.HTTPError) as e:
            urllib.request.urlopen(other.url, timeout=5)
        self.assertEqual(e.exception.code, 403)
        short = cast.FileServer("127.0.0.1", "song.mp3", "audio/mpeg", path=str(f), idle=1)
        time.sleep(2.5)
        self.assertTrue(short.closed.is_set())  # idle: closed by itself

    def test_screen_experimental(self):
        tv = self.tv()
        rec = helpers.TMP / "fake-recorder.py"
        rec.write_text("import sys, time\nwhile True:\n    sys.stdout.buffer.write(b'G' * 188 * 8); sys.stdout.flush(); time.sleep(0.01)\n")
        helpers.HOME.joinpath(".config/omarchy-link").mkdir(parents=True, exist_ok=True)
        helpers.HOME.joinpath(".config/omarchy-link/config.json").write_text(
            json.dumps({"screenCommand": [sys.executable, str(rec)]}))
        r = self.jcast("--screen")
        self.assertEqual((r["app"], r["state"]), ("player", "stopped"))
        c = tv.casts[-1]
        self.assertEqual((c["kind"], c["mime"], c["live"]), ("video", "video/mp2t", True))
        self.assertGreaterEqual(len(tv.cast_fetched[c["id"]]), tv.cast_live_bytes)
        self.assertEqual(set(tv.cast_fetched[c["id"]]), {ord("G")})
        time.sleep(0.5)
        self.assertEqual(subprocess.run(["pgrep", "-f", str(rec)], capture_output=True).returncode, 1)  # recorder stopped

    def test_old_launcher_and_clipboard(self):
        tv = self.tv(cast=False)
        r = cli("cast", "--via", "link", "https://example.org/x", check=False)
        self.assertEqual(r.returncode, 1)
        self.assertIn("cannot receive casts yet", r.stderr)
        tv.cast_enabled = True
        stub("wl-paste", "#!/bin/sh\nprintf 'https://youtu.be/dQw4w9WgXcQ'\n")
        self.assertEqual(self.jcast("--clipboard")["app"], "youtube")
        stub("wl-paste", "#!/bin/sh\nprintf 'Wi-Fi: casa / pass: 1234\\nsecond line'\n")
        self.assertEqual(self.jcast("--clipboard")["kind"], "text")
        self.assertEqual(tv.casts[-1]["text"], "Wi-Fi: casa / pass: 1234\nsecond line")
        d = json.loads(cli("cast", "--devices", "--json").stdout)
        self.assertEqual((d["catt"], d["link"]["cast"]), (False, True))

    def test_macos_clipboard(self):
        # pbpaste is macOS's clipboard reader; it is only tried when wl-paste and xclip are missing
        tv = self.tv()
        stub("pbpaste", "#!/bin/sh\nprintf 'https://youtu.be/dQw4w9WgXcQ'\n")
        self.assertEqual(self.jcast("--clipboard")["app"], "youtube")
        stub("pbpaste", "#!/bin/sh\nprintf 'Wi-Fi: casa / pass: 1234\\nsecond line'\n")
        self.assertEqual(self.jcast("--clipboard")["kind"], "text")
        self.assertEqual(tv.casts[-1]["text"], "Wi-Fi: casa / pass: 1234\nsecond line")

    def test_macos_notification(self):
        # --notify on macOS: osascript "display notification", with title and text passed as arguments
        log = helpers.TMP / "osascript.log"
        log.unlink(missing_ok=True)
        stub("osascript", f"#!/bin/sh\nprintf '%s\\n' \"$@\" > {log}\n")
        with mock.patch.object(sys, "platform", "darwin"):
            cli_mod._notify('it\'s "quoted"', "Sent to TV")
        self.assertEqual(log.read_text().splitlines()[-2:], ["Sent to TV", 'it\'s "quoted"'])
        log.unlink()
        with mock.patch.object(sys, "platform", "linux"):
            cli_mod._notify("x")
        self.assertFalse(log.exists())  # never tried off macOS

    def test_desktop_entries(self):
        cli("desktop")
        apps = helpers.HOME / ".local/share/applications"
        share = (apps / "omarchy-link-share.desktop").read_text()
        self.assertIn("cast --notify %u", share)
        self.assertIn("video/mp4;", share)
        self.assertIn("cast --clipboard --notify", (apps / "omarchy-link-share-clipboard.desktop").read_text())
        cli("desktop", "--remove")
        self.assertFalse(list(apps.glob("omarchy-link-*.desktop")))



class UfwHintTests(unittest.TestCase):
    """#82: a served cast that fails while ufw is on gets the one-line fix, with the TV's address."""

    def test_hint_only_when_ufw_is_active_and_a_served_cast_failed(self):
        ok = mock.Mock(return_value=mock.Mock(returncode=0))
        off = mock.Mock(return_value=mock.Mock(returncode=3))
        has = lambda n: "/usr/bin/ufw"
        h = cli_mod._ufw_hint("disconnected", True, "192.168.1.50", run=ok, which=has)
        self.assertIn("sudo ufw allow from 192.168.1.50 proto tcp", h)
        self.assertIn("<tv-ip>", cli_mod._ufw_hint("timeout", True, None, run=ok, which=has))
        self.assertEqual(cli_mod._ufw_hint("playing", True, "x", run=ok, which=has), "")      # it worked
        self.assertEqual(cli_mod._ufw_hint("error", False, "x", run=ok, which=has), "")       # nothing was served
        self.assertEqual(cli_mod._ufw_hint("error", True, "x", run=off, which=has), "")       # ufw not active
        self.assertEqual(cli_mod._ufw_hint("error", True, "x", run=ok, which=lambda n: None), "")   # no ufw

if __name__ == "__main__":
    unittest.main()
