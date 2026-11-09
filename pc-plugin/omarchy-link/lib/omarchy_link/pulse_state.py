"""OmarchyTV 2.15: Pulse on the TV, the PC's half.

When Omarchy Pulse runs on this PC, its hub (omarchy-pulse) already knows everything the TV's own Pulse can't: your AI
agents, the Hyprland workspaces, your repos, your panels and [[source]]s, the updates, the planes over you... This reads
the hub's state (on this machine only, with its own token) and hands the TV a copy without what is private: the
notifications and any panel marked private, the calendar's titles, the names of the devices on your network, the
terminal lines. The TV is in the living room; everyone sees it.
"""
from __future__ import annotations

import json
import os
import urllib.parse
import urllib.request
from pathlib import Path

DEFAULT_PORT = 47800
MAX_BYTES = 256 * 1024


def _conf_dir() -> Path:
    return Path(os.environ.get("XDG_CONFIG_HOME") or Path.home() / ".config") / "omarchy-pulse"


def _port() -> int:
    if os.environ.get("PULSE_PORT"):
        try:
            return int(os.environ["PULSE_PORT"])
        except ValueError:
            pass
    try:
        import tomllib   # noqa: PLC0415
        with open(_conf_dir() / "pulse.toml", "rb") as f:
            return int(((tomllib.load(f).get("pulse") or {}).get("port")) or DEFAULT_PORT)
    except Exception:   # noqa: BLE001 - no pulse.toml, or no port in it
        return DEFAULT_PORT


def _token() -> str:
    try:
        return (_conf_dir() / "token").read_text().strip()
    except OSError:
        return ""


def for_tv(st: dict) -> dict:
    """The hub's state without anything private (see the module's docstring)."""
    if not isinstance(st, dict) or not st.get("v"):
        return {}
    out = dict(st)
    out["widgets"] = [w for w in (st.get("widgets") or []) if isinstance(w, dict) and not w.get("private")
                      and w.get("id") not in ("notifications", "doorbell")
                      and (w.get("type") != "image")]   # pictures are served by the hub, which the TV can't reach
    out["calendar"] = [{**e, "title": ""} for e in (st.get("calendar") or []) if isinstance(e, dict)]
    out["lan"] = []
    out["term"] = []
    out["listen"] = None
    out["heart"] = None
    for k in ("sceneTicket", "scene", "mons", "layoutEdit", "perfProbe", "timelapse"):
        out.pop(k, None)
    return out


def read(timeout: float = 2.0) -> dict | None:
    """The PC's Pulse state for the TV, or None when Pulse isn't running (or doesn't answer)."""
    tok = _token()
    if not tok:
        return None
    url = f"http://127.0.0.1:{_port()}/api/state?token={urllib.parse.quote(tok)}"
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:   # noqa: S310 - our own hub, on this machine
            raw = r.read(MAX_BYTES + 1)
        if len(raw) > MAX_BYTES:
            return None
        st = for_tv(json.loads(raw.decode("utf-8")))
        return st or None
    except Exception:   # noqa: BLE001 - Pulse off, restarting, or not installed
        return None

