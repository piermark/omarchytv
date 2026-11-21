"""v2.22 AI LIVE, the night shift and skills for LG webOS and Samsung Tizen.

An LG or Samsung app cannot listen on a port, so the PC bridge (omarchy-tv webos-serve) keeps what the Android TV
keeps itself, with the same rules and answers (ports of LiveFeed, NightShift and Skills in the launcher):

  agents (their key)   POST/GET /v1/live, GET /v1/shift, POST /v1/shift/start|end|log|queue,
                       GET /v1/shift/queue/<id>, GET /v1/skills, POST /v1/skills/<id>/install, DELETE /v1/skills/<id>
                       GET/POST /v1/briefing, POST /v1/briefing/show, POST /v1/debate, GET/POST /v1/debate/<id>
  the TV (ask code)    GET /v1/tvlive (the channel, the shift, a skill to approve, the briefing, a debate),
                       POST /v1/shift/answer {id, yes}, POST /v1/skills/answer {id, yes},
                       POST /v1/tvstudio/briefing-shown, POST /v1/tvstudio/debate {id, index}

The studio (v2.22): the morning briefing is kept with the Home's settings ("briefing"); debates are memory only and
only the TV picks the winner. Scenes need the TV's own tools, so they are for Android TV.

Only the TV answers the morning queue and the skill questions. Everything is text. The live channel is memory
only; the shift is kept in ~/.config/omarchy-tv/night-shift.json (0600).
"""

from __future__ import annotations

import json
import os
import re
import threading
import time
from pathlib import Path

from . import home_grid as hg

LEVELS = ("ok", "fail", "info")
MAX_TASKS, MAX_LOG, MAX_QUEUE = 20, 200, 50
MAX_LENGTH_S, WAKE_WITHIN_S, GRACE_S = 16 * 3600, 24 * 3600, 2 * 3600
MAX_EVENTS, MAX_AGENTS, AGENT_TTL_S = 300, 24, 6 * 3600
PERMISSIONS = ("widgets", "notify", "ha.read", "ha.control", "shift", "live")
SKILL_ID = re.compile(r"^[a-z0-9][a-z0-9-]{1,39}$")


class Bad(Exception):
    def __init__(self, code: str, message: str, status: int = 400):
        super().__init__(message)
        self.code, self.status = code, status

    def answer(self) -> dict:
        return {"error": self.code, "message": str(self)}


def multiline(s, max_chars: int, max_lines: int) -> str:
    out = ""
    for i, ln in enumerate(str(s or "").replace("\r\n", "\n").split("\n")[:max_lines]):
        if len(out) >= max_chars:
            break
        out += ("\n" if i else "") + hg.printable(ln, max_chars - len(out))
    return out.strip()


def preview(p):
    """{kind: image, url} (https or LAN), {kind: diff | text, text}, {kind: chart, points, label?}; None if bad."""
    if not isinstance(p, dict):
        return None
    k = p.get("kind", "")
    o = {"kind": k}
    title = hg.printable(p.get("title", ""), 60)
    if title:
        o["title"] = title
    if k == "image":
        u = str(p.get("url", "")).strip()
        return {**o, "url": u} if hg.web_url(u) else None
    if k in ("diff", "text"):
        t = multiline(p.get("text", ""), 8000 if k == "diff" else 4000, 200)
        return {**o, "text": t} if t else None
    if k == "chart":
        pts = p.get("points")
        if not isinstance(pts, list) or not pts or len(pts) > 120:
            return None
        if any(isinstance(x, bool) or not isinstance(x, (int, float)) or x != x or x in (float("inf"), float("-inf")) for x in pts):
            return None
        return {**o, "points": [float(x) for x in pts], "label": hg.printable(p.get("label", ""), 32)}
    return None


# ---------------------------------------------------------------- AI LIVE (memory only)

