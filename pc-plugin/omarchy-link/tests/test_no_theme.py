"""A PC without an Omarchy theme (macOS, a Linux without Omarchy): no theme is pushed, the TV keeps its own."""
import shutil
import unittest

import helpers  # noqa: F401  (hermetic env + sys.path)
from omarchy_link import config, panes, sync, theme


class FakeClient:
    def __init__(self):
        self.tv = {"id": "tv-1"}
        self.calls = []

    def wallpaper_info(self):
        return {"sha256": None}

    def put_wallpaper(self, jpeg):
        self.calls.append("wallpaper")

    def put_theme(self, payload):
        self.calls.append(("theme", payload))


class NoTheme(unittest.TestCase):
    def setUp(self):
        helpers.reset_pc_state()
        shutil.rmtree(helpers.OMARCHY, ignore_errors=True)   # nothing under ~/.local/state/omarchy/current
        config.SYNC_FILE.unlink(missing_ok=True)

    def test_name_is_empty_not_unknown(self):
        self.assertEqual(theme.theme_name(), "")
        self.assertFalse(theme.has_theme())
        helpers.set_theme("gruvbox", helpers.GRUV, "0x282828", "1920x1200")
        self.assertEqual(theme.theme_name(), "gruvbox")
        self.assertTrue(theme.has_theme())

    def test_colors_without_a_name_still_count(self):
        (helpers.OMARCHY / "theme").mkdir(parents=True)
        (helpers.OMARCHY / "theme/colors.toml").write_text(helpers.TOKYO)
        self.assertTrue(theme.has_theme())
        self.assertEqual(theme.theme_payload()["name"], "unknown")   # as before: a palette with no name

    def test_sync_sends_nothing_and_remembers(self):
        c, logs = FakeClient(), []
        self.assertIsNone(sync.sync_theme(c, config.load_config(), True, logs.append))
        self.assertEqual(c.calls, [])
        self.assertEqual(logs, ["no Omarchy theme on this PC; the TV keeps its own"])
        # the fingerprint is recorded, so the session's 3 s check does not log this again
        self.assertEqual(config.read_json(config.SYNC_FILE, {})["byTv"]["tv-1"], theme.fingerprint())
        logs.clear()
        self.assertIsNone(sync.sync_theme(c, config.load_config(), False, logs.append))
        self.assertEqual(logs, [])
        # a theme set later is pushed as always
        helpers.set_theme("gruvbox", helpers.GRUV, "0x282828", "1920x1200")
        self.assertEqual(sync.sync_theme(c, {**config.load_config(), "wallpaperSync": False}, False, logs.append)["name"], "gruvbox")
        self.assertEqual(c.calls[-1][0], "theme")

    def test_pc_pane_has_no_theme_line(self):
        self.assertNotIn("Theme", [l["label"] for l in panes.pc_pane()["lines"]])
        helpers.set_theme("tokyo-night", helpers.TOKYO)
        self.assertIn({"label": "Theme", "value": "Tokyo Night"}, panes.pc_pane()["lines"])


if __name__ == "__main__":
    unittest.main()
