"""Menus and output in the Omarchy style, colours from the active theme.

Menu order: omarchy-menu-select (Omarchy 4.5+, the Quickshell menu) > walker --dmenu (older Omarchy) > gum > plain.
"""
import os, re, shutil, subprocess
from .i18n import t
from pathlib import Path

def _colors_file() -> Path:
    """Live Omarchy writes the palette under the state dir. The config path is the older location."""
    home = Path.home()
    state = home / ".local/state/omarchy/current/theme/colors.toml"
    legacy = home / ".config/omarchy/current/theme/colors.toml"
    return state if state.is_file() else legacy


def colors() -> dict:
    out = {"accent": "#7aa2f7", "foreground": "#c0caf5", "background": "#1a1b26"}
    try:
        for line in _colors_file().read_text().splitlines():
            m = re.match(r'\s*([a-z_0-9]+)\s*=\s*"(#[0-9a-fA-F]{6})"', line)
            if m:
                out[m.group(1)] = m.group(2)
    except OSError:
        pass
    return out


OMARCHY_BIN = Path.home() / ".local/share/omarchy/bin"


def _menu_select() -> str | None:
    """omarchy-menu-select on PATH, else in ~/.local/share/omarchy/bin (where Omarchy installs it)."""
    found = shutil.which("omarchy-menu-select")
    if found:
        return found
    f = OMARCHY_BIN / "omarchy-menu-select"
    return str(f) if f.is_file() and os.access(f, os.X_OK) else None


def _graphical() -> bool:
    """True inside the Omarchy desktop, also when started from a Hyprland keybind without WAYLAND_DISPLAY.
    Over SSH (no local Wayland/Hyprland session in the environment) the text menus are used."""
    e = os.environ
    if e.get("WAYLAND_DISPLAY") or e.get("HYPRLAND_INSTANCE_SIGNATURE"):
        return True
    if e.get("SSH_CONNECTION") or e.get("SSH_TTY"):
        return False
    return e.get("XDG_SESSION_TYPE") == "wayland" or bool(e.get("XDG_RUNTIME_DIR") and e.get("XDG_CURRENT_DESKTOP"))


def _omarchy_env() -> dict:
    """omarchy-menu-select calls omarchy-shell, so its bin dir must be on PATH even if ours lacks it."""
    env = dict(os.environ)
    if OMARCHY_BIN.is_dir() and str(OMARCHY_BIN) not in env.get("PATH", "").split(os.pathsep):
        env["PATH"] = str(OMARCHY_BIN) + os.pathsep + env.get("PATH", "")
    return env


def choose(prompt: str, items: list[str]) -> str | None:
    data = "\n".join(items)
    gui = _graphical()
    sel = _menu_select() if gui else None
    if sel:
        # Interface (Omarchy 4.5): omarchy-menu-select <prompt> [option...] [-- --width N --maxheight N ...]
        # prints the chosen label; exit 1 = cancelled, exit 2 = the Omarchy shell could not be reached.
        r = subprocess.run([sel, prompt, *items, "--", "--width", "400"], text=True, capture_output=True,
                           env=_omarchy_env())
        if r.returncode == 0:
            return r.stdout.strip().split("\t")[0] or None
        if r.returncode == 1:
            return None
    if gui and shutil.which("walker"):
        r = subprocess.run(["walker", "--dmenu", "--width", "295", "--minheight", "1", "--maxheight", "630", "-p", prompt],
                           input=data, text=True, capture_output=True)
        return r.stdout.strip() or None
    if shutil.which("gum") and os.isatty(0):
        r = subprocess.run(["gum", "choose", "--header", prompt, "--cursor.foreground", colors()["accent"], *items],
                           text=True, stdout=subprocess.PIPE)
        return r.stdout.strip() or None
    for i, it in enumerate(items, 1):
        print(f"{i}) {it}")
    try:
        s = input(prompt + " ")
    except EOFError:
        return None
    return items[int(s) - 1] if s.isdigit() and 0 < int(s) <= len(items) else None


def ask(prompt: str) -> str:
    if shutil.which("gum"):
        return subprocess.run(["gum", "input", "--placeholder", prompt], text=True, stdout=subprocess.PIPE).stdout.strip()
    return input(prompt + ": ").strip()


def confirm(prompt: str) -> bool:
    if shutil.which("gum"):
        return subprocess.run(["gum", "confirm", prompt]).returncode == 0
    return input(prompt + t("confirm.suffix")).strip().lower() in ("s", "y", "si", "yes")


def say(msg: str):
    if shutil.which("gum"):
        subprocess.run(["gum", "style", "--foreground", colors()["accent"], msg])
    else:
        print(msg)


def notify(msg: str, urgency: str = "normal"):
    """notify-send (mako on Omarchy), like the omarchy-* scripts; silent without a notification daemon."""
    if shutil.which("notify-send"):
        subprocess.run(["notify-send", "-u", urgency, "-a", "OmarchyTV", "OmarchyTV", msg], capture_output=True)


def run(cmd: list[str], env=None) -> int:
    if cmd[0] == "omarchy-link" and not shutil.which(cmd[0]) and os.environ.get("OMARCHY_LINK_BIN"):
        cmd = [os.environ["OMARCHY_LINK_BIN"]] + cmd[1:]
    if not shutil.which(cmd[0]) and not os.path.isfile(cmd[0]):
        say(t("not_found", cmd=cmd[0]))
        return 1
    return subprocess.run(cmd, env=env).returncode