class LiveFeed:
    def __init__(self):
        self.lock = threading.Lock()
        self.agents: dict = {}
        self.events: list = []
        self.seq = 0

    def _agent(self, name, now):
        a = hg.printable(name or "agent", 40) or "agent"
        if a not in self.agents:
            if len(self.agents) >= MAX_AGENTS:
                idle = [x for x in self.agents.values() if not x["waiting"]] or list(self.agents.values())
                del self.agents[min(idle, key=lambda x: x["at"])["agent"]]
            self.agents[a] = {"agent": a, "text": "", "progress": -1, "at": now, "since": now, "preview": None, "waiting": None}
        return self.agents[a]

    def _event(self, agent, kind, text, now):
        self.seq += 1
        self.events.append({"seq": self.seq, "at": int(now * 1000), "agent": hg.printable(agent, 40), "kind": kind,
                            "text": hg.printable(text, 200)})
        del self.events[:-MAX_EVENTS]

    def event(self, agent, kind, text, now=None):
        now = time.time() if now is None else now
        with self.lock:
            self._event(agent, kind, text, now)
            self._agent(agent, now)["at"] = now

    def now(self, agent, body: dict, now=None) -> dict:
        now = time.time() if now is None else now
        with self.lock:
            n = self._agent(agent, now)
            text = hg.printable(str(body.get("text", "")).replace("\n", " "), 120)
            if text and text != n["text"]:
                n["text"], n["since"] = text, now
                self._event(n["agent"], "status", text, now)
            if "progress" in body:
                n["progress"] = max(-1, min(100, hg._int(body.get("progress"), -1)))
            if "preview" in body:
                n["preview"] = None if body["preview"] is None else preview(body["preview"])
            n["at"] = now
            return self._json(n)

    def from_monitor(self, snap: dict, now=None):
        """omarchy-link's agent monitor (already redacted on this PC): every working agent goes on air by itself."""
        now = time.time() if now is None else now
        for g in (snap or {}).get("agents") or []:
            if not isinstance(g, dict):
                continue
            name, project, status = g.get("agentName") or g.get("agent") or "", g.get("project") or "", g.get("status") or ""
            if not name or status in ("idle", "done"):
                continue
            step = g.get("step") or ""
            text = (step or status) + (" · ⌛" if status == "waiting" else "")
            self.now(f"{name} · {project}" if project else name, {"text": text}, now)

    def waiting(self, agent, aid, question, now=None):
        now = time.time() if now is None else now
        with self.lock:
            n = self._agent(agent, now)
            n["waiting"], n["at"] = {"id": aid, "question": hg.printable(question, 200)}, now
            self._event(agent, "ask", question, now)

    def answered(self, aid, choice, now=None):
        now = time.time() if now is None else now
        with self.lock:
            for n in self.agents.values():
                if n["waiting"] and n["waiting"]["id"] == aid:
                    n["waiting"] = None
                    self._event(n["agent"], "answer", choice, now)
                    return

    @staticmethod
    def _json(n) -> dict:
        o = {"agent": n["agent"], "text": n["text"], "progress": n["progress"], "at": int(n["at"] * 1000),
             "since": int(n["since"] * 1000)}
        if n["waiting"]:
            o["waiting"] = dict(n["waiting"])
        if n["preview"]:
            o["preview"] = n["preview"]
        return o

    def snapshot(self, now=None, max_events: int = 60) -> dict:
        now = time.time() if now is None else now
        with self.lock:
            for k in [k for k, n in self.agents.items() if not n["waiting"] and now - n["at"] > AGENT_TTL_S]:
                del self.agents[k]
            return {"agents": [self._json(n) for n in self.agents.values()], "events": self.events[-max_events:],
                    "now": int(now * 1000)}


# ---------------------------------------------------------------- the night shift

