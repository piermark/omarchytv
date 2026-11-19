"""Live "what is my AI agent doing" feed for the TV, read from each agent's own local files.

Read-only, no APIs, off by default ("agentMonitor": {"enabled": true} in config.json). Every
string that leaves this module went through redact.short(): tool name plus a short target
(command first line, file basename, host, pattern), never file contents or tool output, never
env values or secrets, project = basename of the cwd only.

Supported (see docs/AGENTS.md): T3 Code, Claude Code, Codex CLI, Grok CLI, Hermes Agent, Gemini CLI
(best effort), Grok Bot (presence only).
"""
from __future__ import annotations

import datetime as dt
import glob
import json
import os
import sqlite3
import time
import urllib.parse
from pathlib import Path

from .. import config
from .redact import base, short, tool_step

STATUSES = ("thinking", "running", "waiting", "idle", "done", "error")
NAMES = {"claude-code": "Claude", "codex": "Codex", "grok": "Grok", "hermes": "Hermes", "gemini": "Gemini",
         "grok-bot": "Grok Bot", "t3-code": "T3 Code"}
DEFAULTS = {"enabled": False, "agents": None, "projects": None, "hideProjects": [], "steps": 8,
            "activeMinutes": 30, "maxAgents": 8}
TAIL = 384 * 1024
HOOK_EVENTS = "agent-events.jsonl"


def settings(cfg: dict) -> dict:
    m = cfg.get("agentMonitor") if isinstance(cfg.get("agentMonitor"), dict) else {}
    return {**DEFAULTS, **m}


def home(*parts) -> Path:
    return Path.home().joinpath(*parts)


