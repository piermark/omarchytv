"""Paths, user config and pairing state. Secrets live only in owner-only files."""

from __future__ import annotations

import json
import os
import secrets
import socket
import stat
import tempfile
from pathlib import Path

HOME = Path.home()


def _xdg(var: str, default: str) -> Path:
    return Path(os.environ.get(var) or (HOME / default))


CONFIG_DIR = _xdg("XDG_CONFIG_HOME", ".config") / "omarchy-link"
STATE_DIR = _xdg("XDG_STATE_HOME", ".local/state") / "omarchy-link"
CACHE_DIR = _xdg("XDG_CACHE_HOME", ".cache") / "omarchy-link"
RUNTIME_DIR = Path(os.environ.get("XDG_RUNTIME_DIR") or tempfile.gettempdir()) / f"omarchy-link-{os.getuid()}"

CONFIG_FILE = CONFIG_DIR / "config.json"
WIDGETS_FILE = CONFIG_DIR / "widgets.json"
AI_FILE = CONFIG_DIR / "ai.json"
PAIRING_FILE = STATE_DIR / "pairing.json"
SYNC_FILE = STATE_DIR / "last-sync.json"

OMARCHY_STATE = HOME / ".local/state/omarchy/current"

DEFAULTS = {
    "themeSync": True,          # push colours on theme change
    "wallpaperSync": True,      # and the current background
    "themeFromTv": True,
    "mediaFromTv": True,        # Cinemarchy may browse and play this PC's media folders (XDG Videos, Music, Pictures, Download, ~/Movies)
    "powerFromTv": True,        # TV off -> suspend after powerGraceS; TV on -> it wakes this PC (TV side: off until enabled)
    "powerGraceS": 60,
    "updateFromTv": True,       # LG/Samsung "Update via PC" (omarchy-tv webos-serve) may install official releases
    "livingToTv": True,         # Living screensaver feed (the TV asks for notifications / GitHub / X; off there by default)
    "githubUser": None,         # default: gh's signed-in login
    "githubRepos": [],          # e.g. ["piermark/omarchytv"]: stars, forks, PRs on your repos
    "xApi": True,               # X API mentions when the TV picks "X: API" (needs `omarchy-link x setup`)
    "xPollMinutes": 3,          # only while the TV screensaver runs, only new mentions
    "xMonthlyUsd": 1.0,         # estimated X API spend per month; polling stops at this
    "omastormFromTv": True,     # the TV's Omastorm plugin may show this PC's Omastorm window (stream of that window only)
    "liftoffToTv": True,        # show `omarchy-link liftoff -- <build>` and TV updates as a countdown on the TV        # the TV may switch the PC's theme ("theme_set"; the TV asks only when its own setting is on)
    "pcPane": True,             # push the "this PC" info pane every minute while connected
    "aiCredits": True,          # push ai.json providers (if the file exists)
    "aiRefreshMinutes": 15,
    "aiSourcesFromTv": True,
    "castPrefer": "cast",       # Send to TV: "cast" (Google Cast via catt) or "link" (OmarchyTV) first
    "castDevice": None,         # Cast device name; None = the only one / the one named like the TV
    "castServe": False,         # allow a temporary TV-only port for local files (also --serve)
    "agentMonitor": {"enabled": False},  # live agent feed on the TV (docs/AGENTS.md); off until enabled
    "agentControlFromTv": False,  # let the TV send instructions to an idle agent session
    "agentConfirm": True,       # ...and only after a click on a PC notification
    "agentToolApprovalFromTv": False,  # never let TV instructions auto-approve tool permissions
    "prompt": None,             # {"user", "host"} for the TV's home prompt; None = not sent    # the TV may switch mirror sources on/off ("ai_sources" event)
    "assistant": True,          # answer voice questions from the TV (assistant.json, signed-in CLIs)
    "allowOtherSubnets": False,
    "actions": {},              # id -> {"label", "exec": [argv], "confirm": true}
}


def ensure_private_dir(path: Path) -> Path:
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(path, 0o700)
    return path


def read_json(path: Path, default):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return default
    except (OSError, ValueError) as e:
        raise ValueError(f"cannot read {path}: {e}") from e


def write_json_private(path: Path, data) -> None:
    """Atomic write, mode 0600, parent 0700."""
    ensure_private_dir(path.parent)
    fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=".tmp-")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, sort_keys=True)
            f.write("\n")
        os.chmod(tmp, 0o600)
        os.replace(tmp, path)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


def is_private_file(path: Path) -> bool:
    st = path.stat()
    return st.st_uid == os.getuid() and not st.st_mode & (stat.S_IRWXG | stat.S_IRWXO)


def load_config() -> dict:
    cfg = dict(DEFAULTS)
    user = read_json(CONFIG_FILE, {})
    if isinstance(user, dict):
        cfg.update(user)
    return cfg


def save_config(changes: dict) -> dict:
    """Merge `changes` into config.json (None deletes a key)."""
    cur = read_json(CONFIG_FILE, {})
    cur = cur if isinstance(cur, dict) else {}
    for k, v in changes.items():
        if v is None:
            cur.pop(k, None)
        else:
            cur[k] = v
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    write_json_private(CONFIG_FILE, cur)
    return cur


class Pairing:
    """~/.local/state/omarchy-link/pairing.json (0600): one TV for now."""

    def __init__(self, data: dict):
        self.data = data

    @classmethod
    def load(cls) -> "Pairing":
        data = read_json(PAIRING_FILE, {})
        if data and not is_private_file(PAIRING_FILE):
            os.chmod(PAIRING_FILE, 0o600)
        if not data.get("pcId"):
            data["pcId"] = secrets.token_hex(16)
        data.setdefault("pcName", socket.gethostname())
        return cls(data)

    def save(self) -> None:
        write_json_private(PAIRING_FILE, self.data)

    @property
    def pc_id(self) -> str:
        return self.data["pcId"]

    @property
    def pc_name(self) -> str:
        return self.data["pcName"]

    @property
    def tv(self) -> dict | None:
        return self.data.get("tv")

    @property
    def paired(self) -> bool:
        return bool(self.tv and self.tv.get("token"))

    @property
    def tvs(self) -> list[dict]:
        """Every paired TV, primary first (2.13 multi-device theme sync). Extras live under "tvs"."""
        out, seen = [], set()
        for t in [self.tv] + list(self.data.get("tvs") or []):
            if t and t.get("token") and t.get("id") not in seen:
                seen.add(t.get("id")); out.append(t)
        return out

    def set_tv(self, tv: dict) -> None:
        old = self.data.get("tv")
        extras = [t for t in (self.data.get("tvs") or []) if t.get("id") != tv.get("id")]
        if old and old.get("token") and old.get("id") != tv.get("id"):
            extras = [t for t in extras if t.get("id") != old.get("id")] + [old]  # keep the previous TV paired
        if extras:
            self.data["tvs"] = extras
        else:
            self.data.pop("tvs", None)
        self.data["tv"] = tv
        self.save()

    def forget(self) -> None:
        """Forget the primary TV; the next extra TV (if any) becomes primary."""
        self.data.pop("tv", None)
        extras = list(self.data.get("tvs") or [])
        if extras:
            self.data["tv"] = extras.pop(0)
            if extras:
                self.data["tvs"] = extras
            else:
                self.data.pop("tvs", None)
        self.save()