def at(v, now: float) -> float:
    """"07:30" (the next one, local time) or epoch milliseconds → epoch seconds; -1 if neither."""
    if isinstance(v, (int, float)) and not isinstance(v, bool):
        return v / 1000.0
    s = str(v or "").strip()
    if re.fullmatch(r"\d{12,14}", s):
        return int(s) / 1000.0
    m = re.fullmatch(r"([01]?\d|2[0-3]):([0-5]\d)", s)
    if not m:
        return -1
    t = time.localtime(now)
    cand = time.mktime((t.tm_year, t.tm_mon, t.tm_mday, int(m.group(1)), int(m.group(2)), 0, 0, 0, -1))
    if cand <= now:
        t2 = time.localtime(now + 86400)
        cand = time.mktime((t2.tm_year, t2.tm_mon, t2.tm_mday, int(m.group(1)), int(m.group(2)), 0, 0, 0, -1))
    return cand


class Shift:
    def __init__(self, path: str | None = None):
        base = os.environ.get("XDG_CONFIG_HOME") or os.path.join(os.path.expanduser("~"), ".config")
        self.path = path or os.path.join(base, "omarchy-tv", "night-shift.json")
        self.lock = threading.Lock()

    def load(self) -> dict:
        try:
            d = json.loads(Path(self.path).read_text(encoding="utf-8"))
            return d if isinstance(d, dict) else {}
        except (OSError, ValueError):
            return {}

    def _save(self, s: dict):
        os.makedirs(os.path.dirname(self.path), exist_ok=True)
        tmp = self.path + ".tmp"
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(s, f, ensure_ascii=False)
        os.replace(tmp, self.path)

    def edit(self, op):
        with self.lock:
            s = self.load()
            out = op(s)
            self._save(s)
            return out

    @staticmethod
    def running(s: dict, now: float) -> bool:
        return bool(s.get("active")) and now < s.get("until", 0)

    @staticmethod
    def writable(s: dict, now: float) -> bool:
        return bool(s.get("startedAt")) and bool(s.get("active")) and now < s.get("until", 0) + GRACE_S

    def quiet(self, now=None) -> bool:
        return self.running(self.load(), time.time() if now is None else now)

    def start(self, body: dict, by: str, now=None) -> dict:
        now = time.time() if now is None else now

        def op(s):
            if self.running(s, now) and not body.get("replace"):
                raise Bad("running", "a shift is running; end it or pass replace:true", 409)
            raw = body.get("tasks")
            if not isinstance(raw, list) or not raw:
                raise Bad("bad_tasks", f"tasks: 1 to {MAX_TASKS} lines")
            if len(raw) > MAX_TASKS:
                raise Bad("too_many", f"{MAX_TASKS} tasks")
            tasks = [{"text": t} for t in (hg.printable(x.get("text") if isinstance(x, dict) else x, 200) for x in raw) if t]
            if not tasks:
                raise Bad("bad_tasks", "tasks: text")
            until = at(body.get("until"), now)
            if until <= now or until - now > MAX_LENGTH_S:
                raise Bad("bad_until", "until: \"07:00\" or epoch ms, within 16 hours")
            wake = 0
            if body.get("wake") is not None:
                wake = at(body.get("wake"), now)
                if wake <= now or wake - now > WAKE_WITHIN_S:
                    raise Bad("bad_wake", "wake: \"07:30\" or epoch ms, within 24 hours")
            s.clear()
            s.update({"active": True, "startedAt": now, "until": until, "wake": wake, "by": hg.printable(by, 40),
                      "tasks": tasks, "log": [], "queue": [], "seq": 0})
            return self.status(s, now)
        return self.edit(op)

    def end(self, now=None) -> dict:
        now = time.time() if now is None else now

        def op(s):
            if not s.get("active"):
                raise Bad("no_shift", "no shift is running", 409)
            s["active"], s["endedAt"] = False, now
            return self.status(s, now)
        return self.edit(op)

    def log(self, agent: str, body: dict, now=None) -> dict:
        now = time.time() if now is None else now

        def op(s):
            if not self.writable(s, now):
                raise Bad("no_shift", "no shift is running: the diary is closed", 409)
            level = body.get("level", "info")
            if level not in LEVELS:
                raise Bad("bad_level", "ok, fail or info")
            text = hg.printable(body.get("text", ""), 200)
            if not text:
                raise Bad("bad_text", "text: 1 to 200 characters")
            e = {"at": int(now * 1000), "agent": hg.printable(agent, 40), "level": level, "text": text}
            detail = multiline(body.get("detail", ""), 4000, 80)
            if detail:
                e["detail"] = detail
            if "task" in body:
                t = hg._int(body.get("task"), -1)
                if not 0 <= t < len(s["tasks"]):
                    raise Bad("bad_task", "task: an index of tasks[]")
                e["task"] = t
            s["log"].append(e)
            del s["log"][:-MAX_LOG]
            return e
        return self.edit(op)

    def queue(self, agent: str, body: dict, now=None) -> dict:
        now = time.time() if now is None else now

        def op(s):
            if not self.writable(s, now):
                raise Bad("no_shift", "no shift is running: ask the owner directly", 409)
            title = hg.printable(body.get("title", ""), 120)
            if not title:
                raise Bad("bad_title", "title: 1 to 120 characters")
            if sum(1 for q in s["queue"] if q["state"] == "pending") >= MAX_QUEUE:
                raise Bad("too_many", f"{MAX_QUEUE} decisions are already waiting", 409)
            s["seq"] = s.get("seq", 0) + 1
            e = {"id": f"q{s['seq']}", "at": int(now * 1000), "agent": hg.printable(agent, 40), "title": title, "state": "pending"}
            detail = multiline(body.get("detail", ""), 8000, 200)
            if detail:
                e["detail"] = detail
            risk = hg.printable(body.get("risk", ""), 80)
            if risk:
                e["risk"] = risk
            s["queue"].append(e)
            return e
        return self.edit(op)

    def decision(self, qid: str) -> dict:
        for q in self.load().get("queue", []):
            if q.get("id") == qid:
                return q
        raise Bad("no_such_item", qid, 404)

    def answer(self, qid: str, yes: bool, now=None) -> dict:
        now = time.time() if now is None else now

        def op(s):
            for q in s.get("queue", []):
                if q.get("id") == qid:
                    if q["state"] != "pending":
                        raise Bad("answered", "already " + q["state"], 409)
                    q["state"], q["answeredAt"] = ("approved" if yes else "denied"), int(now * 1000)
                    return q
            raise Bad("no_such_item", qid, 404)
        return self.edit(op)

    def status(self, s: dict | None = None, now=None) -> dict:
        now = time.time() if now is None else now
        s = self.load() if s is None else s
        if not s.get("startedAt"):
            return {"state": "none"}
        log, q = s.get("log", []), s.get("queue", [])
        out = {"state": "running" if self.running(s, now) else "ended", "startedAt": int(s["startedAt"] * 1000),
               "until": int(s["until"] * 1000), "wake": int(s.get("wake", 0) * 1000), "by": s.get("by", ""),
               "tasks": s.get("tasks", []),
               "counts": {"ok": sum(1 for e in log if e["level"] == "ok"), "fail": sum(1 for e in log if e["level"] == "fail"),
                          "info": sum(1 for e in log if e["level"] == "info"),
                          "waiting": sum(1 for e in q if e["state"] == "pending")}}
        if "endedAt" in s:
            out["endedAt"] = int(s["endedAt"] * 1000)
        return out

    def full(self, now=None) -> dict:
        s = self.load()
        out = self.status(s, now)
        if s.get("startedAt"):
            out["log"], out["queue"] = s.get("log", []), s.get("queue", [])
        return out


