"""Living screensaver feed: what the TV's screensaver tells in ASCII art next to the weather.

- notes:  the PC's desktop notifications (all of them, as they arrive), only while a TV asked for them
          ("PC notifications on the screensaver", off by default) and never written to disk.
- github: your GitHub activity (gh's login, or "githubUser") and the repos in "githubRepos".
- x:      X mentions. "notify" (free): the X notifications your browser shows on this PC.
          "api" (opt-in, paid per use by X): GET /2/users/:id/mentions with your bearer token from
          ~/.config/omarchy-link/x.json (0600, `omarchy-link x setup`), only while the TV screensaver runs,
          only new posts (since_id), and never past "xMonthlyUsd" (estimated spend, default 1 USD a month).
The TV decides what it wants ("living_prefs"); the PC can refuse with "livingToTv": false.
"""

from __future__ import annotations

import datetime as dt
import json
import re
import shutil
import subprocess
import threading
import time
import urllib.parse
import urllib.request
from collections import deque

from . import config

X_FILE = config.CONFIG_DIR / "x.json"
X_API = "https://api.x.com/2"
X_MODES = ("off", "notify", "api")
X_COST_POST = 0.005      # USD per post read (conservative: app-only bearer, not the cheaper own-account rate)
X_COST_USER = 0.010      # USD per user returned (the author expansion)
GH_EVERY_S = 300
BROWSERS = ("chromium", "chrome", "google-chrome", "firefox", "brave", "vivaldi", "zen", "librewolf", "edge")
CTRL = re.compile(r"[\x00-\x08\x0b-\x1f\x7f]")
TAG = re.compile(r"<[^>]{1,80}>")


def clean(text: object, limit: int) -> str:
    s = TAG.sub("", CTRL.sub("", str(text or ""))).replace("\n", " ").strip()
    return re.sub(r"\s+", " ", s)[:limit]


def parse_prefs(d: object) -> dict:
    d = d if isinstance(d, dict) else {}
    x = d.get("x")
    return {"notes": d.get("notes") is True, "github": d.get("github", True) is not False,
            "x": x if x in X_MODES else "off"}


# ------------------------------------------------------------------ notifications (D-Bus)

def parse_notify(block: list[str]) -> dict | None:
    """One `dbus-monitor` Notify call (its lines) -> {app, title, body}; the first 5 args are
    string app_name, uint32 replaces_id, string icon, string summary, string body."""
    args: list[str] = []
    cur: list[str] | None = None
    for line in block[1:]:
        if cur is not None:
            cur.append(line)
            if line.endswith('"'):
                args.append("\n".join(cur)[:-1])
                cur = None
            continue
        t = line.strip()
        if t.startswith('string "'):
            body = t[len('string "'):]
            if body.endswith('"') and len(body) >= 1:
                args.append(body[:-1])
            else:
                cur = [body]
        elif t.startswith("uint32 "):
            args.append(t[7:])
        elif t.startswith(("array [", "dict entry", "int32 ")):
            break
        if len(args) >= 5:
            break
    if len(args) < 5:
        return None
    app, title, body = args[0], args[3], args[4]
    if not (title or body):
        return None
    return {"app": clean(app, 32), "title": clean(title, 80), "body": clean(body, 200)}


def x_from_note(n: dict) -> dict | None:
    """A browser notification from X ("… mentioned you", "x.com") -> an X item, else None."""
    app = n.get("app", "").lower()
    blob = (n.get("title", "") + " " + n.get("body", "")).lower()
    from_browser = any(b in app for b in BROWSERS)
    if n.get("app", "").strip().lower() in ("x", "x.com") or (from_browser and ("x.com" in blob or n.get("title", "").strip() in ("X", "X (formerly Twitter)"))):
        return {"kind": "notify", "user": n.get("title", "X"), "text": n.get("body", "")}
    return None


class NoteMonitor:
    """`dbus-monitor` on the session bus for org.freedesktop.Notifications.Notify (Linux)."""

    def __init__(self, on_note):
        self.on_note = on_note
        self.proc = None

    def running(self) -> bool:
        return self.proc is not None and self.proc.poll() is None

    def start(self) -> bool:
        if self.running():
            return True
        tool = shutil.which("dbus-monitor")
        if not tool:
            return False
        try:
            self.proc = subprocess.Popen(
                [tool, "--session", "interface='org.freedesktop.Notifications',member='Notify'"],
                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, stdin=subprocess.DEVNULL, text=True, errors="replace")
        except OSError:
            self.proc = None
            return False
        threading.Thread(target=self._read, args=(self.proc,), daemon=True).start()
        return True

    def stop(self) -> None:
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
        self.proc = None

    def _read(self, proc) -> None:
        block: list[str] = []
        done = True
        for line in proc.stdout:
            line = line.rstrip("\n")
            if line.startswith(("method call", "signal", "method return", "error")):
                block, done = [line], "member=Notify" not in line
                continue
            if done:
                continue
            block.append(line)
            n = parse_notify(block)   # emit as soon as the 5 fields are complete, not at the next message
            if n:
                done = True
                self.on_note(n)


