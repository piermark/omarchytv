"""choose(): omarchy-menu-select (Omarchy 4.5+) > walker --dmenu > gum > plain, with fake binaries (no desktop needed)."""
import builtins, os, sys, tempfile, unittest
from pathlib import Path
from unittest import mock
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "lib"))
from omarchy_tv import ui


def fake(path: Path, body: str):
    path.write_text("#!/bin/bash\n" + body + "\n")
    path.chmod(0o755)


class ChooseTest(unittest.TestCase):
    def setUp(self):
        self.td = tempfile.TemporaryDirectory()
        r = Path(self.td.name)
        self.log = r / "log"
        self.obin = r / "omarchy-bin"; self.obin.mkdir()      # stands in for ~/.local/share/omarchy/bin
        self.pbin = r / "path-bin"; self.pbin.mkdir()         # stands in for the rest of PATH
        self.env = {"PATH": f"{self.pbin}:/usr/bin:/bin", "HOME": str(r)}
        self.p_bin = mock.patch.object(ui, "OMARCHY_BIN", self.obin)
        self.p_bin.start()

    def tearDown(self):
        self.p_bin.stop()
        self.td.cleanup()

    def menu_select(self, body):
        fake(self.obin / "omarchy-menu-select", f'printf "%s\\n" "$@" > {self.log}; echo "PATH=$PATH" >> {self.log}\n{body}')

    def walker(self):
        fake(self.pbin / "walker", f'echo walker >> {self.log}.w; head -n1')

    def choose(self, env, items=("Stato", "Temi", "Esci")):
        with mock.patch.dict(os.environ, env, clear=True):
            return ui.choose("OmarchyTV", list(items))

    def test_menu_select_from_omarchy_bin_via_keybind_without_wayland_display(self):
        self.menu_select('echo Temi')
        self.walker()
        got = self.choose({**self.env, "HYPRLAND_INSTANCE_SIGNATURE": "abc"})
        self.assertEqual(got, "Temi")
        args = self.log.read_text().splitlines()
        self.assertEqual(args[:6], ["OmarchyTV", "Stato", "Temi", "Esci", "--", "--width"])
        self.assertIn(str(self.obin), args[-1])                 # omarchy-shell must be reachable
        self.assertFalse(Path(f"{self.log}.w").exists())

    def test_menu_select_on_path_preferred(self):
        fake(self.pbin / "omarchy-menu-select", f'echo onpath > {self.log}; echo Stato')
        self.assertEqual(self.choose({**self.env, "WAYLAND_DISPLAY": "wayland-1"}), "Stato")
        self.assertEqual(self.log.read_text().strip(), "onpath")

    def test_subtext_is_stripped(self):
        self.menu_select('printf "Temi\\tsub\\n"')
        self.assertEqual(self.choose({**self.env, "WAYLAND_DISPLAY": "wayland-1"}), "Temi")

    def test_cancel_returns_none_without_fallback(self):
        self.menu_select('exit 1')
        self.walker()
        self.assertIsNone(self.choose({**self.env, "WAYLAND_DISPLAY": "wayland-1"}))
        self.assertFalse(Path(f"{self.log}.w").exists())

    def test_shell_unreachable_falls_back_to_walker(self):
        self.menu_select('exit 2')
        self.walker()
        self.assertEqual(self.choose({**self.env, "WAYLAND_DISPLAY": "wayland-1"}), "Stato")
        self.assertTrue(Path(f"{self.log}.w").exists())

    def test_old_omarchy_uses_walker(self):
        self.walker()
        self.assertEqual(self.choose({**self.env, "WAYLAND_DISPLAY": "wayland-1"}), "Stato")

    def test_ssh_uses_text_menu(self):
        self.menu_select('echo Temi')
        self.walker()
        with mock.patch.object(builtins, "input", return_value="3"), mock.patch("os.isatty", return_value=False):
            got = self.choose({**self.env, "SSH_CONNECTION": "1 2 3 4"})
        self.assertEqual(got, "Esci")
        self.assertFalse(self.log.exists())
        self.assertFalse(Path(f"{self.log}.w").exists())

    def test_no_terminal_no_menu_returns_none(self):
        with mock.patch.object(builtins, "input", side_effect=EOFError), mock.patch("os.isatty", return_value=False):
            self.assertIsNone(self.choose({**self.env, "SSH_TTY": "/dev/pts/1"}))


class ColorsTest(unittest.TestCase):
    def test_colors_prefer_the_state_dir(self):
        with tempfile.TemporaryDirectory() as td:
            home = Path(td)
            state = home / ".local/state/omarchy/current/theme"
            legacy = home / ".config/omarchy/current/theme"
            state.mkdir(parents=True)
            legacy.mkdir(parents=True)
            (state / "colors.toml").write_text('accent = "#112233"\n')
            (legacy / "colors.toml").write_text('accent = "#aabbcc"\n')
            with mock.patch.object(ui.Path, "home", return_value=home):
                self.assertEqual(ui.colors()["accent"], "#112233")


if __name__ == "__main__":
    unittest.main()