# ---------------------------------------------------------------- skills

def catalog_dir() -> Path:
    return Path(__file__).resolve().parent / "skills"


def check_skill(sk: dict) -> dict:
    sid = str(sk.get("id", ""))
    if not SKILL_ID.match(sid):
        raise Bad("bad_skill", "id: a-z, 0-9, dashes, 2 to 40")
    name, author, version = hg.printable(sk.get("name", ""), 40), hg.printable(sk.get("author", ""), 40), str(sk.get("version", ""))
    if not name or not author or not re.fullmatch(r"\d{1,3}\.\d{1,3}(\.\d{1,3})?", version):
        raise Bad("bad_skill", "name, author and version (1.0) are required")
    perms = sk.get("permissions") or []
    for p in perms:
        if p not in PERMISSIONS:
            raise Bad("bad_skill", f"permission {p}: one of {', '.join(PERMISSIONS)}")
    ws = sk.get("widgets") or []
    if len(ws) > 16:
        raise Bad("bad_skill", "16 widgets at most")
    if ws and "widgets" not in perms:
        raise Bad("bad_skill", "widgets need the \"widgets\" permission")
    for w in ws:
        if not isinstance(w, dict):
            raise Bad("bad_skill", "widgets: objects")
        if str(w.get("type", "")).startswith("homeassistant.") and "ha.read" not in perms:
            raise Bad("bad_skill", f"{w.get('type')} needs ha.read")
        probe = {k: v for k, v in w.items() if k != "page"}
        probe["id"] = "probe"
        try:
            hg.create([], probe, "skill", 0)
        except hg.Bad as e:
            raise Bad("bad_skill", f"widget {w.get('id')}: {e}") from None
    return {"id": sid, "name": name, "author": author, "version": version,
            "description": hg.printable(sk.get("description", ""), 200), "license": hg.printable(sk.get("license", ""), 20),
            "permissions": list(perms), "widgets": ws, "page": hg.printable(sk.get("page", name), 32),
            "agent": multiline(sk.get("agent", ""), 2000, 40)}


