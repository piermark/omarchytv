"""Omastorm on the TV: find the real window, stream only its rectangle, remote keys -> its key bindings."""
import json
import unittest
from unittest import mock

import helpers  # noqa: F401  (hermetic env + sys.path)
from omarchy_link import cast, omastorm, session

WIN = {"address": "0x55aa", "title": "Omastorm", "at": [320, 180], "size": [1280, 720], "floating": True, "mapped": True}


class Window(unittest.TestCase):
    def test_find_region_and_keys(self):
        clients = [{"title": "kitty", "at": [0, 0], "size": [10, 10]}, WIN]
        with mock.patch.object(omastorm, "_hypr", return_value=json.dumps(clients)):
            w = omastorm.find_window()
        self.assertEqual(w["address"], "0x55aa")
        self.assertEqual(omastorm.region(w), "320,180 1280x720")
        self.assertEqual(omastorm.key_shortcut("mosaic", "0x55aa"), "SHIFT, N, address:0x55aa")
        self.assertEqual(omastorm.key_shortcut("left", "0x55aa"), ", Left, address:0x55aa")
        self.assertIsNone(omastorm.key_shortcut("rm -rf", "0x55aa"))
        self.assertIsNone(omastorm.key_shortcut("left", "kitty"))

    def test_region_capture_argv(self):
        env = {"OMARCHY_LINK_SCREEN_REGION": "320,180 1280x720"}
        with mock.patch.dict(omastorm.os.environ, env), \
             mock.patch.object(cast.shutil, "which", side_effect=lambda n: "/usr/bin/" + n if n == "gpu-screen-recorder" else None):
            argv = cast.screen_argv({})
        self.assertEqual(argv[:5], ["gpu-screen-recorder", "-w", "region", "-region", "1280x720+320+180"])
        with mock.patch.dict(omastorm.os.environ, {"OMARCHY_LINK_SCREEN_REGION": "1;rm -rf"}), \
             mock.patch.object(cast.shutil, "which", side_effect=lambda n: "/usr/bin/" + n if n == "wf-recorder" else None):
            self.assertNotIn("-g", cast.screen_argv({}))   # junk region: the normal whole-screen path


class Snapshot(unittest.TestCase):
    def test_jpeg_of_the_window_only(self):
        jpg = b"\xff\xd8\xff" + b"x" * 100
        with mock.patch.object(omastorm.shutil, "which", return_value="/usr/bin/x"), \
             mock.patch.object(omastorm, "find_window", return_value=WIN), \
             mock.patch.object(omastorm.subprocess, "run", return_value=mock.Mock(returncode=0, stdout=jpg)) as run:
            self.assertEqual(omastorm.snapshot({}), jpg)
            self.assertEqual(run.call_args[0][0][:3], ["grim", "-g", "320,180 1280x720"])
        with mock.patch.object(omastorm.shutil, "which", return_value="/usr/bin/x"), \
             mock.patch.object(omastorm, "find_window", return_value=WIN), \
             mock.patch.object(omastorm.subprocess, "run", return_value=mock.Mock(returncode=0, stdout=b"<html>")):
            self.assertIsNone(omastorm.snapshot({}))
        self.assertIsNone(omastorm.snapshot({"omastormFromTv": False}))


class SameKeysAsTheTv(unittest.TestCase):
    def test_every_tv_key_name_is_known_here(self):
        import re
        from pathlib import Path
        java = (Path(__file__).resolve().parents[3] / "launcher/app/src/main/java/com/omarchytv/home/OmastormKeys.java").read_text()
        names = set(re.findall(r'return "([a-z_]+)";', java))
        self.assertTrue(names)
        self.assertEqual(names - set(omastorm.KEYS), set())


class Start(unittest.TestCase):
    def test_start_streams_the_window_and_keys_go_to_it(self):
        st = omastorm.Session()
        calls = []
        with mock.patch.object(omastorm.shutil, "which", return_value="/usr/bin/x"), \
             mock.patch.object(omastorm, "find_window", return_value=WIN), \
             mock.patch.object(omastorm, "_hypr", side_effect=lambda *a, **k: calls.append(a) or ""), \
             mock.patch.object(omastorm.time, "sleep"), \
             mock.patch.object(omastorm.subprocess, "Popen") as po:
            po.return_value.poll.return_value = None
            self.assertEqual(st.start({}), "ok")
            argv, kw = po.call_args[0][0], po.call_args[1]
            self.assertEqual(argv[1:], ["cast", "--screen", "--via", "link", "--title", "Omastorm"])
            self.assertEqual(kw["env"]["OMARCHY_LINK_SCREEN_REGION"], "320,180 1280x720")
            self.assertIn(("dispatch", "resizewindowpixel", "exact 1280 720,address:0x55aa"), calls)
            self.assertTrue(st.key("next"))
            self.assertIn(("dispatch", "sendshortcut", ", bracketright, address:0x55aa"), calls)
            st.stop()
            self.assertIn(("dispatch", "sendshortcut", ", Escape, address:0x55aa"), calls)
        self.assertFalse(st.key("next"))

    def test_refusals(self):
        st = omastorm.Session()
        self.assertEqual(st.start({"omastormFromTv": False}), "disabled")
        with mock.patch.object(omastorm.shutil, "which", return_value=None):
            self.assertEqual(st.start({}), "no_omarchy")
        with mock.patch.object(omastorm.shutil, "which", return_value="/usr/bin/x"), \
             mock.patch.object(omastorm, "find_window", return_value=None), \
             mock.patch.object(omastorm.time, "sleep"), mock.patch.object(omastorm.subprocess, "Popen"):
            self.assertEqual(st.start({}), "not_installed")

    def test_session_event_reports_failures(self):
        s = session.Session.__new__(session.Session)
        s.cfg, s.logs = {"omastormFromTv": False}, []
        s.log = s.logs.append
        s.stream = mock.Mock()
        s.handle_event({"type": "omastorm", "data": {"action": "start"}})
        s.stream.send.assert_called_once_with("omastorm_state", {"state": "disabled"})


if __name__ == "__main__":
    unittest.main()
