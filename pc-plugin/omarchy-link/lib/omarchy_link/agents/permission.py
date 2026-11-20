"""v2.21 approve on the TV: Claude Code's permission prompts answered with the remote.

`omarchy-link agents approvals on` adds a PermissionRequest hook to ~/.claude/settings.json (a backup is kept). When
Claude Code would ask "Allow this?", the hook puts the question on the TV: Allow / Deny / On the PC. Allow and Deny
answer Claude Code (hookSpecificOutput.decision.behavior); "On the PC", no answer in time, a TV that is off or has agent
alerts off print nothing, so Claude Code shows its own prompt as usual. Nothing is ever allowed without an answer.
"""

from __future__ import annotations

import json
import shlex
import shutil
import time
from pathlib import Path

from .. import config
from .ask import claude_settings

MARK = "agents permission"
CHOICES = ("Allow", "Deny", "On the PC")
WAIT = 90   # seconds on the TV; the hook's own timeout is above it


def describe(p: dict) -> str:
    tool = str(p.get("tool_name") or "a tool")
    ti = p.get("tool_input") if isinstance(p.get("tool_input"), dict) else {}
    what = ti.get("command") or ti.get("file_path") or ti.get("url") or ti.get("pattern") or ti.get("description") or ""
    what = " ".join(str(what).split())
    if len(what) > 110:
        what = what[:107] + "…"
    where = Path(str(p.get("cwd") or "")).name
    return (f"Claude Code{' · ' + where if where else ''} wants {tool}" + (f": {what}" if what else "?"))[:200]


def spoken(p: dict) -> str:
    """v2.22 what the TV reads aloud: who, where, the tool and the whole command or path (the TV stops at 300)."""
    tool = str(p.get("tool_name") or "a tool")
    ti = p.get("tool_input") if isinstance(p.get("tool_input"), dict) else {}
    what = ti.get("command") or ti.get("file_path") or ti.get("url") or ti.get("pattern") or ti.get("description") or ""
    what = " ".join(str(what).split())
    where = Path(str(p.get("cwd") or "")).name
    return (f"Claude Code{', project ' + where if where else ''} wants to use {tool}" + (f": {what}" if what else "."))[:300]


def detail(p: dict) -> str:
    """v2.22 the whole command (or path, URL…) shown under the question on the TV, scrollable; the TV keeps 2000."""
    ti = p.get("tool_input") if isinstance(p.get("tool_input"), dict) else {}
    what = ti.get("command") or ti.get("file_path") or ti.get("url") or ti.get("pattern") or ti.get("description") or ""
    return str(what)[:2000]


def decide(payload: dict, ask) -> dict | None:
    """ask(question, choices, timeout) -> answer or None. Returns the hook's stdout object, or None (no decision)."""
    if not isinstance(payload, dict) or payload.get("hook_event_name", "PermissionRequest") != "PermissionRequest":
        return None
    try:
        answer = ask(describe(payload), list(CHOICES), WAIT, spoken(payload), detail(payload))
    except Exception:
        return None
    if answer == "Allow":
        decision = {"behavior": "allow"}
    elif answer == "Deny":
        decision = {"behavior": "deny", "message": "Denied on the TV"}
    else:
        return None
    return {"hookSpecificOutput": {"hookEventName": "PermissionRequest", "decision": decision}}


def install(exe: str, speak: bool = False) -> Path:
    f = claude_settings()
    data = config.read_json(f, {}) if f.exists() else {}
    if not isinstance(data, dict):
        raise ValueError(f"{f} is not a JSON object; not touching it")
    bak = f.with_suffix(".json.omarchy-link.bak")
    if f.exists() and MARK not in f.read_text() and not bak.exists():   # the user's own file, once
        shutil.copy2(f, bak)
    hooks = data.setdefault("hooks", {})
    lst = [h for h in hooks.get("PermissionRequest", []) if MARK not in json.dumps(h)]
    hooks["PermissionRequest"] = lst + [{"matcher": "*", "hooks": [
        {"type": "command", "command": f"{shlex.quote(exe)} {MARK}" + (" --speak" if speak else ""), "timeout": WAIT + 30}]}]
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_text(json.dumps(data, indent=2) + "\n")
    return f


def remove() -> bool:
    f = claude_settings()
    data = config.read_json(f, None)
    if not isinstance(data, dict) or not isinstance(data.get("hooks"), dict):
        return False
    lst = data["hooks"].get("PermissionRequest")
    if not isinstance(lst, list):
        return False
    keep = [h for h in lst if MARK not in json.dumps(h)]
    if keep == lst:
        return False
    if keep:
        data["hooks"]["PermissionRequest"] = keep
    else:
        del data["hooks"]["PermissionRequest"]
    if not data["hooks"]:
        del data["hooks"]
    f.write_text(json.dumps(data, indent=2) + "\n")
    return True


def tv_ask(client, source: str = "Claude Code", speak: bool = False):
    """An ask() over the paired link: POST /v1/ask, then wait for the answer. speak (v2.22): read aloud first."""
    def ask(q, choices, timeout, say="", detail=""):
        body = {"question": q, "choices": choices, "timeout": timeout, "from": source}
        if detail:
            body["detail"] = detail   # v2.22: the whole command under the question
        if speak:
            body.update(speak=True, say=say or q)
        r = client.request("POST", "/v1/ask", body, timeout=8)
        end = time.time() + timeout + 5
        while time.time() < end:
            s = client.request("GET", f"/v1/ask/{r['id']}?wait=25", None, timeout=35)
            if s.get("state") == "answered":
                return s.get("answer")
            if s.get("state") in ("expired", "cancelled"):
                return None
        return None
    return ask