def catalog() -> dict:
    out = {}
    for f in sorted(catalog_dir().glob("*.json")):
        try:
            sk = check_skill(json.loads(f.read_text(encoding="utf-8")))
            out[sk["id"]] = sk
        except (OSError, ValueError, Bad):
            continue
    return out


def skill_install(cfg: dict, sk: dict, now: int) -> dict:
    inst = cfg.setdefault("skills", {})
    if sk["id"] in inst:
        skill_remove(cfg, sk["id"])
    if len(inst) >= 32:
        raise Bad("too_many", "32 skills", 409)
    all_ = cfg.setdefault("widgets", [])
    ids, page = [], 0
    if sk["widgets"]:
        page = hg.on_demand(cfg, "skill-" + sk["id"], False, now)
        hg.page_rename(cfg, page, sk["page"], now)
    for i, w in enumerate(sk["widgets"]):
        w = dict(w)
        wid = ("s." + sk["id"] + "." + str(w.get("id", i))).lower()[:48]
        w.update({"id": wid, "page": page})
        hg.create(all_, w, "skill:" + sk["id"], now)
        ids.append(wid)
    rec = {"version": sk["version"], "name": sk["name"], "author": sk["author"], "permissions": sk["permissions"],
           "widgets": ids, "page": page, "agent": sk["agent"], "at": now}
    inst[sk["id"]] = rec
    return rec


def skill_remove(cfg: dict, sid: str) -> dict:
    inst = cfg.setdefault("skills", {})
    rec = inst.get(sid)
    if rec is None:
        raise Bad("no_such_skill", sid, 404)
    cfg["widgets"] = [w for w in cfg.get("widgets", []) if w.get("id") not in rec.get("widgets", [])]
    page = rec.get("page", 0)
    if 1 < page <= hg.total(cfg) and hg.count(cfg, page) == 0:
        hg.page_delete(cfg, page, None, 0)
    del inst[sid]
    return rec


def monitor_loop(stop: threading.Event, period: float = 5.0):
    """While the bridge runs: omarchy-link's agent monitor (only when the owner turned it on with
    `omarchy-link agents on`) feeds AI LIVE, as the Android TV does with the PC's agent list."""
    try:
        from omarchy_link import agents, config   # noqa: PLC0415  optional: omarchy-link may not be installed
    except ImportError:
        return
    while not stop.wait(period):
        try:
            cfg = config.load_config()
            if agents.settings(cfg).get("enabled"):
                LIVE.from_monitor(agents.public(agents.snapshot(cfg)))
        except Exception:  # noqa: BLE001 - a broken agent file never stops the bridge
            continue


# ---------------------------------------------------------------- the studio (a port of Studio in the launcher)