# ------------------------------------------------------------------ GitHub

def describe_event(e: dict) -> dict | None:
    """A GitHub event -> {repo, what, text, at}; None for the noisy ones."""
    t, p = e.get("type", ""), e.get("payload") or {}
    repo = clean((e.get("repo") or {}).get("name", ""), 60)
    who = clean((e.get("actor") or {}).get("login", ""), 39)
    text = ""
    if t == "PushEvent":
        commits = p.get("commits") or []
        n = p.get("size") or len(commits)
        what = f"push {n}"
        text = clean(commits[-1].get("message", "").split("\n")[0], 90) if commits else ""
    elif t == "PullRequestEvent":
        pr = p.get("pullRequest") or p.get("pull_request") or {}
        what = f"PR {p.get('action', '')} #{pr.get('number', '')}"
        text = clean(pr.get("title", ""), 90)
    elif t == "IssuesEvent":
        what = f"issue {p.get('action', '')} #{(p.get('issue') or {}).get('number', '')}"
        text = clean((p.get("issue") or {}).get("title", ""), 90)
    elif t == "IssueCommentEvent":
        what = f"comment #{(p.get('issue') or {}).get('number', '')}"
        text = clean((p.get("comment") or {}).get("body", ""), 90)
    elif t == "WatchEvent":
        what, text = "★ star", who
    elif t == "ForkEvent":
        what, text = "fork", who
    elif t == "ReleaseEvent":
        what = "release"
        text = clean((p.get("release") or {}).get("tag_name", ""), 40)
    elif t == "CreateEvent" and p.get("ref_type") in ("tag", "repository"):
        what, text = f"new {p.get('ref_type')}", clean(p.get("ref") or "", 60)
    else:
        return None
    return {"repo": repo, "what": clean(what, 24), "who": who, "text": text, "at": e.get("created_at", "")}


