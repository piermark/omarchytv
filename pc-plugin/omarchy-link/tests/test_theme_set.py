"""Reverse theme sync: the TV picks a theme and the PC follows (installed themes only, no shell, rate limited)."""
import unittest
from unittest import mock

import helpers  # noqa: F401  (hermetic env + sys.path)
from omarchy_link import config, session, theme


class SetTheme(unittest.TestCase):
    def setUp(self):
        theme._last_set = 0.0
        self.dir = config.HOME / ".config/omarchy/themes"
        for n in ("tokyo-night", "gruvbox", "Bad Name"):
            (self.dir / n).mkdir(parents=True, exist_ok=True)
        self.p = mock.patch.object(theme, "THEMES_DIR", self.dir)
        self.p.start()
        self.addCleanup(self.p.stop)

    def test_installed_themes_skips_bad_names(self):
        self.assertIn("gruvbox", theme.installed_themes())
        self.assertNotIn("Bad Name", theme.installed_themes())

    def test_unknown_names_never_run_anything(self):
        with mock.patch.object(theme.subprocess, "Popen") as po:
            for n in ("nord", "../etc", "gruvbox; rm -rf ~", "", None, 42, "Bad Name", "a" * 80):
                self.assertEqual(theme.set_theme(n, now=100.0), "unknown", n)
            po.assert_not_called()

    def test_ok_runs_omarchy_theme_set_without_a_shell(self):
        with mock.patch.object(theme, "theme_name", lambda: "tokyo-night"), \
             mock.patch.object(theme.subprocess, "Popen") as po:
            self.assertEqual(theme.set_theme("gruvbox", now=100.0), "ok")
            argv = po.call_args[0][0]
            self.assertEqual(argv, ["omarchy-theme-set", "gruvbox"])
            self.assertNotIn("shell", po.call_args[1])

    def test_same_busy_and_failed(self):
        with mock.patch.object(theme, "theme_name", lambda: "gruvbox"), \
             mock.patch.object(theme.subprocess, "Popen") as po:
            self.assertEqual(theme.set_theme("gruvbox", now=100.0), "same")
            self.assertEqual(theme.set_theme("tokyo-night", now=100.0), "ok")
            self.assertEqual(theme.set_theme("tokyo-night", now=101.0), "busy")
            self.assertEqual(po.call_count, 1)
        with mock.patch.object(theme, "theme_name", lambda: "gruvbox"), \
             mock.patch.object(theme.subprocess, "Popen", side_effect=OSError):
            self.assertEqual(theme.set_theme("tokyo-night", now=200.0), "failed")


class Handler(unittest.TestCase):
    def run_handler(self, cfg, data):
        s = session.Session.__new__(session.Session)
        s.cfg, s.logs = cfg, []
        s.log = s.logs.append
        with mock.patch.object(theme, "set_theme", return_value="ok") as st:
            s.handle_theme_set(data)
        return st, s.logs

    def test_follows_the_tv_by_default(self):
        st, _ = self.run_handler({}, {"name": "gruvbox"})
        st.assert_called_once_with("gruvbox")

    def test_theme_from_tv_off(self):
        st, logs = self.run_handler({"themeFromTv": False}, {"name": "gruvbox"})
        st.assert_not_called()
        self.assertTrue(logs)


if __name__ == "__main__":
    unittest.main()