def iso(t) -> str | None:
    """unix s / ms or ISO -> ISO Z."""
    if t is None or t == "":
        return None
    try:
        if isinstance(t, (int, float)):
            v = float(t) / (1000 if t > 1e11 else 1)
            return dt.datetime.fromtimestamp(v, dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        d = dt.datetime.fromisoformat(str(t).replace("Z", "+00:00"))
        if d.tzinfo is None:
            d = d.replace(tzinfo=dt.timezone.utc)
        return d.astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    except (ValueError, OverflowError, OSError):
        return None


def epoch(t) -> float | None:
    s = iso(t)
    return dt.datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp() if s else None


def tail_json(path: Path, limit: int = TAIL) -> list:
    """Last `limit` bytes of a JSONL file as objects (first partial line dropped)."""
    try:
        with open(path, "rb") as f:
            f.seek(0, 2)
            size = f.tell()
            f.seek(max(0, size - limit))
            data = f.read()
    except OSError:
        return []
    lines = data.split(b"\n")
    if size > limit:
        lines = lines[1:]
    out = []
    for ln in lines:
        ln = ln.strip()
        if not ln:
            continue
        try:
            o = json.loads(ln)
        except ValueError:
            continue
        if isinstance(o, dict):
            out.append(o)
    return out


def recent_files(pattern: str, minutes: float) -> list[Path]:
    cut = time.time() - minutes * 60
    files = []
    for f in glob.glob(pattern, recursive=True):
        try:
            m = os.stat(f).st_mtime
        except OSError:
            continue
        if m >= cut:
            files.append((m, Path(f)))
    return [f for _, f in sorted(files, reverse=True)]


class Feed:
    """Builds one agent session's activity from its events, oldest first."""

    def __init__(self, agent: str, session: str, cwd: str | None, n_steps: int):
        self.a = {"id": f"{agent}:{session[:12]}", "agent": agent, "agentName": NAMES.get(agent, agent),
                  "session": session, "project": base(cwd) if cwd else None, "status": "idle", "step": None,
                  "since": None, "startedAt": None, "updatedAt": None, "needsInput": False, "question": None,
                  "canAsk": False, "steps": [], "cwd": cwd}
        self.cwd, self.n = cwd, n_steps
        self.pending: dict = {}

    def at(self, t):
        s = iso(t)
        if s:
            self.a["startedAt"] = self.a["startedAt"] or s
            self.a["updatedAt"] = s
        return s

    def set(self, status: str, step=None, t=None, question=None):
        s = self.at(t)
        if step is not None and (status != self.a["status"] or step != self.a["step"]):
            self.a["since"] = s
        elif status != self.a["status"]:
            self.a["since"] = s
        self.a["status"] = status
        if step is not None:
            self.a["step"] = step
        self.a["needsInput"] = status == "waiting"
        self.a["question"] = short(question, 160) if (status == "waiting" and question) else None

    def add_step(self, text: str, t=None, status: str = "running"):
        self.a["steps"].append({"at": iso(t), "text": text, "status": status})
        self.a["steps"] = self.a["steps"][-self.n:]

    def finish_step(self, ok: bool):
        if self.a["steps"]:
            self.a["steps"][-1]["status"] = "ok" if ok else "error"

    def tool_start(self, key, name, raw, t, title=None):
        text = tool_step(name, raw, title)
        self.pending[key] = text
        self.add_step(text, t)
        self.set("running", text, t)

    def tool_end(self, key, ok: bool, t):
        text = self.pending.pop(key, None)
        for s in reversed(self.a["steps"]):
            if s["text"] == text and s["status"] == "running":
                s["status"] = "ok" if ok else "error"
                break
        self.set("running" if self.pending else "thinking", next(iter(self.pending.values()), self.a["step"]), t)

    def done(self) -> dict:
        a = self.a
        for s in a["steps"]:
            if s["status"] == "running" and a["status"] not in ("running", "waiting"):
                s["status"] = "ok"
        since = epoch(a["since"]) or epoch(a["updatedAt"])
        a["elapsed"] = int(max(0, time.time() - since)) if since else None
        return a


def stale(a: dict, file_mtime: float | None, now: float | None = None):
    """A running/thinking session that has not written for a while is waiting or gone idle."""
    now = now or time.time()
    age = now - (file_mtime or epoch(a.get("updatedAt")) or now)
    if a["status"] in ("thinking", "running") and age > 600:
        a["status"], a["needsInput"] = "idle", False
    elif a["status"] == "running" and age > 45 and not a["needsInput"]:
        # a tool call that has not returned for 45 s with nothing written is almost always a
        # permission prompt in the terminal
        a["status"], a["needsInput"] = "waiting", True
        a["question"] = a["question"] or short(f"Allow {a['step']}?", 160)
    elif a["status"] == "done" and age > 300:
        a["status"] = "idle"


# ------------------------------------------------------------------ Claude Code
def read_claude(st: dict, hooks: dict) -> list[dict]:
    root = Path(os.environ.get("CLAUDE_CONFIG_DIR") or home(".claude"))
    out = []
    for f in recent_files(str(root / "projects" / "*" / "*.jsonl"), st["activeMinutes"])[:st["maxAgents"]]:
        recs = tail_json(f)
        sid = f.stem
        cwd = next((r.get("cwd") for r in reversed(recs) if r.get("cwd")), None)
        feed = Feed("claude-code", sid, cwd, st["steps"])
        for r in recs:
            if r.get("isSidechain"):
                continue
            t, m = r.get("timestamp"), r.get("message")
            if r.get("type") == "user" and isinstance(m, dict):
                c = m.get("content")
                if isinstance(c, str) or (isinstance(c, list) and any(isinstance(x, dict) and x.get("type") == "text" for x in c)):
                    if not (isinstance(c, str) and c.startswith("<")):  # command/system wrappers
                        feed.add_step("prompt", t, "ok")
                        feed.set("thinking", "thinking", t)
                for x in c if isinstance(c, list) else []:
                    if isinstance(x, dict) and x.get("type") == "tool_result":
                        feed.tool_end(x.get("tool_use_id"), not x.get("is_error"), t)
            elif r.get("type") == "assistant" and isinstance(m, dict):
                for x in m.get("content") or []:
                    if not isinstance(x, dict):
                        continue
                    if x.get("type") == "tool_use":
                        feed.tool_start(x.get("id"), x.get("name"), x.get("input"), t)
                    elif x.get("type") == "thinking" and not feed.pending:
                        feed.set("thinking", "thinking", t)
                if m.get("stop_reason") in ("end_turn", "stop_sequence") and not feed.pending:
                    feed.set("done", "finished", t)
        a = feed.done()
        a["canAsk"] = True
        h = hooks.get(sid)
        if h and (epoch(h.get("at")) or 0) >= (epoch(a["updatedAt"]) or 0) - 1:
            apply_hook(a, h)
        stale(a, f.stat().st_mtime if f.exists() else None)
        out.append(a)
    return out


def hook_events() -> dict:
    """Latest Claude Code hook event per session (written by `omarchy-link agents hook`)."""
    latest = {}
    for e in tail_json(config.STATE_DIR / HOOK_EVENTS, 128 * 1024):
        if e.get("session"):
            latest[e["session"]] = e
    return latest


def apply_hook(a: dict, h: dict):
    ev = h.get("event")
    if ev == "Notification":
        a["status"], a["needsInput"], a["question"] = "waiting", True, h.get("text") or "Claude needs your input"
    elif ev == "PreToolUse" and h.get("step"):
        a["status"], a["step"], a["needsInput"], a["question"] = "running", h["step"], False, None
    elif ev in ("PostToolUse", "UserPromptSubmit"):
        a["status"], a["needsInput"], a["question"] = "thinking", False, None
    elif ev in ("Stop", "SessionEnd"):
        a["status"], a["needsInput"], a["question"] = "done", False, None
    a["updatedAt"] = max(a.get("updatedAt") or "", h.get("at") or "") or a.get("updatedAt")


def hook_record(payload: dict) -> dict | None:
    """Claude Code hook stdin JSON -> one sanitized event line."""
    ev = payload.get("hook_event_name")
    sid = payload.get("session_id")
    if not ev or not sid:
        return None
    rec = {"at": iso(time.time()), "session": str(sid)[:64], "event": str(ev)[:32],
           "project": base(payload.get("cwd")) if payload.get("cwd") else None}
    if ev in ("PreToolUse", "PostToolUse"):
        rec["step"] = tool_step(payload.get("tool_name"), payload.get("tool_input"))
    if ev == "Notification":
        rec["text"] = short(payload.get("message") or "", 160)
    return rec


# ------------------------------------------------------------------ Codex CLI
def read_codex(st: dict) -> list[dict]:
    root = Path(os.environ.get("CODEX_HOME") or home(".codex"))
    out = []
    for f in recent_files(str(root / "sessions" / "**" / "rollout-*.jsonl"), st["activeMinutes"])[:st["maxAgents"]]:
        recs = tail_json(f)
        meta = next((r.get("payload") for r in recs if r.get("type") == "session_meta"), None) or {}
        if not meta:  # long session: the meta line is at the top of the file
            try:
                with open(f, "rb") as fh:
                    first = json.loads(fh.readline() or b"{}")
                meta = first.get("payload") or {} if first.get("type") == "session_meta" else {}
            except (OSError, ValueError):
                meta = {}
        sid = str(meta.get("id") or "-".join(f.stem.split("-")[-5:]))
        cwd = meta.get("cwd") or next(((r.get("payload") or {}).get("cwd") for r in reversed(recs)
                                       if r.get("type") == "turn_context"), None)
        feed = Feed("codex", sid, cwd, st["steps"])
        for r in recs:
            t, p = r.get("timestamp"), r.get("payload") or {}
            pt = p.get("type")
            if r.get("type") == "response_item":
                if pt in ("function_call", "custom_tool_call"):
                    feed.tool_start(p.get("call_id"), p.get("name"), p.get("arguments") or p.get("input"), t)
                elif pt == "local_shell_call":
                    feed.tool_start(p.get("call_id"), "shell", {"command": (p.get("action") or {}).get("command")}, t)
                elif pt in ("function_call_output", "custom_tool_call_output"):
                    o = p.get("output")
                    ok = not (isinstance(o, dict) and o.get("success") is False)
                    if isinstance(o, str):
                        try:
                            meta_o = json.loads(o).get("metadata") or {}
                            ok = meta_o.get("exit_code", 0) == 0
                        except (ValueError, AttributeError):
                            pass
                    feed.tool_end(p.get("call_id"), ok, t)
                elif pt == "reasoning" and not feed.pending:
                    feed.set("thinking", "thinking", t)
            elif r.get("type") == "event_msg":
                if pt in ("user_message",):
                    feed.add_step("prompt", t, "ok")
                    feed.set("thinking", "thinking", t)
                elif pt in ("task_started",):
                    feed.set("thinking", "thinking", t)
                elif pt in ("task_complete",):
                    feed.pending.clear()
                    feed.set("done", "finished", t)
                elif pt in ("turn_aborted",):
                    feed.pending.clear()
                    feed.set("idle", "interrupted", t)
                elif pt in ("exec_approval_request", "apply_patch_approval_request", "request_user_input"):
                    cmd = p.get("command")
                    q = ("Run " + tool_step("shell", {"command": cmd}).split(": ", 1)[-1] + "?") if cmd else \
                        ("Apply the patch?" if "patch" in pt else (p.get("question") or "Codex needs your input"))
                    feed.set("waiting", feed.a["step"] or "approval", t, q)
                elif pt == "error":
                    feed.set("error", short(p.get("message") or "error", 60), t)
        a = feed.done()
        a["canAsk"] = True
        stale(a, f.stat().st_mtime)
        out.append(a)
    return out


# ------------------------------------------------------------------ Grok CLI
def _upd(r: dict) -> dict | None:
    for k in (None, "update", "params", "data"):
        v = r if k is None else r.get(k)
        if isinstance(v, dict) and "sessionUpdate" in v:
            return v
        if isinstance(v, dict) and isinstance(v.get("update"), dict) and "sessionUpdate" in v["update"]:
            return v["update"]
    return None


def read_grok(st: dict) -> list[dict]:
    root = Path(os.environ.get("GROK_HOME") or home(".grok"))
    live = config.read_json(root / "active_sessions.json", {})
    live = live if isinstance(live, dict) else {}
    out = []
    for f in recent_files(str(root / "sessions" / "*" / "*" / "updates.jsonl"), st["activeMinutes"])[:st["maxAgents"]]:
        sdir = f.parent
        summary = config.read_json(sdir / "summary.json", {})
        summary = summary if isinstance(summary, dict) else {}
        cwd = summary.get("cwd")
        if not cwd:
            cwd_file = sdir.parent / ".cwd"
            cwd = cwd_file.read_text().strip() if cwd_file.is_file() else urllib.parse.unquote(sdir.parent.name)
        feed = Feed("grok", sdir.name, cwd, st["steps"])
        for r in tail_json(f):
            u = _upd(r)
            t = r.get("timestamp") or r.get("ts") or r.get("time") or (u or {}).get("timestamp")
            if not u:
                if r.get("stopReason") or r.get("stop_reason"):
                    feed.pending.clear()
                    feed.set("done", "finished", t)
                continue
            kind = u.get("sessionUpdate")
            if kind == "user_message_chunk":
                if feed.a["status"] != "thinking":
                    feed.add_step("prompt", t, "ok")
                feed.set("thinking", "thinking", t)
            elif kind == "agent_thought_chunk" and not feed.pending:
                feed.set("thinking", "thinking", t)
            elif kind == "tool_call":
                feed.tool_start(u.get("toolCallId"), u.get("kind") or u.get("title") or "tool", u.get("rawInput"),
                                t, u.get("title"))
                if u.get("status") in ("completed", "failed"):
                    feed.tool_end(u.get("toolCallId"), u.get("status") == "completed", t)
            elif kind == "tool_call_update" and u.get("status") in ("completed", "failed"):
                feed.tool_end(u.get("toolCallId"), u.get("status") == "completed", t)
            elif kind in ("agent_message_chunk",) and not feed.pending:
                feed.set("thinking", "writing", t)
            elif kind in ("turn_end", "stop", "end_turn"):
                feed.pending.clear()
                feed.set("done", "finished", t)
        a = feed.done()
        a["canAsk"] = True
        a["live"] = sdir.name in live
        if a["status"] == "thinking" and not a["live"] and time.time() - f.stat().st_mtime > 30:
            a["status"] = "done"  # writing stopped and no grok process holds the session
        stale(a, f.stat().st_mtime)
        if summary.get("title"):
            a["title"] = short(summary["title"], 60)
        out.append(a)
    return out


# ------------------------------------------------------------------ Hermes Agent
def read_hermes(st: dict) -> list[dict]:
    db = Path(os.environ.get("HERMES_HOME") or home(".hermes")) / "state.db"
    if not db.is_file():
        return []
    cut = time.time() - st["activeMinutes"] * 60
    out = []
    try:
        con = sqlite3.connect(f"file:{db}?mode=ro", uri=True, timeout=1)
    except sqlite3.Error:
        return []
    try:
        con.row_factory = sqlite3.Row
        cols = {r[1] for r in con.execute("PRAGMA table_info(sessions)")}
        cwdcol = next((c for c in ("cwd", "workspace", "workspace_path") if c in cols), None)
        rows = con.execute(
            f"SELECT s.id, s.title, s.source, s.ended_at, s.end_reason{', s.' + cwdcol if cwdcol else ''}, "
            "(SELECT MAX(timestamp) FROM messages m WHERE m.session_id = s.id) AS last "
            "FROM sessions s WHERE COALESCE(s.ended_at, s.started_at) >= ? OR s.ended_at IS NULL "
            "ORDER BY last DESC LIMIT ?", (cut, st["maxAgents"])).fetchall()
        for s in rows:
            if (s["last"] or 0) < cut:
                continue
            feed = Feed("hermes", s["id"], s[cwdcol] if cwdcol else None, st["steps"])
            if not feed.a["project"]:
                feed.a["project"] = short(s["title"] or s["source"] or "hermes", 32)
            msgs = con.execute("SELECT role, tool_calls, tool_call_id, tool_name, timestamp, finish_reason "
                               "FROM messages WHERE session_id = ? ORDER BY id DESC LIMIT 60", (s["id"],)).fetchall()
            for m in reversed(msgs):
                t = m["timestamp"]
                if m["role"] == "user":
                    feed.add_step("prompt", t, "ok")
                    feed.set("thinking", "thinking", t)
                elif m["role"] == "assistant":
                    calls = []
                    try:
                        calls = json.loads(m["tool_calls"] or "[]") or []
                    except ValueError:
                        pass
                    for c in calls if isinstance(calls, list) else []:
                        fn = (c or {}).get("function") or {}
                        feed.tool_start(c.get("id"), fn.get("name") or c.get("name"), fn.get("arguments") or c.get("args"), t)
                    if not calls and m["finish_reason"] in ("stop", "end_turn") and not feed.pending:
                        feed.set("done", "finished", t)
                elif m["role"] == "tool":
                    feed.tool_end(m["tool_call_id"], True, t)
            if s["ended_at"]:
                feed.pending.clear()
                feed.set("error" if (s["end_reason"] or "") in ("error", "crash") else "done",
                         "finished", s["ended_at"])
            a = feed.done()
            a["canAsk"] = s["source"] in ("cli", "oneshot", "tui")
            stale(a, s["last"])
            out.append(a)
    except sqlite3.Error:
        pass
    finally:
        con.close()
    return out


# ------------------------------------------------------------------ Gemini CLI (best effort)
def read_gemini(st: dict) -> list[dict]:
    out = []
    for f in recent_files(str(home(".gemini", "tmp", "*", "chats", "*.json")), st["activeMinutes"])[:st["maxAgents"]]:
        d = config.read_json(f, {})
        msgs = d.get("messages") if isinstance(d, dict) else None
        if not isinstance(msgs, list):
            continue
        feed = Feed("gemini", str(d.get("sessionId") or f.stem), d.get("projectRoot") or d.get("cwd"), st["steps"])
        if not feed.a["project"]:
            feed.a["project"] = f.parent.parent.name[:8]
        for m in msgs[-40:]:
            t = m.get("timestamp")
            if m.get("type") == "user":
                feed.add_step("prompt", t, "ok")
                feed.set("thinking", "thinking", t)
            for c in m.get("toolCalls") or []:
                feed.tool_start(c.get("id"), c.get("name"), c.get("args"), t)
                if c.get("status") in ("success", "error", "cancelled"):
                    feed.tool_end(c.get("id"), c.get("status") == "success", t)
            if m.get("type") == "gemini" and not m.get("toolCalls"):
                feed.set("done", "finished", t)
        a = feed.done()
        stale(a, f.stat().st_mtime)
        out.append(a)
    return out


# ------------------------------------------------------------------ Grok Bot desktop (presence only)
def read_grok_bot(st: dict) -> list[dict]:
    """Its transcripts live on the remote box and in an encrypted Electron store; the only local,
    non-secret signal is the local-exec daemon's discovery file. Never read its credential files."""
    d = config.read_json(Path(os.environ.get("SAND_DATA_ROOT") or home(".grokbot")) / "local-exec-daemon.json", {})
    if not isinstance(d, dict) or not d.get("pid"):
        return []
    try:
        os.kill(int(d["pid"]), 0)
    except (OSError, ValueError, OverflowError):
        return []
    n = d.get("inflightCount") or 0
    return [{"id": "grok-bot:local", "agent": "grok-bot", "agentName": "Grok Bot", "session": "local",
             "project": None, "status": "running" if n else "idle",
             "step": f"{n} local tool call{'s' if n != 1 else ''}" if n else None, "since": None,
             "startedAt": iso(d.get("startedAt")), "updatedAt": iso(time.time()), "needsInput": False,
             "question": None, "canAsk": False, "steps": [], "elapsed": None}]


from .t3 import read_t3

READERS = {"claude-code": read_claude, "codex": read_codex, "grok": read_grok, "hermes": read_hermes,
           "gemini": read_gemini, "grok-bot": read_grok_bot, "t3-code": read_t3}


def snapshot(cfg: dict) -> dict:
    """{"agents":[...], "at":ISO}; empty when the monitor is off."""
    st = settings(cfg)
    if not st["enabled"]:
        return {"agents": [], "at": iso(time.time()), "enabled": False}
    allow = st["agents"]
    hooks = hook_events()
    rows = []
    for aid, fn in READERS.items():
        if allow is not None and aid not in allow:
            continue
        try:
            rows += fn(st, hooks) if aid == "claude-code" else fn(st)
        except Exception:  # noqa: BLE001 - one broken agent format must not hide the others
            continue
    projects, hide = st["projects"], set(st["hideProjects"] or [])
    rows = [r for r in rows if (projects is None or r.get("project") in projects) and r.get("project") not in hide]
    order = {"waiting": 0, "running": 1, "thinking": 2, "error": 3, "done": 4, "idle": 5}
    rows.sort(key=lambda r: (order.get(r["status"], 9), -(epoch(r.get("updatedAt")) or 0)))
    return {"agents": rows[: st["maxAgents"]], "at": iso(time.time()), "enabled": True}


def public(snap: dict) -> dict:
    """What goes to the TV: drop the full session id (kept on the PC for asks)."""
    return {"at": snap["at"], "enabled": snap.get("enabled", True),
            "agents": [{k: v for k, v in a.items() if k not in PRIVATE} for a in snap["agents"]]}


PRIVATE = ("session", "live", "cwd")   # stay on the PC: needed to resume, never shown on the TV
