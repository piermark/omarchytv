"""v2.21 rooms on the TV Home: a pane per room with its last messages (Hermes cron reports, agents, scripts).

`omarchy-link room post hermes "Backup done, 412 GB"`; MCP `tv_room_post` (omarchy-link mcp). Kept in
~/.local/state/omarchy-link/rooms.json, at most 3 rooms of 5 lines, shown with the other Home panes (PUT /v1/widgets,
no new TV code). `room clear NAME` removes one.
"""

from __future__ import annotations

import json
import re
import time

from . import config

ROOM_RE = re.compile(r"^[a-z0-9][a-z0-9-]{0,23}$")
MAX_ROOMS, MAX_LINES = 3, 5


def _file():
    return config.STATE_DIR / "rooms.json"


def load() -> dict:
    d = config.read_json(_file(), {})
    return d if isinstance(d, dict) else {}


def _save(d: dict) -> None:
    config.STATE_DIR.mkdir(parents=True, exist_ok=True, mode=0o700)
    _file().write_text(json.dumps(d, ensure_ascii=False))


def name_of(room: str) -> str:
    r = str(room or "").strip().lower().replace(" ", "-")
    if not ROOM_RE.match(r):
        raise ValueError("room: a-z, 0-9 and -, up to 24 characters")
    return r


def post(room: str, text: str, sender: str = "", now: float | None = None) -> dict:
    r = name_of(room)
    t = " ".join("".join(ch for ch in str(text) if ch.isprintable()).split())[:64]
    if not t:
        raise ValueError("nothing to post")
    now = time.time() if now is None else now
    d = load()
    msgs = (d.get(r) or {}).get("msgs", [])
    msgs = (msgs + [{"at": now, "from": str(sender or "")[:16], "text": t}])[-MAX_LINES:]
    d[r] = {"msgs": msgs, "at": now}
    if len(d) > MAX_ROOMS:   # the least recent room makes way
        for k in sorted(d, key=lambda k: d[k].get("at", 0))[: len(d) - MAX_ROOMS]:
            del d[k]
    _save(d)
    return d[r]


def clear(room: str) -> bool:
    r, d = name_of(room), load()
    if r not in d:
        return False
    del d[r]
    _save(d)
    return True


def panes(now: float | None = None) -> list[dict]:
    """Home panes, newest room first; a room quiet for a day leaves the Home."""
    now = time.time() if now is None else now
    out = []
    for r, v in sorted(load().items(), key=lambda kv: -kv[1].get("at", 0)):
        if now - v.get("at", 0) > 86400:
            continue
        lines = [{"label": time.strftime("%H:%M", time.localtime(m["at"])) + (" " + m["from"] if m.get("from") else ""),
                  "value": m["text"]} for m in reversed(v.get("msgs", []))]
        out.append({"id": f"room-{r}", "title": f"{r} · room", "icon": "", "lines": lines, "ttl": 86400})
    return out