def _gh_json(path: str):
    if shutil.which("gh"):
        r = subprocess.run(["gh", "api", path], capture_output=True, text=True, timeout=20, stdin=subprocess.DEVNULL)
        if r.returncode == 0:
            return json.loads(r.stdout or "null")
    req = urllib.request.Request("https://api.github.com/" + path, headers={"User-Agent": "omarchy-link",
                                                                         "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(req, timeout=20) as r:   # noqa: S310 (fixed host)
        return json.loads(r.read(2_000_000))


def github_items(cfg: dict) -> list[dict]:
    user = cfg.get("githubUser")
    if not user and shutil.which("gh"):
        try:
            user = subprocess.run(["gh", "api", "user", "--jq", ".login"], capture_output=True, text=True,
                                  timeout=15, stdin=subprocess.DEVNULL).stdout.strip()
        except (OSError, subprocess.SubprocessError):
            user = None
    paths = []
    if user and re.match(r"^[A-Za-z0-9-]{1,39}$", user):
        paths.append(f"users/{user}/events?per_page=20")
    for repo in (cfg.get("githubRepos") or [])[:5]:
        if isinstance(repo, str) and re.match(r"^[A-Za-z0-9-]{1,39}/[A-Za-z0-9._-]{1,100}$", repo):
            paths.append(f"repos/{repo}/events?per_page=20")
    out, seen = [], set()
    for path in paths:
        try:
            evs = _gh_json(path)
        except (OSError, ValueError, subprocess.SubprocessError):
            continue
        for e in evs if isinstance(evs, list) else []:
            if not isinstance(e, dict) or e.get("id") in seen:
                continue
            seen.add(e.get("id"))
            d = describe_event(e)
            if d:
                out.append(d)
    out.sort(key=lambda d: d["at"], reverse=True)
    return out[:6]


# ------------------------------------------------------------------ X API (opt-in)

def x_load() -> dict:
    try:
        return config.read_json(X_FILE, {}) if X_FILE.exists() and config.is_private_file(X_FILE) else {}
    except ValueError:
        return {}


def x_save(d: dict) -> None:
    config.write_json_private(X_FILE, d)


def _month() -> str:
    return dt.date.today().strftime("%Y-%m")


def x_spent(d: dict) -> float:
    s = d.get("spend") or {}
    return float(s.get("usd", 0)) if s.get("month") == _month() else 0.0


def _x_get(bearer: str, path: str, params: dict) -> dict:
    url = f"{X_API}/{path}?{urllib.parse.urlencode(params)}"
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {bearer}", "User-Agent": "omarchy-link"})
    with urllib.request.urlopen(req, timeout=20) as r:   # noqa: S310 (fixed host)
        return json.loads(r.read(1_000_000))


def _x_bill(d: dict, cost: float) -> None:
    """Adds the estimated cost to this month's total and saves (userId, since_id) with it."""
    d["spend"] = {"month": _month(), "usd": round(x_spent(d) + cost, 4)}
    x_save(d)


def x_mentions(cfg: dict, get=_x_get) -> tuple[list[dict], str]:
    """New mentions since the last call ([], "" when nothing new). Error text instead of raising."""
    d = x_load()
    bearer, username = d.get("bearer"), d.get("username")
    if not bearer or not username:
        return [], "x_not_set_up"
    limit = float(cfg.get("xMonthlyUsd", 1.0))
    if x_spent(d) >= limit:
        return [], "x_budget"
    cost = 0.0
    try:
        if not d.get("userId"):
            u = get(bearer, f"users/by/username/{urllib.parse.quote(username)}", {})
            cost += X_COST_USER
            d["userId"] = str((u.get("data") or {}).get("id") or "")
            if not d["userId"]:
                _x_bill(d, cost)
                return [], "x_user"
        params = {"max_results": 5, "tweet.fields": "created_at,author_id", "expansions": "author_id",
                  "user.fields": "username"}
        if d.get("sinceMention"):
            params["since_id"] = d["sinceMention"]
        r = get(bearer, f"users/{d['userId']}/mentions", params)
    except (OSError, ValueError) as e:
        _x_bill(d, cost)
        return [], f"x_error: {str(e)[:80]}"
    posts = r.get("data") or []
    users = {u.get("id"): u.get("username") for u in (r.get("includes") or {}).get("users", [])}
    cost += len(posts) * X_COST_POST + len(users) * X_COST_USER
    if posts:
        d["sinceMention"] = str((r.get("meta") or {}).get("newest_id") or posts[0].get("id"))
    _x_bill(d, cost)
    return [{"kind": "mention", "user": "@" + clean(users.get(p.get("author_id"), "?"), 40),
             "text": clean(p.get("text", ""), 240), "at": p.get("created_at", "")} for p in posts], ""


# ------------------------------------------------------------------ the collector

class Living:
    """One per process (omarchy-link session or the webOS bridge)."""

    def __init__(self, cfg_get=lambda: {}, log=lambda m: None):
        self.cfg_get, self.log = cfg_get, log
        self.prefs = parse_prefs({})
        self.notes: deque = deque(maxlen=12)
        self.x: deque = deque(maxlen=8)
        self.github: list[dict] = []
        self.x_error = ""
        self.active_until = 0.0
        self.last_gh = 0.0
        self.last_x = 0.0
        self.lock = threading.Lock()
        self.monitor = NoteMonitor(self._on_note)
        self.stop = threading.Event()
        self.thread = None

    def set_prefs(self, p: object) -> None:
        new = parse_prefs(p)
        if new != self.prefs:
            self.prefs = new
            self.last_gh = 0.0
            if new["x"] != "api":
                self.x_error = ""
        self.ensure()

    def touch(self, seconds: float = 20) -> None:
        """The TV screensaver is on (bridge: every poll; Android: from its status)."""
        self.active_until = time.time() + seconds
        self.ensure()

    def set_active(self, on: bool) -> None:
        self.active_until = time.time() + 3600 * 24 if on else 0.0
        self.ensure()

    def active(self) -> bool:
        return time.time() < self.active_until

    def wanted(self) -> bool:
        p = self.prefs
        return self.cfg_get().get("livingToTv", True) is not False and (p["notes"] or p["github"] or p["x"] != "off")

    def ensure(self) -> None:
        if self.thread is None and self.wanted():
            self.thread = threading.Thread(target=self._loop, daemon=True)
            self.thread.start()

    def _on_note(self, n: dict) -> None:
        n = dict(n, at=dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))
        with self.lock:
            if self.prefs["x"] == "notify":
                xi = x_from_note(n)
                if xi:
                    self.x.appendleft(dict(xi, at=n["at"]))
            if self.prefs["notes"]:
                self.notes.appendleft(n)

    def _loop(self) -> None:
        while not self.stop.wait(2):
            cfg = self.cfg_get()
            if not self.wanted():
                self.monitor.stop()
                continue
            p = self.prefs
            if p["notes"] or p["x"] == "notify":
                if not self.monitor.start():
                    pass
            else:
                self.monitor.stop()
                with self.lock:
                    self.notes.clear()
            now = time.time()
            if p["github"] and now - self.last_gh > GH_EVERY_S and self.active():
                self.last_gh = now
                try:
                    self.github = github_items(cfg)
                except Exception as e:   # noqa: BLE001 - a network hiccup must not stop the feed
                    self.log(f"living: github: {e}")
            every = max(60, int(cfg.get("xPollMinutes", 3)) * 60)
            if p["x"] == "api" and cfg.get("xApi", True) is not False and self.active() and now - self.last_x > every:
                self.last_x = now
                items, err = x_mentions(cfg)
                self.x_error = err
                with self.lock:
                    for it in reversed(items):
                        self.x.appendleft(it)

    def payload(self) -> dict:
        p = self.prefs
        with self.lock:
            out = {"github": self.github if p["github"] else [], "notes": list(self.notes) if p["notes"] else [],
                   "x": list(self.x) if p["x"] != "off" else [], "xMode": p["x"]}
        if self.x_error and p["x"] == "api":
            out["xError"] = self.x_error
        return out
