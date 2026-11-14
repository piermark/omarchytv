"""GrokBot Office (OmarchyTV 2.20, optional): the bots and the weekly usage the TV shows in its office.

  omarchy-tv office push <bots.json>   check the file, keep it in ~/.config/omarchy-tv/office.json (LG/Samsung read it
                                       from the bridge: GET /v1/office) and send it to a paired Android TV (PUT /v1/office)
  omarchy-tv office show               what the TV gets now
  omarchy-tv office clear              empty the office

The file is yours (or your Grok Bot's): nothing is made up here. The same rules as the TV (webos/app/office.js and
Office.java): at most 24 bots, a name each (24 characters), optional role, colour (#rrggbb or a name), status
(working, idle, done, error) and task; the usage card only when "usage" has a weekly_pct between 0 and 100.

  {"bots": [{"name": "Trimmer", "role": "Cuts token use", "color": "#ff9e64", "status": "working"}],
   "usage": {"weekly_pct": 46.3, "label": "Weekly usage", "resets": "Mon 09:00"}}
"""
import json, os, re
from pathlib import Path

MAX_BOTS = 24
STATUS = ("working", "idle", "done", "error")
NAMED = {"red", "orange", "yellow", "green", "teal", "cyan", "blue", "purple", "violet", "pink", "white", "lime", "grey", "gray"}


class OfficeError(ValueError):
    pass


def path() -> Path:
    base = os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config")
    return Path(base) / "omarchy-tv" / "office.json"


def _clean(s, n: int) -> str:
    s = "" if s is None else str(s)
    s = re.sub(r"[\x00-\x1f\x7f<>]", "", s)
    return re.sub(r"\s+", " ", s).strip()[:n]


def _slug(s: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", str(s or "").lower()).strip("-")[:40]


def _color(c) -> str:
    if isinstance(c, str):
        v = c.strip().lower()
        if re.fullmatch(r"#[0-9a-f]{6}", v) or v in NAMED:
            return v
    return ""


def _pct(u):
    if not isinstance(u, dict):
        return None
    v = next((u[k] for k in ("weekly_pct", "weeklyPct", "pct") if u.get(k) is not None), None)
    if isinstance(v, str) and re.fullmatch(r"\s*\d+(\.\d+)?\s*%?\s*", v):
        v = float(v.strip().rstrip("%"))
    if isinstance(v, bool) or not isinstance(v, (int, float)) or not 0 <= v <= 100:
        return None
    return round(float(v), 1)


def clean(j) -> dict:
    """Untrusted JSON -> what the TV accepts. Unknown fields go; bad bots are dropped; no usage without a number."""
    if not isinstance(j, dict):
        raise OfficeError("the office file must be a JSON object with a \"bots\" list")
    bots, seen = [], set()
    for b in j.get("bots") or []:
        if not isinstance(b, dict) or len(bots) >= MAX_BOTS:
            continue
        name = _clean(b.get("name") or b.get("shortName"), 24)
        if not name:
            continue
        bid = _slug(b.get("id") or name) or _slug(name) or "bot"
        if bid in seen:
            continue
        seen.add(bid)
        st = str(b.get("status") or "").lower()
        out = {"id": bid, "name": name, "status": st if st in STATUS else "idle"}
        for k, n in (("role", 40), ("task", 60)):
            v = _clean(b.get(k), n)
            if v:
                out[k] = v
        col = _color(b.get("color") or b.get("fill"))
        if col:
            out["color"] = col
        bots.append(out)
    res = {"bots": bots}
    p = _pct(j.get("usage"))
    if p is not None:
        u = j["usage"]
        res["usage"] = {"weekly_pct": p}
        for k, n in (("label", 24), ("resets", 32)):
            v = _clean(u.get(k) or (u.get("resets_at") if k == "resets" else None), n)
            if v:
                res["usage"][k] = v
    title = _clean(j.get("title"), 32)
    if title:
        res["title"] = title
    at = _clean(j.get("updated_at"), 32)
    if at:
        res["updated_at"] = at
    return res


def load():
    try:
        return clean(json.loads(path().read_text(encoding="utf-8")))
    except (OSError, ValueError):
        return None


def save(data: dict) -> Path:
    p = path()
    p.parent.mkdir(parents=True, exist_ok=True)
    tmp = p.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    os.replace(tmp, p)
    return p


def payload(roster=None) -> dict:
    """GET /v1/office on the bridge: the pushed file; else the Agent Desktop roster (names and colours only, no usage)."""
    d = load()
    if d is not None:
        return dict(d, source="push")
    if roster:
        return dict(clean({"bots": [{"id": r.get("id"), "name": r.get("shortName") or r.get("name"), "color": r.get("fill")} for r in roster]}), source="roster")
    return {"bots": [], "source": "none"}


def run(args, client=None, say=print) -> int:
    sub = args[0] if args else "show"
    if sub == "push":
        if len(args) < 2:
            raise OfficeError("usage: omarchy-tv office push <bots.json>")
        try:
            raw = json.loads(Path(args[1]).expanduser().read_text(encoding="utf-8"))
        except OSError as e:
            raise OfficeError(f"cannot read {args[1]}: {e.strerror or e}")
        except ValueError as e:
            raise OfficeError(f"{args[1]} is not valid JSON: {e}")
        data = clean(raw)
        p = save(data)
        n = len(data["bots"])
        say(f"󰚩 GrokBot Office: {n} bot{'s' if n != 1 else ''}" + (f" · weekly usage {data['usage']['weekly_pct']}%" if "usage" in data else " · no usage numbers (no usage card)"))
        say(f"  saved for LG/Samsung (the bridge serves it): {p}")
        if client is not None:
            try:
                c = client()
            except SystemExit:
                say("  no Android TV paired: skipped (omarchy-tv pair)")
                return 0
            c.request("PUT", "/v1/office", data)
            say("  sent to the Android TV ✓")
        return 0
    if sub == "show":
        say(json.dumps(payload(), ensure_ascii=False, indent=1))
        return 0
    if sub == "clear":
        try:
            path().unlink()
        except FileNotFoundError:
            pass
        if client is not None:
            try:
                client().request("PUT", "/v1/office", {"bots": []})
            except SystemExit:
                pass
        say("󰚩 GrokBot Office emptied")
        return 0
    raise OfficeError("usage: omarchy-tv office push <bots.json> | show | clear")
