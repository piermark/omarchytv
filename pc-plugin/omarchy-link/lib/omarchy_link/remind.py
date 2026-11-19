"""v2.21 spoken reminders on the TV: `omarchy-link remind 10m "Pizza in the oven"`.

A systemd user timer (systemd-run --user) runs `omarchy-link notify --level warn --speak` at the time, so nothing of
omarchy-link stays running meanwhile and the TV does no work until then. Times: 90s, 10m, 1h30m, 45 (minutes) or
HH:MM (today, or tomorrow if it has passed). `remind list` and `remind cancel ID`.
"""

from __future__ import annotations

import datetime as dt
import re
import secrets
import shutil
import subprocess

PREFIX = "omarchy-link-remind-"
DUR_RE = re.compile(r"^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$")


class RemindError(Exception):
    pass


def seconds_until(when: str, now: dt.datetime | None = None) -> int:
    w = when.strip().lower()
    if re.fullmatch(r"\d{1,4}", w):
        n = int(w) * 60
    elif re.fullmatch(r"\d{1,2}:\d{2}", w):
        now = now or dt.datetime.now()
        h, m = (int(x) for x in w.split(":"))
        if h > 23 or m > 59:
            raise RemindError(f"no such time: {when}")
        t = now.replace(hour=h, minute=m, second=0, microsecond=0)
        if t <= now:
            t += dt.timedelta(days=1)
        n = int((t - now).total_seconds())
    else:
        mm = DUR_RE.match(w)
        if not w or not mm or not any(mm.groups()):
            raise RemindError(f"when? 90s, 10m, 1h30m, 45 or HH:MM (not {when!r})")
        h, m, s = (int(x or 0) for x in mm.groups())
        n = h * 3600 + m * 60 + s
    if n < 5 or n > 7 * 86400:
        raise RemindError("between 5 seconds and 7 days")
    return n


def clean(text: str) -> str:
    t = " ".join("".join(ch for ch in str(text) if ch.isprintable()).split())[:160]
    if not t:
        raise RemindError("nothing to remind")
    return t


def command(exe: str, secs: int, text: str, rid: str) -> list[str]:
    return ["systemd-run", "--user", "--quiet", f"--unit={PREFIX}{rid}", f"--on-active={secs}",
            f"--description=OmarchyTV reminder: {text[:60]}", "--timer-property=AccuracySec=1s",
            exe, "notify", "--quiet", "--level", "warn", "--speak", "--from", "Reminder", "--title", "Reminder",
            "--", text]   # the text may start with "-"


def schedule(exe: str, when: str, text: str, run=subprocess.run, which=shutil.which) -> dict:
    secs, text = seconds_until(when), clean(text)
    if not which("systemd-run"):
        raise RemindError("needs systemd (systemd-run --user)")
    rid = secrets.token_hex(3)
    r = run(command(exe, secs, text, rid), capture_output=True, text=True, timeout=15)
    if r.returncode != 0:
        raise RemindError((r.stderr or "systemd-run failed").strip()[:160])
    at = dt.datetime.now() + dt.timedelta(seconds=secs)
    return {"id": rid, "at": at.strftime("%H:%M:%S"), "in": secs, "text": text}


def listing(run=subprocess.run) -> list[dict]:
    r = run(["systemctl", "--user", "list-timers", "--all", "--no-legend", "--output=json", PREFIX + "*"],
            capture_output=True, text=True, timeout=10)
    import json
    try:
        rows = json.loads(r.stdout or "[]")
    except ValueError:
        return []
    out = []
    for row in rows if isinstance(rows, list) else []:
        unit = str(row.get("unit", ""))
        if unit.startswith(PREFIX):
            out.append({"id": unit[len(PREFIX):].removesuffix(".timer"), "next": row.get("next"), "left": row.get("left")})
    return out


def cancel(rid: str, run=subprocess.run) -> bool:
    if not re.fullmatch(r"[0-9a-f]{6}", rid):
        raise RemindError("an id from `omarchy-link remind list`")
    r = run(["systemctl", "--user", "stop", f"{PREFIX}{rid}.timer"], capture_output=True, text=True, timeout=10)
    return r.returncode == 0
