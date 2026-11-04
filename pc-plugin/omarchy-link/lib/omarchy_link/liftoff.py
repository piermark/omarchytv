"""Liftoff: a build or an update on the big screen.

`omarchy-link liftoff -- make release` runs the command here (output still in your terminal) and the TV shows an
Omarchy-style rocket countdown: T-minus from the last run of the same command (T-plus the first time), the stage,
then LIFTOFF when it exits 0 or a red ABORT alarm with the last lines when it fails. The TV update via the PC
(omarchy-tv webos-serve) writes the same state with kind "update".

The state is one small file in the runtime dir; omarchy-link sends it to Android TVs ("liftoff" event) and the
webOS/Tizen bridge serves it in /v1/status. Nothing is sent unless a liftoff is running or just finished.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import threading
import time
from collections import deque

from . import config

STATE_FILE = config.RUNTIME_DIR / "liftoff.json"
HISTORY_FILE = config.STATE_DIR / "liftoff-history.json"
KINDS = ("build", "update")
STATES = ("running", "ok", "failed")
SHOW_DONE_S = 90          # a finished liftoff stays in the payload this long (the TV shows it, then hides)
TAIL_LINES = 6
NAME = re.compile(r"[^A-Za-z0-9 ._:/+-]")
ANSI = re.compile(r"\x1b\[[0-9;?]*[ -/]*[@-~]")


def clean(text: object, limit: int) -> str:
    """Printable, single line, no terminal escapes, at most `limit` characters."""
    s = ANSI.sub("", str(text or ""))
    s = "".join(ch if ch.isprintable() else " " for ch in s).strip()
    return s[:limit]


def default_name(argv: list[str]) -> str:
    parts = [os.path.basename(argv[0])] + [a for a in argv[1:3] if not a.startswith("-")]
    return NAME.sub("", " ".join(parts))[:40] or "build"


def _write(state: dict) -> None:
    config.ensure_private_dir(STATE_FILE.parent)
    tmp = STATE_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(state))
    os.chmod(tmp, 0o600)
    os.replace(tmp, STATE_FILE)


def read_state() -> dict | None:
    try:
        return json.loads(STATE_FILE.read_text())
    except (OSError, ValueError):
        return None


def public(state: dict | None, now: float | None = None) -> dict | None:
    """What the TV gets: validated, clipped, or None when there is nothing to show."""
    if not isinstance(state, dict):
        return None
    now = time.time() if now is None else now
    st = state.get("state")
    kind = state.get("kind")
    if st not in STATES or kind not in KINDS:
        return None
    started = float(state.get("startedAt") or now)
    ended = state.get("endedAt")
    if st == "running":
        pid = state.get("pid")
        if isinstance(pid, int) and pid > 0 and not _alive(pid):   # killed without a goodbye: show it failed
            st, ended = "failed", state.get("updatedAt") or now
    if st != "running" and now - float(ended or now) > SHOW_DONE_S:
        return None
    expected = state.get("expectedS")
    pct = state.get("pct")
    out = {
        "id": clean(state.get("id"), 32),
        "kind": kind,
        "name": clean(state.get("name"), 40),
        "state": st,
        "stage": clean(state.get("stage"), 40),
        "elapsedS": max(0, int((float(ended) if ended else now) - started)),
        "expectedS": int(expected) if isinstance(expected, (int, float)) and 0 < expected < 86400 else None,
        "pct": int(pct) if isinstance(pct, (int, float)) and 0 <= pct <= 100 else None,
        "code": state.get("code") if isinstance(state.get("code"), int) else None,
        "tail": [clean(t, 120) for t in (state.get("tail") or [])[-TAIL_LINES:]] if st == "failed" else [],
    }
    return out


def _alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def _history() -> dict:
    try:
        h = json.loads(HISTORY_FILE.read_text())
        return h if isinstance(h, dict) else {}
    except (OSError, ValueError):
        return {}


def expected_for(name: str) -> int | None:
    v = _history().get(name)
    return int(v) if isinstance(v, (int, float)) and v > 0 else None


def remember(name: str, seconds: float) -> None:
    seconds = max(1.0, seconds)
    h = _history()
    old = h.get(name)
    # Smooth a little, so one odd run doesn't throw the next countdown off.
    h[name] = round(seconds if not isinstance(old, (int, float)) else old * 0.3 + seconds * 0.7, 1)
    if len(h) > 50:
        h = dict(list(h.items())[-50:])
    config.ensure_private_dir(HISTORY_FILE.parent)
    HISTORY_FILE.write_text(json.dumps(h))


class Tracker:
    """One liftoff: call stage()/progress(), then done(ok, code). Writes the state file each time."""

    def __init__(self, kind: str, name: str, expected: int | None = None, pid: int | None = None):
        assert kind in KINDS
        self.state = {"id": f"{int(time.time() * 1000):x}", "kind": kind, "name": clean(name, 40), "state": "running",
                      "stage": "", "startedAt": time.time(), "expectedS": expected, "pct": None, "tail": [],
                      "pid": pid if pid is not None else os.getpid(), "updatedAt": time.time()}
        self.lock = threading.Lock()
        self.save()

    def save(self) -> None:
        with self.lock:
            self.state["updatedAt"] = time.time()
            try:
                _write(self.state)
            except OSError:
                pass

    def stage(self, text: str, pct: int | None = None) -> None:
        self.state["stage"] = clean(text, 40)
        if pct is not None:
            self.state["pct"] = max(0, min(100, int(pct)))
        self.save()

    def tail(self, lines: list[str]) -> None:
        self.state["tail"] = [clean(t, 120) for t in lines[-TAIL_LINES:]]

    def done(self, ok: bool, code: int | None = None, message: str | None = None) -> None:
        self.state.update(state="ok" if ok else "failed", endedAt=time.time(), code=code)
        if message:
            self.state["tail"] = (self.state.get("tail") or []) + [clean(message, 120)]
        if ok:
            self.state["pct"] = 100
        self.save()


def run(argv: list[str], name: str | None = None, out=sys.stdout) -> int:
    """Run argv (no shell), mirror its output, track it on the TV. Returns its exit code."""
    name = NAME.sub("", name or "")[:40] or default_name(argv)
    t = Tracker("build", name, expected_for(name))
    t.stage(name)
    tail: deque[str] = deque(maxlen=TAIL_LINES)
    start = time.monotonic()
    try:
        p = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
    except OSError as e:
        t.done(False, 127, str(e))
        print(f"liftoff: {e}", file=sys.stderr)
        return 127
    last = 0.0
    try:
        for raw in iter(p.stdout.readline, b""):
            line = raw.decode("utf-8", "replace")
            out.write(line)
            out.flush()
            if line.strip():
                tail.append(line.rstrip("\n"))
            if time.monotonic() - last > 1:
                last = time.monotonic()
                t.tail(list(tail))
                t.save()
        code = p.wait()
    except KeyboardInterrupt:
        p.terminate()
        code = p.wait()
    t.tail(list(tail))
    if code == 0:
        remember(name, time.monotonic() - start)
    t.done(code == 0, code)
    return code
