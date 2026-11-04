"""LG/Samsung "Update via PC": pick the right official asset, refuse anything else, install, report."""
import io
import json
import os
import sys
import tempfile
import threading
import unittest
import zipfile
from pathlib import Path
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "lib"))
from omarchy_tv import tvupdate  # noqa: E402

DL = "https://github.com/piermark/omarchytv/releases/download/"
RELS = [
    {"tag_name": "v2.13.12", "draft": False, "assets": [
        {"name": "omarchytv-webos-2.13.12-testing.ipk", "browser_download_url": DL + "v2.13.12/omarchytv-webos-2.13.12-testing.ipk",
         "size": 10, "digest": "sha256:" + "ab" * 32},
        {"name": "OmarchyTV-tizen-2.13.12-unsigned.wgt", "browser_download_url": DL + "v2.13.12/OmarchyTV-tizen-2.13.12-unsigned.wgt", "size": 10}]},
    {"tag_name": "v2.14.0", "draft": True, "assets": [
        {"name": "omarchytv-webos-2.14.0.ipk", "browser_download_url": DL + "v2.14.0/omarchytv-webos-2.14.0.ipk"}]},
    {"tag_name": "evil", "draft": False, "assets": [
        {"name": "omarchytv-webos-9.9.9.ipk", "browser_download_url": "https://evil.example/omarchytv-webos-9.9.9.ipk"}]},
    {"tag_name": "v2.13.11", "draft": False, "assets": [
        {"name": "omarchytv-webos-2.13.11-testing.ipk", "browser_download_url": DL + "v2.13.11/omarchytv-webos-2.13.11-testing.ipk"},
        {"name": "omarchytv-release-2.13.8.apk", "browser_download_url": DL + "v2.13.8/omarchytv-release-2.13.8.apk"}]},
]


class Tracker:
    def __init__(self, *a):
        self.stages, self.result, self.ev = [], None, threading.Event()

    def stage(self, s, pct=None):
        self.stages.append(s)

    def done(self, ok, code=None, message=None):
        self.result = (ok, message)
        self.ev.set()


class Pick(unittest.TestCase):
    def test_versions(self):
        k = tvupdate.version_key
        self.assertTrue(k("2.13.11") > k("2.13.11-beta") > k("2.13.10"))
        self.assertEqual(k("2.13.11-testing"), k("2.13.11"))
        self.assertEqual(k("junk"), (-1,))

    def test_pick_newest_official_non_draft(self):
        a = tvupdate.pick(RELS, "webos", "2.13.11")
        self.assertEqual((a["version"], a["sha256"]), ("2.13.12", "ab" * 32))
        self.assertIsNone(tvupdate.pick(RELS, "webos", "2.13.12"))
        self.assertEqual(tvupdate.pick(RELS, "tizen", "2.13.11")["name"], "OmarchyTV-tizen-2.13.12-unsigned.wgt")
        self.assertIsNone(tvupdate.pick(RELS, "android", "1.0.0"))


class Start(unittest.TestCase):
    def run_job(self, platform, payload, cfg=None, tools=True):
        t = Tracker()
        runs = []

        def fake_run(argv, timeout=300):
            runs.append(argv)
            if argv[1:2] == ["package"]:
                out = Path(argv[argv.index("-o") + 1])
                (out / "OmarchyTV.wgt").write_bytes(b"x")
            return mock.Mock(returncode=0, stdout="", stderr="")

        with mock.patch.object(tvupdate, "releases", return_value=RELS), \
             mock.patch.object(tvupdate, "_get", return_value=payload), \
             mock.patch.object(tvupdate, "_run", side_effect=fake_run), \
             mock.patch.object(tvupdate.shutil, "which", side_effect=lambda n: f"/usr/bin/{n}" if tools else None), \
             mock.patch.object(tvupdate.update, "ipk_info", return_value={"id": "com.omarchytv.home", "version": "2.13.12"}):
            res = tvupdate.start(platform, "2.13.11", "2.13.12", "192.168.1.50", cfg or {}, lambda k, n: t)
            if res == "started":
                self.assertTrue(t.ev.wait(5))
        return res, t, runs

    def test_webos_checksum_mismatch_is_refused(self):
        res, t, runs = self.run_job("webos", b"not the release")
        self.assertEqual(res, "started")
        self.assertFalse(t.result[0])
        self.assertIn("checksum", t.result[1])
        self.assertEqual(runs, [])

    def test_webos_ok(self):
        import hashlib
        payload = b"ipk"
        orig = RELS[0]["assets"][0]["digest"]
        RELS[0]["assets"][0]["digest"] = "sha256:" + hashlib.sha256(payload).hexdigest()
        try:
            res, t, runs = self.run_job("webos", payload, {"webosDevice": "tv"})
        finally:
            RELS[0]["assets"][0]["digest"] = orig
        self.assertEqual(t.result, (True, None))
        self.assertEqual(runs[0][:3], ["/usr/bin/ares-install", "--device", "tv"])
        self.assertEqual(runs[1][-1], "com.omarchytv.home")

    def wgt(self, config=b'<widget><tizen:application package="OmarchyTV0"/></widget>', extra=None):
        b = io.BytesIO()
        with zipfile.ZipFile(b, "w") as z:
            z.writestr("config.xml", config)
            z.writestr("index.html", "<html>")
            z.writestr("author-signature.xml", "old")
            for n, v in (extra or {}).items():
                z.writestr(n, v)
        return b.getvalue()

    def test_tizen_needs_a_profile(self):
        res, t, runs = self.run_job("tizen", self.wgt())
        self.assertFalse(t.result[0])
        self.assertIn("tizenProfile", t.result[1])

    def test_tizen_signs_with_the_users_profile_and_installs(self):
        res, t, runs = self.run_job("tizen", self.wgt(), {"tizenProfile": "my-profile"})
        self.assertEqual(t.result, (True, None))
        self.assertEqual(runs[0][:6], ["/usr/bin/tizen", "package", "-t", "wgt", "-s", "my-profile"])
        self.assertEqual(runs[1], ["/usr/bin/sdb", "connect", "192.168.1.50"])
        self.assertIn("192.168.1.50:26101", runs[2])

    def test_tizen_refuses_foreign_or_traversal(self):
        _, t, runs = self.run_job("tizen", self.wgt(b"<widget/>"), {"tizenProfile": "p"})
        self.assertFalse(t.result[0])
        _, t, runs = self.run_job("tizen", self.wgt(extra={"../../evil": "x"}), {"tizenProfile": "p"})
        self.assertFalse(t.result[0])
        self.assertEqual(runs, [])

    def test_uptodate_busy_and_bad_input(self):
        with mock.patch.object(tvupdate, "releases", return_value=RELS):
            self.assertEqual(tvupdate.start("webos", "2.13.12", "", "10.0.0.2", {}, Tracker), "uptodate")
            with self.assertRaises(tvupdate.UpdateError):
                tvupdate.start("webos", "2.13.11", "2.13.99", "10.0.0.2", {}, Tracker)
            with self.assertRaises(tvupdate.UpdateError):
                tvupdate.start("webos", "2.13.11", "", "not-an-ip", {}, Tracker)
            tvupdate._job.acquire()
            try:
                self.assertEqual(tvupdate.start("webos", "2.13.11", "", "10.0.0.2", {}, Tracker), "busy")
            finally:
                tvupdate._job.release()
        self.assertFalse(tvupdate._job.locked())


if __name__ == "__main__":
    unittest.main()
