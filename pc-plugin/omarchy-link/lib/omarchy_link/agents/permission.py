"""v2.21 approve on the TV: Claude Code's permission prompts answered with the remote.

`omarchy-link agents approvals on` adds a PermissionRequest hook to ~/.claude/settings.json (a backup is kept). When
Claude Code would ask "Allow this?", the hook puts the question on the TV: Allow / Deny / On the PC. Allow and Deny
answer Claude Code (hookSpecificOutput.decision.behavior); "On the PC", no answer in time, a TV that is off or has agent
alerts off print nothing, so Claude Code shows its own prompt as usual. Nothing is ever allowed without an answer.
"""

from __future__ import annotations

import json
import re
import shlex
import shutil
import time
from pathlib import Path

from .. import config
from .ask import claude_settings

MARK = "agents permission"
CHOICES = ("Allow", "Deny", "On the PC")
RISKY_CHOICES = ("Deny", "On the PC")   # v2.22: a risky request is never approved from the couch

# v2.22: what makes a request risky. (pattern, reason); the first match wins. Shell commands:
RISKY_COMMANDS = (
    (r"(^|[;&|(\s])(rm|rmdir|shred|unlink)\s+\S|\bfind\b[^;&|]*\s-delete\b", "deletes files"),
    (r"\bgit\s+push\b[^;&|]*(\s--force\b|\s-f\b|\s--force-with-lease\b|\s\+\S)", "force push"),
    (r"\bgit\s+push\b", "pushes to a remote"),
    (r"\bgit\s+(reset\s+--hard|clean\s+-\S*f|branch\s+-D)\b", "throws work away"),
    (r"(^|[;&|(]\s*)sudo\b", "runs as root"),
    (r"\b(curl|wget)\b[^|;]*\|\s*(sudo\s+)?(ba|z|da)?sh\b", "runs a downloaded script"),
    (r"\bdd\b[^;&|]*\bof=|\bmkfs(\.\w+)?\b|>\s*/dev/(sd|nvme|mmcblk)", "writes a disk"),
    (r"(?i)\b(drop\s+(table|database|schema)|truncate\s+table)\b", "drops data"),
    (r"\bchmod\s+(-\S+\s+)*0?777\b|\bchown\s+-R\b", "changes permissions"),
    (r"\b(shutdown|reboot|poweroff|halt)\b", "stops the machine"),
    (r"\b(npm|pnpm|yarn|cargo|twine|gem)\s+publish\b|\bgh\s+release\s+create\b", "publishes"),
    (r"\bkubectl\s+delete\b|\bterraform\s+(destroy|apply)\b|\bdocker\s+(system\s+prune|volume\s+rm)\b", "changes infrastructure"),
)
# ...and files a change or a write must not touch casually:
RISKY_PATHS = (
    (r"(^|/)\.env(\.[\w-]+)?$|(^|/)\.ssh/|(^|/)id_(rsa|ecdsa|ed25519)|\.(pem|key|p12|jks)$|credentials|secrets?\.",
     "touches secrets"),
    (r"^/(etc|boot|usr|var/lib)/", "touches system files"),
    (r"(^|/)\.git/", "edits git internals"),
)


def risk(p: dict) -> str:
    """v2.22: why this request is risky ("" when it is routine)."""
    ti = p.get("tool_input") if isinstance(p.get("tool_input"), dict) else {}
    cmd = str(ti.get("command") or "")
    if cmd:
        for pat, why in RISKY_COMMANDS:
            if re.search(pat, cmd):
                return why
    path = str(ti.get("file_path") or ti.get("notebook_path") or "")
    if path:
        for pat, why in RISKY_PATHS:
            if re.search(pat, path):
                return why
    return ""
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
    why = risk(p)
    head = (f"Risky: {why}. " if why else "") + f"Claude Code{', project ' + where if where else ''}"
    pairs = _edits(p)
    if pairs:   # a diff read aloud is noise: say which file and how big the change is, the screen shows it
        _, rm, add = _diff(pairs)
        name = Path(str(ti.get("file_path") or ti.get("notebook_path") or "a file")).name
        return f"{head} wants to change {name}: {add} lines added, {rm} removed. The diff is on screen."[:300]
    return (f"{head} wants to use {tool}" + (f": {what}" if what else "."))[:300]


def _edits(p: dict) -> list:
    """(old, new) pairs of a file change: Edit, MultiEdit, Write (old is empty), NotebookEdit."""
    ti = p.get("tool_input") if isinstance(p.get("tool_input"), dict) else {}
    tool = str(p.get("tool_name") or "")
    if tool == "MultiEdit" and isinstance(ti.get("edits"), list):
        return [(str(e.get("old_string", "")), str(e.get("new_string", ""))) for e in ti["edits"] if isinstance(e, dict)]
    if "old_string" in ti or "new_string" in ti:
        return [(str(ti.get("old_string", "")), str(ti.get("new_string", "")))]
    if tool == "Write" and "content" in ti:
        return [("", str(ti.get("content", "")))]
    if "new_source" in ti:
        return [("", str(ti.get("new_source", "")))]
    return []


def _diff(pairs: list) -> tuple:
    """The changed lines as "- old" / "+ new" (with a little context), and how many were removed and added."""
    import difflib
    out, rm, add = [], 0, 0
    for old, new in pairs:
        if out:
            out.append("···")
        for line in difflib.unified_diff(old.splitlines(), new.splitlines(), lineterm="", n=1):
            if line.startswith(("---", "+++", "@@")):
                continue
            if line.startswith("-"):
                rm += 1
                out.append("- " + line[1:])
            elif line.startswith("+"):
                add += 1
                out.append("+ " + line[1:])
            else:
                out.append("  " + line[1:])
    return out, rm, add


def detail(p: dict) -> str:
    """v2.22 shown under the question on the TV, scrollable (the TV keeps 40 lines, 2000 characters): the whole
    command, or for a file change the path and the diff."""
    ti = p.get("tool_input") if isinstance(p.get("tool_input"), dict) else {}
    pairs = _edits(p)
    if pairs:
        lines, _, _ = _diff(pairs)
        path = str(ti.get("file_path") or ti.get("notebook_path") or "")
        return "\n".join(([path] if path else []) + lines)[:2000]
    what = ti.get("command") or ti.get("file_path") or ti.get("url") or ti.get("pattern") or ti.get("description") or ""
    return str(what)[:2000]


def decide(payload: dict, ask) -> dict | None:
    """ask(question, choices, timeout) -> answer or None. Returns the hook's stdout object, or None (no decision)."""
    if not isinstance(payload, dict) or payload.get("hook_event_name", "PermissionRequest") != "PermissionRequest":
        return None
    why = risk(payload)
    try:
        if why:   # v2.22: no Allow on the remote; the TV frames it red
            answer = ask(describe(payload), list(RISKY_CHOICES), WAIT, spoken(payload), detail(payload), why)
        else:
            answer = ask(describe(payload), list(CHOICES), WAIT, spoken(payload), detail(payload))
    except Exception:
        return None
    if why and answer == "Allow":   # whatever the TV sent back: risky requests are approved at the PC only
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
    def ask(q, choices, timeout, say="", detail="", risk=""):
        body = {"question": q, "choices": choices, "timeout": timeout, "from": source}
        if risk:
            body["risk"] = risk   # v2.22: the TV frames it red and never offers a yes
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
