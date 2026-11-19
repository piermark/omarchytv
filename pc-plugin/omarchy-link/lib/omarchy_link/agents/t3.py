"""Read T3 run metadata only; never open conversations, payloads or credentials."""
from __future__ import annotations

import hashlib
import os
import re
import sqlite3
import time
import urllib.parse
from contextlib import closing
from pathlib import Path

from .redact import base, short

_cache = None


def read_t3(st: dict) -> list[dict]:
    from . import epoch, iso

    global _cache
    root = Path(os.environ.get("T3CODE_HOME") or Path.home() / ".t3")
    path = root / "userdata" / "statev2.sqlite"
    now, tick = time.time(), time.monotonic()
    if not path.is_file():
        return []
    if _cache and _cache[0] == path and tick - _cache[1] < 5:
        return list(_cache[2])

    def message(status, text):
        return [{"id": "t3-code:" + status, "agent": "t3-code", "agentName": "T3 Code",
                 "title": "", "status": status, "step": text, "project": None,
                 "canAsk": False, "needsInput": False, "steps": []}]

    try:
        with closing(sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True, timeout=0.2)) as db:
            db.set_progress_handler(lambda: time.monotonic() - tick > 0.25, 1000)
            rows = db.execute("""
                SELECT t.thread_id, t.title, p.workspace_root, r.status, r.requested_at
                FROM orchestration_v2_projection_runs r
                JOIN orchestration_v2_projection_threads t ON t.thread_id = r.thread_id
                LEFT JOIN projection_projects p ON p.project_id = t.project_id
                WHERE r.status IN ('preparing', 'queued', 'starting', 'running', 'waiting')
                  AND t.archived_at IS NULL AND t.deleted_at IS NULL
                ORDER BY CASE r.status WHEN 'waiting' THEN 0 WHEN 'running' THEN 1 ELSE 2 END,
                         r.requested_at DESC
            """).fetchall()
            pending = dict(db.execute("""
                SELECT thread_id, MAX(created_at) FROM orchestration_v2_projection_runtime_requests
                WHERE status = 'pending' AND resolved_at IS NULL
                  AND kind IN ('user_input', 'mcp-elicitation', 'command')
                GROUP BY thread_id
            """))
        modified = max(f.stat().st_mtime for f in (path, Path(str(path) + "-wal")) if f.exists())
        if rows and now - modified > 120:
            result = message("error", "Status unavailable")
        else:
            result, seen = [], set()
            for tid, title, cwd, state, requested in rows:
                if tid in seen:
                    continue
                seen.add(tid)
                status = "waiting" if tid in pending else "running" if state in ("running", "waiting") else "thinking"
                since = pending.get(tid, requested)
                title = re.sub(r"https?://\S+", lambda m: urllib.parse.urlsplit(m[0]).hostname or "link", title or "Untitled thread")
                title = re.sub(r"(?<!\w)(?:/|~/|[A-Za-z]:[\\/])[^\s]+",
                               lambda m: base(m[0].replace("\\", "/")), title)
                result.append({"id": "t3-code:" + hashlib.sha256(tid.encode()).hexdigest()[:16],
                               "agent": "t3-code", "agentName": "T3 Code", "project": short(base(cwd), 40) if cwd else None,
                               "title": short(title or "Untitled thread", 80), "status": status,
                               "step": {"waiting": "Needs input", "running": "Working", "thinking": "Queued"}[status],
                               "since": iso(since), "startedAt": iso(requested), "updatedAt": iso(since),
                               "elapsed": int(max(0, now - (epoch(since) or now))),
                               "canAsk": False, "needsInput": status == "waiting", "steps": []})
            if not result:
                result = message("idle", "No active threads")
    except (OSError, sqlite3.Error, ValueError, TypeError):
        result = message("error", "Status unavailable")
    _cache = (path, tick, result)
    return list(result)