MAX_SECTIONS, MAX_LINES, MAX_DEBATERS = 8, 8, 4


def briefing_set(cfg: dict, b: dict, by: str, now: float) -> dict:
    title = hg.printable(b.get("title") or "Morning briefing", 60)
    raw = b.get("sections")
    if not isinstance(raw, list) or not raw:
        raise Bad("bad_sections", f"sections: 1 to {MAX_SECTIONS} {{title, lines[]}}")
    if len(raw) > MAX_SECTIONS:
        raise Bad("too_many", f"{MAX_SECTIONS} sections")
    secs = []
    for s in raw:
        if not isinstance(s, dict):
            raise Bad("bad_sections", "sections: objects")
        lines = s.get("lines") if isinstance(s.get("lines"), list) else []
        out = [x for x in (hg.printable(ln, 120) for ln in lines[:MAX_LINES]) if x]
        st = hg.printable(s.get("title", ""), 40)
        if not st or not out:
            raise Bad("bad_sections", "every section needs a title and lines")
        secs.append({"title": st, "lines": out})
    wake = 0
    if b.get("wake") is not None:
        w = at(b.get("wake"), now)
        if w <= now or w - now > WAKE_WITHIN_S:
            raise Bad("bad_wake", "wake: \"07:30\" or epoch ms, within 24 hours")
        wake = int(w * 1000)
    br = {"title": title, "sections": secs, "say": multiline(b.get("say", ""), 1500, 20), "by": hg.printable(by, 40),
          "at": int(now * 1000), "wake": wake, "shown": False}
    cfg["briefing"] = br
    return br


def briefing_due(cfg: dict, now: float) -> bool:
    b = cfg.get("briefing") or {}
    w = b.get("wake") or 0
    return bool(w) and now * 1000 >= w and now * 1000 - w < 18 * 3600_000 and not b.get("shown")


class Debates:
    def __init__(self):
        self.lock = threading.Lock()
        self.all: dict = {}
        self.seq = 0

    def start(self, b: dict, by: str, now: float) -> dict:
        topic = hg.printable(b.get("topic", ""), 160)
        if not topic:
            raise Bad("bad_topic", "topic: 1 to 160 characters")
        with self.lock:
            while len(self.all) >= 8:
                self.all.pop(next(iter(self.all)))
            self.seq += 1
            d = {"id": f"d{self.seq}", "topic": topic, "by": hg.printable(by, 40), "at": int(now * 1000), "state": "open", "entries": []}
            self.all[d["id"]] = d
            return d

    def get(self, did: str) -> dict:
        d = self.all.get(did)
        if d is None:
            raise Bad("no_such_debate", did, 404)
        return d

    def post(self, did: str, agent: str, b: dict, now: float) -> dict:
        with self.lock:
            d = self.get(did)
            if d["state"] != "open":
                raise Bad("closed", "the owner has already chosen", 409)
            text = multiline(b.get("text", ""), 1200, 24)
            if not text:
                raise Bad("bad_text", "text: your answer, ≤1200")
            who = hg.printable(agent, 40)
            e = {"agent": who, "text": text, "at": int(now * 1000)}
            if b.get("preview") is not None:
                pv = preview(b.get("preview"))
                if pv is None:
                    raise Bad("bad_preview", "{kind: image, url}, {kind: diff | text, text}, {kind: chart, points}")
                e["preview"] = pv
            for i, x in enumerate(d["entries"]):
                if x["agent"] == who:   # a second post replaces the first
                    d["entries"][i] = e
                    return d
            if len(d["entries"]) >= MAX_DEBATERS:
                raise Bad("full", f"{MAX_DEBATERS} agents already answered", 409)
            d["entries"].append(e)
            return d

    def choose(self, did: str, index, now: float) -> dict:   # the TV only
        with self.lock:
            d = self.get(did)
            if not isinstance(index, int) or isinstance(index, bool) or not 0 <= index < len(d["entries"]):
                raise Bad("bad_choice", "no such answer")
            if d["state"] != "open":
                raise Bad("closed", "already chosen", 409)
            d.update({"state": "chosen", "winner": d["entries"][index]["agent"], "chosenAt": int(now * 1000)})
            return d

    def on_air(self, now: float):
        """The newest open debate, or the last one chosen in the past 10 minutes (the TV shows the winner)."""
        last = None
        for d in self.all.values():
            if d["state"] == "open" or now * 1000 - d.get("chosenAt", 0) < 600_000:
                last = d
        return last


