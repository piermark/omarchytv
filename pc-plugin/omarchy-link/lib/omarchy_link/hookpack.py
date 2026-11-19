"""v2.21 Omarchy hook pack: the TV hears about the PC's own events.

Omarchy runs ~/.config/omarchy/hooks/<event>.d/* (omarchy-hook). `omarchy-link hook add` writes one small script per
event, all quiet and backgrounded, so a slow or absent TV never delays or fails the desktop:

  theme-set     the new theme goes to the TV (as before)
  post-boot     "PC is up" on the TV, and the theme follows
  post-update   "Omarchy updated" (after `omarchy update`)
  battery-low   a spoken warning with the level (laptops)
  font-set      a short note with the new font
"""

from __future__ import annotations

import os
from pathlib import Path

NAME = "omarchy-link"
EVENTS = ("theme-set", "post-boot", "post-update", "battery-low", "font-set")
DEFAULT = ("theme-set",)   # `hook add` alone keeps the 2.20 behaviour

_HEAD = """#!/bin/bash
# Omarchy {event} hook (written by `omarchy-link hook add`). Never fails or delays the desktop.
exe="{exe}"
[[ -x $exe ]] || exit 0
detach=(); command -v setsid >/dev/null && detach=(setsid)
"""
_BODY = {
    "theme-set": '"${detach[@]}" "$exe" sync-theme --quiet </dev/null >/dev/null 2>&1 &\n',
    "post-boot": ('"${detach[@]}" sh -c \'"$0" notify --quiet --from Omarchy --title "PC" "$(hostname) is up"; '
                  '"$0" sync-theme --quiet\' "$exe" </dev/null >/dev/null 2>&1 &\n'),
    "post-update": '"${detach[@]}" "$exe" notify --quiet --from Omarchy --title "Update" "Omarchy updated" </dev/null >/dev/null 2>&1 &\n',
    "battery-low": ('level="${1//[^0-9]/}"\n'
                    '"${detach[@]}" "$exe" notify --quiet --from Omarchy --level warn --speak --title "Battery" '
                    '"Battery at ${level:-low}%, plug in the charger" </dev/null >/dev/null 2>&1 &\n'),
    "font-set": ('font="${1//[^A-Za-z0-9 ._-]/}"\n'
                 '"${detach[@]}" "$exe" notify --quiet --from Omarchy --title "Font" "${font:0:40}" </dev/null >/dev/null 2>&1 &\n'),
}


def hooks_dir() -> Path:
    return Path.home() / ".config/omarchy/hooks"


def script(event: str, exe: str) -> str:
    return _HEAD.format(event=event, exe=exe) + _BODY[event] + "exit 0\n"


def parse(names: list[str]) -> tuple[str, ...]:
    if not names:
        return DEFAULT
    if names == ["all"] or names == ["--all"]:
        return EVENTS
    bad = [n for n in names if n not in EVENTS]
    if bad:
        raise ValueError(f"unknown hook {bad[0]!r}: {', '.join(EVENTS)} or all")
    return tuple(dict.fromkeys(names))


def add(events, exe: str) -> list[Path]:
    out = []
    for ev in events:
        d = hooks_dir() / f"{ev}.d"
        d.mkdir(parents=True, exist_ok=True)
        f = d / NAME
        f.write_text(script(ev, exe))
        os.chmod(f, 0o755)
        out.append(f)
    return out


def remove(events=EVENTS) -> list[Path]:
    gone = []
    for ev in events:
        f = hooks_dir() / f"{ev}.d" / NAME
        if f.exists():
            f.unlink()
            gone.append(f)
    return gone


def installed() -> list[str]:
    return [ev for ev in EVENTS if (hooks_dir() / f"{ev}.d" / NAME).exists()]
