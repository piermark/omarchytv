"""Omastorm on the TV (v2.14): the real Omastorm radar window (Wes Grimes' Omarchy plugin, MIT,
https://omastorm.com) from this PC, exactly as it looks here, streamed to the TV and driven by its remote.

The TV ("omastorm" event, plugin on there) asks to start: this PC summons the Omastorm window, makes it 1280x720,
streams just that window's rectangle to the TV (the experimental screen cast, region only) and turns remote keys
into Omastorm's own key bindings (hyprctl sendshortcut to that window only). Stop: the stream ends and the window
closes. Needs Omastorm installed (`omarchy plugin add https://github.com/wesleygrimes/omastorm.git --enable`),
Hyprland and gpu-screen-recorder or wf-recorder. "omastormFromTv": false refuses.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import time

PLUGIN_ID = "com.omastorm.radar"
TITLE = "Omastorm"
SIZE = (1280, 720)
# TV key name -> (modifiers, xkb key) from Omastorm's ui/Keys.js default bindings.
KEYS = {
    "left": ("", "Left"), "right": ("", "Right"), "up": ("", "Up"), "down": ("", "Down"),
    "play": ("", "space"), "next": ("", "bracketright"), "prev": ("", "bracketleft"),
    "oldest": ("", "Home"), "newest": ("", "End"),
    "zoom_in": ("", "equal"), "zoom_out": ("", "minus"), "reset": ("", "0"),
    "pixels": ("", "1"), "glyphs": ("", "2"), "stipple": ("", "3"),
    "nearest": ("", "n"), "mosaic": ("SHIFT", "N"), "locate": ("", "m"),
    "aviation": ("", "a"), "focus": ("", "f"), "weak": ("", "w"), "help": ("SHIFT", "slash"), "close": ("", "Escape"),
}


def _hypr(*args: str, timeout: float = 5) -> str:
    try:
        return subprocess.run(["hyprctl", *args], capture_output=True, text=True, timeout=timeout,
                              stdin=subprocess.DEVNULL).stdout
    except (OSError, subprocess.SubprocessError):
        return ""


def find_window() -> dict | None:
    try:
        clients = json.loads(_hypr("clients", "-j") or "[]")
    except ValueError:
        return None
    for c in clients if isinstance(clients, list) else []:
        if isinstance(c, dict) and c.get("title") == TITLE and c.get("mapped", True):
            return c
    return None


def region(win: dict) -> str:
    """wf-recorder/slurp geometry "X,Y WxH" of the window (logical pixels)."""
    (x, y), (w, h) = win["at"], win["size"]
    return f"{int(x)},{int(y)} {int(w)}x{int(h)}"


def key_shortcut(name: str, address: str) -> str | None:
    k = KEYS.get(name)
    if not k or not str(address).startswith("0x"):
        return None
    return f"{k[0]}, {k[1]}, address:{address}"


class Session:
    """One Omastorm stream at a time (per omarchy-link process)."""

    def __init__(self, log=lambda m: None):
        self.log = log
        self.proc = None
        self.address = ""

    def running(self) -> bool:
        return self.proc is not None and self.proc.poll() is None

    def start(self, cfg: dict) -> str:
        if cfg.get("omastormFromTv", True) is False:
            return "disabled"
        if self.running():
            return "running"
        if not shutil.which("hyprctl") or not shutil.which("omarchy"):
            return "no_omarchy"
        win = find_window()
        if not win:
            subprocess.Popen(["omarchy", "shell", "shell", "summon", PLUGIN_ID, "{}"], stdin=subprocess.DEVNULL,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
            for _ in range(40):
                time.sleep(0.2)
                win = find_window()
                if win:
                    break
        if not win:
            return "not_installed"
        self.address = win.get("address", "")
        if win.get("floating"):
            _hypr("dispatch", "resizewindowpixel", f"exact {SIZE[0]} {SIZE[1]},address:{self.address}")
            _hypr("dispatch", "centerwindow")
            time.sleep(0.3)
            win = find_window() or win
        exe = os.environ.get("OMARCHY_LINK_BIN", sys.argv[0])
        env = dict(os.environ, OMARCHY_LINK_SCREEN_REGION=region(win))
        try:
            self.proc = subprocess.Popen([exe, "cast", "--screen", "--via", "link", "--title", TITLE], env=env,
                                         stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                         stderr=subprocess.DEVNULL, start_new_session=True)
        except OSError:
            return "failed"
        self.log(f"Omastorm on the TV: streaming {region(win)}")
        return "ok"

    def key(self, name: str) -> bool:
        sc = key_shortcut(name, self.address)
        if not sc or not self.running():
            return False
        _hypr("dispatch", "sendshortcut", sc)
        return True

    def stop(self) -> None:
        if self.running():
            self.proc.terminate()
        self.proc = None
        if self.address:
            _hypr("dispatch", "sendshortcut", f", Escape, address:{self.address}")
        self.address = ""