# ---------------------------------------------------------------- the bridge's state and routes

LIVE = LiveFeed()
SHIFT = Shift()
SKILL_ASK: dict = {}   # the skill an agent asked the owner to install: {id, at}
DEBATES = Debates()
STUDIO: dict = {}   # "show": when an agent asked to put the briefing on air now (epoch ms)


def handles(path: str) -> bool:
    return path in ("/v1/live", "/v1/shift", "/v1/tvlive", "/v1/skills", "/v1/briefing", "/v1/debate") or \
        path.startswith(("/v1/shift/", "/v1/skills/", "/v1/tvskills/", "/v1/briefing/", "/v1/debate/", "/v1/tvstudio/"))


def tv_only(path: str) -> bool:
    return path in ("/v1/tvlive", "/v1/shift/answer", "/v1/skills/answer") or path.startswith(("/v1/tvskills/", "/v1/tvstudio/"))


def route(store, method: str, path: str, body: dict, who: str = "agent", now=None) -> tuple[int, dict]:
    now = time.time() if now is None else now
    try:
        return _route(store, method, path, body, who, now)
    except (Bad, hg.Bad) as e:
        return e.status, e.answer()


def _route(store, m, p, body, who, now):
    if p == "/v1/live":
        if m == "GET":
            return 200, LIVE.snapshot(now)
        if body.get("preview") is not None and preview(body.get("preview")) is None:
            raise Bad("bad_preview", "{kind: image, url} (https or LAN), {kind: diff | text, text}, {kind: chart, points}")
        return 200, {"ok": True, "now": LIVE.now(who, body, now)}
    if p == "/v1/shift" and m == "GET":
        return 200, SHIFT.status(now=now)
    qm = re.fullmatch(r"/v1/shift/queue/(q\d{1,6})", p)
    if qm and m == "GET":
        return 200, SHIFT.decision(qm.group(1))
    if m == "POST" and p == "/v1/shift/start":
        return 200, {"ok": True, "shift": SHIFT.start(body, who, now)}
    if m == "POST" and p == "/v1/shift/end":
        return 200, {"ok": True, "shift": SHIFT.end(now)}
    if m == "POST" and p == "/v1/shift/log":
        e = SHIFT.log(who, body, now)
        LIVE.event(who, e["level"], e["text"], now)
        return 200, {"ok": True, "item": e}
    if m == "POST" and p == "/v1/shift/queue":
        e = SHIFT.queue(who, body, now)
        LIVE.event(who, "queued", e["title"], now)
        return 200, {"ok": True, "item": e}
    if m == "POST" and p == "/v1/shift/answer":   # the TV only
        return 200, {"ok": True, "item": SHIFT.answer(str(body.get("id", "")), body.get("yes") is True, now)}
    if p == "/v1/skills" and m == "GET":
        cat = [{k: sk[k] for k in ("id", "name", "author", "version", "description", "permissions")} for sk in catalog().values()]
        return 200, {"catalog": cat, "installed": store.load().get("skills", {})}
    if m == "POST" and p == "/v1/skills/answer":   # the TV only
        sid = str(body.get("id", ""))
        ask = SKILL_ASK.pop("ask", None)
        if not ask or ask["id"] != sid:
            raise Bad("no_question", "no install question for this skill", 409)
        if body.get("yes") is not True:
            return 200, {"ok": True, "installed": False}
        sk = catalog().get(sid)
        if sk is None:
            raise Bad("no_such_skill", sid, 404)
        rec = store.edit(lambda cfg: skill_install(cfg, sk, int(now)))
        return 200, {"ok": True, "installed": True, "skill": rec}
    sm = re.fullmatch(r"/v1/skills/([a-z0-9][a-z0-9-]{1,39})(/install)?", p)
    if sm:
        sid = sm.group(1)
        if m == "POST" and sm.group(2):
            if sid not in catalog():
                raise Bad("no_such_skill", sid, 404)
            SKILL_ASK["ask"] = {"id": sid, "at": int(now * 1000)}
            return 200, {"ok": True, "asked": True, "message": "the owner answers on the TV; check GET /v1/skills"}
        if m == "DELETE" and not sm.group(2):
            store.edit(lambda cfg: skill_remove(cfg, sid))
            return 200, {"ok": True, "removed": sid}
    if m == "POST" and p in ("/v1/tvskills/install", "/v1/tvskills/remove"):   # the TV only: the owner chose it there
        sid = str(body.get("id", ""))
        if p.endswith("/remove"):
            store.edit(lambda cfg: skill_remove(cfg, sid))
            return 200, {"ok": True, "removed": sid}
        sk = catalog().get(sid)
        if sk is None:
            raise Bad("no_such_skill", sid, 404)
        SKILL_ASK.pop("ask", None)
        return 200, {"ok": True, "installed": True, "skill": store.edit(lambda cfg: skill_install(cfg, sk, int(now)))}
    if p == "/v1/briefing":   # v2.22 the studio
        if m == "GET":
            return 200, store.load().get("briefing") or {"state": "none"}
        if m == "POST":
            br = store.edit(lambda cfg: briefing_set(cfg, body, who, now))
            LIVE.event(who, "info", "prepared the morning briefing", now)
            return 200, {"ok": True, "briefing": br}
    if m == "POST" and p == "/v1/briefing/show":
        if not store.load().get("briefing"):
            raise Bad("no_briefing", "set one first", 409)
        STUDIO["show"] = int(now * 1000)
        return 200, {"ok": True}
    if m == "POST" and p == "/v1/debate":
        d = DEBATES.start(body, who, now)
        LIVE.event(who, "info", "debate: " + d["topic"], now)
        return 200, {"ok": True, "debate": d}
    dm = re.fullmatch(r"/v1/debate/(d\d{1,6})", p)
    if dm:
        if m == "GET":
            return 200, DEBATES.get(dm.group(1))
        if m == "POST":
            d = DEBATES.post(dm.group(1), who, body, now)
            LIVE.event(who, "info", "answered the debate", now)
            return 200, {"ok": True, "debate": d}
    if m == "POST" and p == "/v1/tvstudio/briefing-shown":   # the TV only
        STUDIO.pop("show", None)

        def shown(cfg):
            if cfg.get("briefing"):
                cfg["briefing"]["shown"] = True
        store.edit(shown)
        return 200, {"ok": True}
    if m == "POST" and p == "/v1/tvstudio/debate":   # the TV only: the owner's pick
        d = DEBATES.choose(str(body.get("id", "")), body.get("index"), now)
        LIVE.event(d["winner"], "ok", "won the debate: " + d["topic"], now)
        return 200, {"ok": True, "debate": d}
    if p == "/v1/tvlive" and m == "GET":
        cfg = store.load()
        br = cfg.get("briefing")
        on_air = bool(br) and (briefing_due(cfg, now) or now * 1000 - STUDIO.get("show", 0) < 600_000)
        ask = SKILL_ASK.get("ask")
        sk = catalog().get(ask["id"]) if ask and now * 1000 - ask["at"] < 600_000 else None
        return 200, {"live": LIVE.snapshot(now, 40), "shift": SHIFT.full(now),
                     "skillAsk": {k: sk[k] for k in ("id", "name", "author", "version", "description", "permissions")} if sk else None,
                     "skills": [{k: s[k] for k in ("id", "name", "author", "version", "description", "permissions")} for s in catalog().values()],
                     "installed": list(cfg.get("skills", {}).keys()),
                     "briefing": br if on_air else None, "debate": DEBATES.on_air(now)}
    return 404, {"error": "not_found"}
