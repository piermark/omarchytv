"""Info panes and shortcuts pushed to the TV (PUT /v1/widgets)."""

from __future__ import annotations

import os
import re
import shutil
import socket
import subprocess

from . import config, theme

ID_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{0,47}$")
PKG_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$")
MAX_PANES, MAX_LINES, MAX_SHORTCUTS = 8, 8, 16


def _text(v, n=48) -> str:
    return "".join(ch for ch in str(v) if ch.isprintable())[:n]


def pc_pane() -> dict:
    lines = []
    try:
        load1 = os.getloadavg()[0]
        lines.append({"label": "CPU", "value": f"{min(100, round(load1 / (os.cpu_count() or 1) * 100))}%"})
    except OSError:
        pass
    try:
        mem = {}
        with open("/proc/meminfo") as f:
            for line in f:
                k, _, v = line.partition(":")
                mem[k] = int(v.split()[0])
        used = 1 - mem["MemAvailable"] / mem["MemTotal"]
        lines.append({"label": "RAM", "value": f"{round(used * 100)}% of {mem['MemTotal'] / 1048576:.0f} GB"})
    except (OSError, KeyError, ValueError, ZeroDivisionError):
        pass
    try:
        with open("/proc/uptime") as f:
            up = int(float(f.read().split()[0]))
        lines.append({"label": "Up", "value": f"{up // 86400}d {up % 86400 // 3600}h" if up >= 86400 else f"{up // 3600}h {up % 3600 // 60}m"})
    except (OSError, ValueError):
        pass
    if theme.theme_name():   # a PC without an Omarchy theme (macOS) has no line to show
        lines.append({"label": "Theme", "value": theme.theme_name().replace("-", " ").title()})
    if shutil.which("playerctl"):
        try:
            r = subprocess.run(["playerctl", "metadata", "--format", "{{status}}\t{{artist}} - {{title}}"],
                               capture_output=True, text=True, timeout=2)
            status, _, track = r.stdout.strip().partition("\t")
            if r.returncode == 0 and status in ("Playing", "Paused") and track.strip(" -"):
                lines.append({"label": "♪" if status == "Playing" else "॥", "value": track.strip(" -")})
        except (OSError, subprocess.SubprocessError):
            pass
    return {"id": "pc", "title": socket.gethostname(), "icon": "omarchy", "lines": lines[:MAX_LINES], "ttl": 180}


def validate(widgets: dict, actions: dict) -> dict:
    """Clean a widgets.json document; raises ValueError on anything unsafe."""
    if not isinstance(widgets, dict):
        raise ValueError("widgets must be an object")
    out = {"panes": [], "shortcuts": []}
    for p in (widgets.get("panes") or [])[:MAX_PANES]:
        if not ID_RE.match(str(p.get("id", ""))):
            raise ValueError(f"bad pane id {p.get('id')!r}")
        out["panes"].append({
            "id": p["id"], "title": _text(p.get("title", p["id"])), "icon": _text(p.get("icon", ""), 16),
            "lines": [{"label": _text(l.get("label", ""), 16), "value": _text(l.get("value", ""), 64)}
                      for l in (p.get("lines") or [])[:MAX_LINES]],
            "ttl": max(60, min(int(p.get("ttl", 600)), 86400)),
        })
    for s in (widgets.get("shortcuts") or [])[:MAX_SHORTCUTS]:
        if not ID_RE.match(str(s.get("id", ""))):
            raise ValueError(f"bad shortcut id {s.get('id')!r}")
        a = s.get("action") or {}
        kind = a.get("type")
        if kind == "app":
            if not PKG_RE.match(str(a.get("package", ""))):
                raise ValueError(f"shortcut {s['id']}: bad package")
            action = {"type": "app", "package": a["package"]}
        elif kind == "view":
            uri = str(a.get("uri", ""))
            if not re.match(r"^(https://|vnd\.youtube:|nflx://)", uri) or len(uri) > 512:
                raise ValueError(f"shortcut {s['id']}: uri must be https://, vnd.youtube: or nflx://")
            action = {"type": "view", "uri": uri}
            if a.get("package"):
                if not PKG_RE.match(str(a["package"])):
                    raise ValueError(f"shortcut {s['id']}: bad package")
                action["package"] = a["package"]
        elif kind == "pc":
            cmd = str(a.get("command", ""))
            if cmd not in actions:
                raise ValueError(f"shortcut {s['id']}: PC action {cmd!r} is not defined in config.json actions")
            action = {"type": "pc", "command": cmd}
        else:
            raise ValueError(f"shortcut {s['id']}: action type must be app, view or pc")
        out["shortcuts"].append({"id": s["id"], "label": _text(s.get("label", s["id"]), 24),
                                 "icon": _text(s.get("icon", ""), 16), "action": action})
    return out


def load_widgets(path=None) -> dict:
    return config.read_json(path or config.WIDGETS_FILE, {"panes": [], "shortcuts": []})
