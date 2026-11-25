"""v2.22 `omarchy-link forget-tv`: everything this PC set up for a TV, undone in one go.

A TV that leaves the house (sold, reset, replaced) should not leave live credentials behind. This revokes the TV's app
tokens and unpairs it while the TV can still be reached, then cleans this PC: the omarchytv MCP server in Claude Code,
Codex and Hermes, the omarchy-link hooks in Claude Code's settings (approvals, notifications), the Omarchy hook pack,
the Tailscale switch and the PC's own MCP bearer token. Every config file it edits is backed up next to itself first
(<file>.omarchy-link.bak). What it cannot do from here (removing the TV from the tailnet) it prints at the end.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
from pathlib import Path

MARK = "omarchy-link"
# the commands omarchy-link writes into Claude Code (the executable may be linked under another name)
HOOK_MARKS = (MARK, "agents permission", "agents hook")


def _home() -> Path:
    return Path(os.environ.get("HOME") or Path.home())


def claude_settings() -> Path:
    return Path(os.environ.get("CLAUDE_CONFIG_DIR") or _home() / ".claude") / "settings.json"


def codex_config() -> Path:
    return Path(os.environ.get("CODEX_HOME") or _home() / ".codex") / "config.toml"


def hermes_config() -> Path:
    return Path(os.environ.get("HERMES_HOME") or _home() / ".hermes") / "config.yaml"


def mcp_token_file() -> Path:
    return Path(os.environ.get("XDG_CONFIG_HOME") or _home() / ".config") / "omarchy-tv" / "mcp-token"


def _backup(f: Path) -> None:
    b = f.with_name(f.name + ".omarchy-link.bak")
    shutil.copy2(f, b)


def strip_claude_hooks(text: str) -> tuple[str, int]:
    """Claude Code settings.json without any hook whose command runs omarchy-link. Returns (json, removed)."""
    data = json.loads(text)
    hooks = data.get("hooks") if isinstance(data, dict) else None
    if not isinstance(hooks, dict):
        return text, 0
    removed = 0
    for event in list(hooks):
        groups = hooks[event] if isinstance(hooks[event], list) else []
        keep_groups = []
        for g in groups:
            inner = g.get("hooks") if isinstance(g, dict) else None
            if not isinstance(inner, list):
                keep_groups.append(g)
                continue
            keep = [h for h in inner if not any(m in str((h or {}).get("command", "")) for m in HOOK_MARKS)]
            removed += len(inner) - len(keep)
            if keep:
                keep_groups.append(dict(g, hooks=keep))
        if keep_groups:
            hooks[event] = keep_groups
        else:
            del hooks[event]
    if not hooks:
        del data["hooks"]
    return (json.dumps(data, indent=2) + "\n", removed) if removed else (text, 0)


def strip_codex(text: str) -> tuple[str, int]:
    """config.toml without [mcp_servers.omarchytv] (and its sub-tables) and a notify line that runs omarchy-link."""
    out, removed, skipping = [], 0, False
    for line in text.splitlines(keepends=True):
        s = line.strip()
        if s.startswith("["):
            skipping = bool(re.match(r"^\[\s*mcp_servers\.(\"omarchytv\"|omarchytv)(\.|\s*\])", s))
            if skipping:
                removed += 1
                continue
        if skipping:
            continue
        if re.match(r"^notify\s*=", s) and MARK in s:
            removed += 1
            continue
        out.append(line)
    return "".join(out), removed


def strip_hermes(text: str) -> tuple[str, int]:
    """config.yaml without the omarchytv entry under mcp_servers (the entry and its more indented lines)."""
    lines = text.splitlines(keepends=True)
    out, removed, i = [], 0, 0
    in_servers, servers_indent = False, -1
    while i < len(lines):
        line = lines[i]
        indent = len(line) - len(line.lstrip(" "))
        s = line.strip()
        if s and not s.startswith("#"):
            if re.match(r"^mcp_servers\s*:", s):
                in_servers, servers_indent = True, indent
            elif in_servers and indent <= servers_indent:
                in_servers = False
        if in_servers and indent > servers_indent and re.match(r"^[\"']?omarchytv[\"']?\s*:", s):
            removed += 1
            i += 1
            while i < len(lines):
                nxt = lines[i]
                if nxt.strip() and len(nxt) - len(nxt.lstrip(" ")) <= indent:
                    break
                i += 1
            continue
        out.append(line)
        i += 1
    return "".join(out), removed


def _edit(f: Path, fn) -> int:
    if not f.is_file():
        return 0
    text = f.read_text()
    try:
        new, n = fn(text)
    except ValueError:   # not valid JSON: leave the user's file alone
        return 0
    if n:
        _backup(f)
        f.write_text(new)
    return n


def plan() -> list[str]:
    """What forget-tv would touch on this PC, for the confirmation."""
    p = []
    for f, what in ((claude_settings(), "omarchy-link hooks in Claude Code"), (codex_config(), "the omarchytv MCP server and notify line in Codex"),
                    (hermes_config(), "the omarchytv MCP server in Hermes")):
        if f.is_file() and ("omarchytv" in f.read_text() or MARK in f.read_text()):
            p.append(f"{what} ({f}, backup kept)")
    if shutil.which("claude"):
        p.append("claude mcp remove omarchytv")
    if mcp_token_file().is_file():
        p.append(f"the PC MCP server's bearer token ({mcp_token_file()}), so old copies stop working")
    return p


def clean_pc() -> list[str]:
    """Undo this PC's side. Returns what was done, one line each."""
    done = []
    n = _edit(claude_settings(), strip_claude_hooks)
    if n:
        done.append(f"Claude Code: {n} omarchy-link hook(s) removed")
    n = _edit(codex_config(), strip_codex)
    if n:
        done.append("Codex: omarchytv MCP server / notify removed")
    n = _edit(hermes_config(), strip_hermes)
    if n:
        done.append("Hermes: omarchytv MCP server removed")
    claude = shutil.which("claude")
    if claude:
        for scope in ("local", "user", "project"):
            try:
                r = subprocess.run([claude, "mcp", "remove", "omarchytv", "-s", scope], capture_output=True, text=True, timeout=20)
            except (OSError, subprocess.SubprocessError):
                break
            if r.returncode == 0:
                done.append(f"Claude Code: omarchytv MCP server removed ({scope})")
    t = mcp_token_file()
    if t.is_file():
        t.unlink()
        done.append("PC MCP server: bearer token deleted (a new one is made on its next start)")
    return done
