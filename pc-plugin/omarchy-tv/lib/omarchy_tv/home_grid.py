"""v2.22 (#157 / #158 / #159) Home widgets and widget pages for LG webOS and Samsung Tizen.

On Android TV the TV keeps them (HomeWidgets / HomePages in the launcher). An LG or Samsung app cannot listen on a
port, so the PC bridge (omarchy-tv webos-serve) keeps them here and the TV app reads them with its ask code
(GET /v1/home). The rules, limits and answers are a port of the Android model: an agent's tv_widget_* / tv_page_*
calls give the same results whichever TV you have.

Pure functions on a dict {"widgets": [...], "widgetPages": [...], "widgetPageCurrent", "widgetPageShowAt"}, plus a
small file store. Everything is text: the TV never renders a value as HTML.
"""

from __future__ import annotations

import json
import os
import re
import threading
import time
import unicodedata
import urllib.parse

COLS, ROWS = 12, 6
MAX_PER_PAGE, MAX_TOTAL, MAX_DATA, MAX_PAGE = 64, 2000, 16 * 1024, 1000
EMPTY_TTL_S = 24 * 3600
ID = re.compile(r"^[a-z0-9][a-z0-9._-]{0,47}$")
PAGE_ID = re.compile(r"^[a-z0-9][a-z0-9-]{0,39}$")
ENTITY = re.compile(r"^[a-z_]{1,32}\.[a-z0-9_]{1,64}$")
PKG = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$")
LAN_URL = re.compile(r"^http://(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.|100\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\.)"
                     r"[0-9.]+(:[0-9]{1,5})?(/[^\s]*)?$")
TYPES = ("lines", "stat", "text", "image", "chart", "page", "shortcuts",
         "homeassistant.scenes", "homeassistant.entities", "radio", "qr")
LINE_TONES = ("up", "down")


class Bad(Exception):
    """An API error: code, message, HTTP status (400 unless said), free slots for a 409."""

    def __init__(self, code: str, message: str, status: int = 400, free=None):
        super().__init__(message)
        self.code, self.status, self.free = code, status, free

    def answer(self) -> dict:
        out = {"error": self.code, "message": str(self)}
        if self.free is not None:
            out["free"] = self.free
        return out


def printable(s, n: int) -> str:
    """Like LinkServer.printable: no control or format characters (ZWJ kept), at most n code points, trimmed."""
    out = []
    for ch in str(s if s is not None else ""):
        if len(out) >= n:
            break
        cat = unicodedata.category(ch)
        if cat == "Cc" or (cat == "Cf" and ch != "‍"):
            continue
        out.append(ch)
    return "".join(out).strip()


def lan_url(url) -> bool:
    return isinstance(url, str) and len(url) <= 2000 and bool(LAN_URL.match(url))


def web_url(url) -> bool:
    if not isinstance(url, str) or len(url) > 512:
        return False
    if url.startswith("https://"):
        return len(url) > 8 and " " not in url
    return lan_url(url)


def _int(v, default=0) -> int:
    if isinstance(v, bool):
        return int(v)
    if isinstance(v, (int, float)):
        return int(v)
    if isinstance(v, str) and re.fullmatch(r"-?\d{1,9}", v.strip()):
        return int(v.strip())
    return default


def _multiline(s: str, max_chars: int, max_lines: int) -> str:
    lines = str(s).replace("\r\n", "\n").split("\n")
    out = ""
    for i, ln in enumerate(lines[:max_lines]):
        if len(out) >= max_chars:
            break
        if i:
            out += "\n"
        out += printable(ln, max_chars - len(out))
    return out.strip()


def clean_action(a):
    if not isinstance(a, dict):
        return None
    t, pkg = a.get("type", ""), a.get("package", "") or ""
    if pkg and not PKG.match(str(pkg)):
        return None
    if t == "app":
        return {"type": "app", "package": pkg} if pkg else None
    if t == "view":
        uri = str(a.get("uri", ""))
        low = uri.lower()
        if len(uri) > 2048 or not (low.startswith("https://") or low.startswith("vnd.youtube:") or low.startswith("nflx://")):
            return None
        if any(ord(c) <= 32 for c in uri):
            return None
        o = {"type": "view", "uri": uri}
        if pkg:
            o["package"] = pkg
        return o
    if t == "pc":
        cmd = str(a.get("command", ""))
        return {"type": "pc", "command": cmd} if ID.match(cmd) else None
    return None


def default_size(t: str):
    if t in ("stat", "radio"):
        return 2, 1
    if t in ("chart", "homeassistant.entities"):
        return 4, 2
    if t in ("image", "page", "homeassistant.scenes"):
        return 4, 3
    return 3, 2


def clean_data(t: str, data) -> dict:
    d = data if isinstance(data, dict) else {}
    if len(json.dumps(d).encode()) > MAX_DATA:
        raise Bad("too_big", "data is over 16 KB")
    if t == "lines":
        ls = d.get("lines") if isinstance(d.get("lines"), list) else []
        if len(ls) > 8:
            raise Bad("too_many", "8 lines")
        out = []
        for ln in ls:
            if not isinstance(ln, dict):
                continue
            o = {"label": printable(ln.get("label", ""), 16), "value": printable(ln.get("value", ""), 64)}
            if ln.get("tone") in LINE_TONES:
                o["tone"] = ln["tone"]
            out.append(o)
        return {"lines": out}
    if t == "stat":
        o = {"value": printable(d.get("value", ""), 16), "unit": printable(d.get("unit", ""), 8),
             "label": printable(d.get("label", ""), 32)}
        delta = printable(d.get("delta", ""), 16)
        if delta:
            o["delta"] = delta
        if d.get("trend") in ("up", "down", "flat"):
            o["trend"] = d["trend"]
        return o
    if t == "text":
        return {"text": _multiline(d.get("text", "") if isinstance(d.get("text", ""), str) else "", 1024, 30)}
    if t == "image":
        url = str(d.get("url", "")).strip()
        if not web_url(url):
            raise Bad("bad_url", "https://, or http:// to a LAN or tailnet IP address")
        return {"url": url, "fit": "contain" if d.get("fit") == "contain" else "cover"}
    if t == "chart":
        ps = d.get("points")
        if not isinstance(ps, list) or not ps:
            raise Bad("bad_data", "points: 1 to 120 numbers")
        if len(ps) > 120:
            raise Bad("too_many", "120 points")
        pts = []
        for p in ps:
            if isinstance(p, bool) or not isinstance(p, (int, float)) or p != p or p in (float("inf"), float("-inf")):
                raise Bad("bad_data", "points must be numbers")
            pts.append(float(p))
        return {"points": pts, "kind": "bars" if d.get("kind") == "bars" else "spark",
                "label": printable(d.get("label", ""), 32), "unit": printable(d.get("unit", ""), 8)}
    if t == "page":
        url = str(d.get("url", "")).strip()
        if not lan_url(url):
            raise Bad("not_lan_url", "http:// to a LAN or tailnet IP address")
        return {"url": url}
    if t == "shortcuts":
        items = d.get("items")
        if not isinstance(items, list) or not items:
            raise Bad("bad_data", "items: 1 to 16 shortcuts")
        if len(items) > 16:
            raise Bad("too_many", "16 shortcuts")
        out = []
        for s in items:
            if not isinstance(s, dict) or not ID.match(str(s.get("id", ""))):
                raise Bad("bad_data", "shortcut id")
            act = clean_action(s.get("action"))
            if act is None:
                raise Bad("bad_data", f"shortcut {s.get('id')}: action")
            out.append({"id": s["id"], "label": printable(s.get("label", s["id"]), 16),
                        "icon": printable(s.get("icon", ""), 16), "action": act})
        return {"items": out}
    if t in ("homeassistant.scenes", "homeassistant.entities"):
        o = {}
        raw = d.get("items")
        if raw == "all" and t == "homeassistant.scenes":
            o["items"] = "all"
        else:
            if not isinstance(raw, list) or not raw:
                raise Bad("bad_data", "items: entity ids")
            if len(raw) > 48:
                raise Bad("too_many", "48 items")
            out = []
            for it in raw:
                e = it if isinstance(it, dict) else {"entity": str(it)}
                ent = str(e.get("entity", ""))
                if not ENTITY.match(ent):
                    raise Bad("bad_data", "entity id like scene.movie")
                c = {"entity": ent}
                label, icon = printable(e.get("label", ""), 24), printable(e.get("icon", ""), 4)
                if label:
                    c["label"] = label
                if icon:
                    c["icon"] = icon
                if e.get("confirm") is True:
                    c["confirm"] = True
                out.append(c)
            o["items"] = out
        o["columns"] = max(1, min(6, _int(d.get("columns", 3), 3)))
        return o
    if t == "radio":
        return {}
    if t == "qr":
        if d.get("preset") == "skill":
            return {"preset": "skill"}
        url = str(d.get("url", "")).strip()
        if not web_url(url):
            raise Bad("bad_url", 'preset "skill", or an https:// / LAN http:// url')
        return {"url": url, "label": printable(d.get("label", ""), 32)}
    raise Bad("bad_type", "one of " + ", ".join(TYPES))


# ---------------------------------------------------------------- placement

def overlap(ax, ay, aw, ah, bx, by, bw, bh) -> bool:
    return ax < bx + bw and bx < ax + aw and ay < by + bh and by < ay + ah


def fits(all_, page, x, y, w, h, except_=None) -> bool:
    if x < 0 or y < 0 or w < 1 or h < 1 or x + w > COLS or y + h > ROWS:
        return False
    for o in all_:
        if o.get("page", 1) != page or o.get("id") == except_:
            continue
        if overlap(x, y, w, h, o.get("x", 0), o.get("y", 0), o.get("w", 1), o.get("h", 1)):
            return False
    return True


def first_free(all_, page, w, h, except_=None):
    for y in range(0, ROWS - h + 1):
        for x in range(0, COLS - w + 1):
            if fits(all_, page, x, y, w, h, except_):
                return x, y
    return None


def free_slots(all_, page, w, h, except_=None):
    out = []
    for y in range(0, ROWS - h + 1):
        for x in range(0, COLS - w + 1):
            if len(out) >= 8:
                return out
            if fits(all_, page, x, y, w, h, except_):
                out.append({"x": x, "y": y})
    return out


def count_on(all_, page) -> int:
    return sum(1 for o in all_ if o.get("page", 1) == page)


def index_of(all_, wid) -> int:
    for i, o in enumerate(all_):
        if o.get("id") == wid:
            return i
    return -1


def push_aside(all_, moving_id, page, x, y, w, h):
    hit = [o for o in all_ if o.get("page", 1) == page and o.get("id") != moving_id
           and overlap(x, y, w, h, o.get("x", 0), o.get("y", 0), o.get("w", 1), o.get("h", 1))]
    hold = {"id": "\0hold", "page": page, "x": x, "y": y, "w": w, "h": h}
    all_.append(hold)
    moved = []
    try:
        for o in hit:
            ow, oh = o.get("w", 1), o.get("h", 1)
            o["page"] = -1
            p, slot = page, None
            while slot is None and p <= MAX_PAGE:
                if count_on(all_, p) < MAX_PER_PAGE:
                    slot = first_free(all_, p, ow, oh, o.get("id"))
                if slot is None:
                    p += 1
            if slot is None:
                raise Bad("cell_taken", "no room to push the other widgets", 409)
            o["page"], o["x"], o["y"] = p, slot[0], slot[1]
            moved.append(o["id"])
    finally:
        all_.remove(hold)
    return moved


def _place(all_, wid, page, x, y, w, h, push):
    if x < 0 or y < 0 or x + w > COLS or y + h > ROWS:
        raise Bad("cell_taken", f"outside the {COLS}×{ROWS} grid", 409, free_slots(all_, page, w, h, wid))
    if fits(all_, page, x, y, w, h, wid):
        return
    if not push:
        raise Bad("cell_taken", "another widget is there; pass push:true or use a free slot", 409, free_slots(all_, page, w, h, wid))
    snapshot = json.loads(json.dumps(all_))
    try:
        push_aside(all_, wid, page, x, y, w, h)
    except Bad:
        all_[:] = snapshot
        raise Bad("cell_taken", "no room to push the other widgets", 409, free_slots(all_, page, w, h, wid))


def _ttl(v) -> int:
    return max(10, min(86_400, _int(v, 180)))


def create(all_, body: dict, owner: str, now: int) -> dict:
    wid = body.get("id", "")
    if not isinstance(wid, str) or not ID.match(wid):
        raise Bad("bad_id", "a-z, 0-9, . _ -, up to 48")
    if index_of(all_, wid) >= 0:
        raise Bad("exists", "a widget with this id exists; use tv_widget_update or tv_widget_move", 409)
    if len(all_) >= MAX_TOTAL:
        raise Bad("too_many", f"{MAX_TOTAL} widgets", 409)
    t = body.get("type", "")
    if t not in TYPES:
        raise Bad("bad_type", "one of " + ", ".join(TYPES))
    page = _int(body.get("page"), 0) if "page" in body else 1
    if page < 1 or page > MAX_PAGE:
        raise Bad("bad_page", f"1 to {MAX_PAGE}")
    if count_on(all_, page) >= MAX_PER_PAGE:
        raise Bad("page_full", f"{MAX_PER_PAGE} widgets per page", 409)
    dw, dh = default_size(t)
    w = _int(body.get("w"), 0) if "w" in body else dw
    h = _int(body.get("h"), 0) if "h" in body else dh
    if w < 1 or w > COLS or h < 1 or h > ROWS:
        raise Bad("bad_size", f"w 1-{COLS}, h 1-{ROWS}")
    data = clean_data(t, body.get("data"))
    if "x" in body or "y" in body:
        x, y = _int(body.get("x"), 0), _int(body.get("y"), 0)
        _place(all_, wid, page, x, y, w, h, body.get("push") is True)
    else:
        slot = first_free(all_, page, w, h)
        if slot is None:
            raise Bad("cell_taken", f"no free {w}×{h} slot on page {page}", 409, free_slots(all_, page, 1, 1))
        x, y = slot
    wd = {"id": wid, "type": t, "page": page, "x": x, "y": y, "w": w, "h": h,
          "title": printable(body.get("title", ""), 48), "icon": printable(body.get("icon", ""), 16),
          "data": data, "updatedAt": now, "owner": owner}
    if "ttl" in body:
        wd["ttl"] = _ttl(body.get("ttl"))
    all_.append(wd)
    return wd


def update(all_, wid, body: dict, now: int) -> dict:
    i = index_of(all_, wid)
    if i < 0:
        raise Bad("no_such_widget", wid, 404)
    wd = all_[i]
    if "title" in body:
        wd["title"] = printable(body.get("title", ""), 48)
    if "icon" in body:
        wd["icon"] = printable(body.get("icon", ""), 16)
    if "ttl" in body:
        wd["ttl"] = _ttl(body.get("ttl"))
    if "data" in body:
        wd["data"] = clean_data(wd.get("type", ""), body.get("data"))
    wd["updatedAt"] = now
    return wd


def move(all_, wid, body: dict, now: int) -> dict:
    i = index_of(all_, wid)
    if i < 0:
        raise Bad("no_such_widget", wid, 404)
    wd = all_[i]
    page = _int(body.get("page"), 0) if "page" in body else wd.get("page", 1)
    w = _int(body.get("w"), 0) if "w" in body else wd.get("w", 1)
    h = _int(body.get("h"), 0) if "h" in body else wd.get("h", 1)
    if page < 1 or page > MAX_PAGE:
        raise Bad("bad_page", f"1 to {MAX_PAGE}")
    if w < 1 or w > COLS or h < 1 or h > ROWS:
        raise Bad("bad_size", f"w 1-{COLS}, h 1-{ROWS}")
    if page != wd.get("page", 1) and count_on(all_, page) >= MAX_PER_PAGE:
        raise Bad("page_full", f"{MAX_PER_PAGE} widgets per page", 409)
    if "x" in body or "y" in body:
        x = _int(body.get("x"), 0) if "x" in body else wd.get("x", 0)
        y = _int(body.get("y"), 0) if "y" in body else wd.get("y", 0)
    elif page != wd.get("page", 1):
        slot = first_free(all_, page, w, h, wid)
        if slot is None:
            raise Bad("cell_taken", f"no free {w}×{h} slot on page {page}", 409, free_slots(all_, page, 1, 1, wid))
        x, y = slot
    else:
        x, y = wd.get("x", 0), wd.get("y", 0)
    _place(all_, wid, page, x, y, w, h, body.get("push") is True)
    wd.update({"page": page, "x": x, "y": y, "w": w, "h": h, "updatedAt": now})
    return wd


def listing(all_, page: int):
    out = [o for o in all_ if page == 0 or o.get("page", 1) == page]
    out.sort(key=lambda o: (o.get("page", 1), o.get("y", 0), o.get("x", 0)))
    return out


def types() -> dict:
    t = {
        "lines": "{lines: [{label ≤16, value ≤64, tone?: up|down}] ≤8}",
        "stat": "{value ≤16, unit? ≤8, label? ≤32, delta? ≤16, trend?: up|down|flat}",
        "text": "{text: plain text ≤1024 chars, ≤30 lines}",
        "image": "{url: https:// or LAN http://, PNG/JPEG ≤512 KB, fit?: cover|contain}",
        "chart": "{points: [numbers] 1-120, kind?: spark|bars, label?, unit?}",
        "page": "{url: http:// to a LAN or tailnet IP address}",
        "shortcuts": "{items: [{id, label, icon?, action: {type: app|view|pc, …}}] ≤16}",
        "homeassistant.scenes": '{items: "all" | [scene.x | script.x | {entity, label?, icon?, confirm?}] ≤48, columns? 1-6}',
        "homeassistant.entities": "{items: [entity ids or {entity, label?}] ≤48, columns? 1-6}",
        "radio": "{} (the radio mini player)",
        "qr": '{preset: "skill"} or {url: https:// / LAN http://, label?}',
    }
    return {"grid": {"cols": COLS, "rows": ROWS}, "types": {k: {"data": v} for k, v in t.items()},
            "limits": {"perPage": MAX_PER_PAGE, "total": MAX_TOTAL, "dataBytes": MAX_DATA, "pages": MAX_PAGE},
            "tv": "webos-or-tizen"}


# ---------------------------------------------------------------- pages (#158 / #159)

def _pages(cfg) -> list:
    p = cfg.get("widgetPages")
    if not isinstance(p, list):
        p = cfg["widgetPages"] = []
    return p


def total(cfg) -> int:
    n = 1
    for o in cfg.get("widgets") or []:
        n = max(n, o.get("page", 1))
    for o in cfg.get("widgetPages") or []:
        n = max(n, o.get("index", 1))
    return min(n, MAX_PAGE)


def meta(cfg, index):
    for o in cfg.get("widgetPages") or []:
        if o.get("index") == index:
            return o
    return None


def _meta_or_new(cfg, index, now):
    m = meta(cfg, index)
    if m is None:
        m = {"index": index, "touched": now}
        _pages(cfg).append(m)
    return m


def name(cfg, index) -> str:
    m = meta(cfg, index)
    n = m.get("name", "") if m else ""
    return n or f"Page {index}"


def resolve(cfg, ref) -> int:
    if isinstance(ref, bool):
        return -1
    if isinstance(ref, (int, float)):
        return int(ref)
    s = "" if ref is None else str(ref).strip()
    if re.fullmatch(r"\d{1,4}", s):
        return int(s)
    for o in cfg.get("widgetPages") or []:
        if s and (s == o.get("id") or s.lower() == str(o.get("name", "")).lower()):
            return o.get("index", -1)
    return -1


def on_demand(cfg, ref, strict: bool, now: int) -> int:
    tot = total(cfg)
    idx = resolve(cfg, ref)
    if idx < 0:
        pid = str(ref).strip()
        if not PAGE_ID.match(pid):
            raise Bad("bad_page", "a page number or id (a-z, 0-9, dashes)")
        if strict:
            raise Bad("no_such_page", pid, 404)
        if tot >= MAX_PAGE:
            raise Bad("too_many_pages", f"{MAX_PAGE} pages", 409)
        idx = tot + 1
        m = _meta_or_new(cfg, idx, now)
        m["id"], m["name"] = pid, pid
        return idx
    if idx < 1:
        raise Bad("bad_page", f"1 to {MAX_PAGE}")
    if idx > tot:
        if strict:
            raise Bad("no_such_page", str(idx), 404)
        idx = min(tot + 1, MAX_PAGE)
    return idx


def count(cfg, index) -> int:
    return count_on(cfg.get("widgets") or [], index)


def page_list(cfg, now: int):
    cleanup(cfg, now)
    out = []
    for i in range(1, total(cfg) + 1):
        m = meta(cfg, i)
        out.append({"index": i, "id": (m.get("id", "") if m else ("home" if i == 1 else "")), "name": name(cfg, i),
                    "widgets": count(cfg, i), "hidden": bool(m and m.get("hidden")), "rotate": bool(m and m.get("rotate"))})
    return out


def _shift(cfg, frm, to, d):
    for o in cfg.get("widgets") or []:
        if frm <= o.get("page", 1) <= to:
            o["page"] = o.get("page", 1) + d
    for o in cfg.get("widgetPages") or []:
        if frm <= o.get("index", 0) <= to:
            o["index"] = o["index"] + d


def _swap(cfg, a, b):
    for o in cfg.get("widgets") or []:
        if o.get("page", 1) == a:
            o["page"] = b
        elif o.get("page", 1) == b:
            o["page"] = a
    for o in cfg.get("widgetPages") or []:
        if o.get("index") == a:
            o["index"] = b
        elif o.get("index") == b:
            o["index"] = a


def page_create(cfg, body: dict, now: int) -> int:
    tot = total(cfg)
    if tot >= MAX_PAGE:
        raise Bad("too_many_pages", f"{MAX_PAGE} pages", 409)
    pid = str(body.get("id", "") or "").strip()
    nm = printable(body.get("name", ""), 32)
    if pid and not PAGE_ID.match(pid):
        raise Bad("bad_id", "a-z, 0-9, dashes, up to 40")
    if pid and resolve(cfg, pid) > 0:
        raise Bad("exists", "a page with this id exists", 409)
    at = tot + 1
    if "after" in body:
        after = resolve(cfg, body.get("after"))
        if after < 1 or after > tot:
            raise Bad("no_such_page", str(body.get("after")), 404)
        at = after + 1
        _shift(cfg, at, tot, 1)
    m = _meta_or_new(cfg, at, now)
    if pid:
        m["id"] = pid
    if nm:
        m["name"] = nm
    m["touched"] = now
    return at


def _existing(cfg, ref) -> int:
    idx = resolve(cfg, ref)
    if idx < 1 or idx > total(cfg):
        raise Bad("no_such_page", str(ref), 404)
    return idx


def page_rename(cfg, ref, nm, now: int):
    idx = _existing(cfg, ref)
    n = printable(nm or "", 32)
    if not n:
        raise Bad("bad_name", "1 to 32 characters")
    m = _meta_or_new(cfg, idx, now)
    m["name"], m["touched"] = n, now


def page_reorder(cfg, ref, to: int, now: int) -> int:
    idx, tot = _existing(cfg, ref), total(cfg)
    if idx == 1 or to == 1:
        raise Bad("home_page", "page 1 is the Home and stays first")
    if to < 2 or to > tot:
        raise Bad("bad_page", f"to: 2 to {tot}")
    while idx < to:
        _swap(cfg, idx, idx + 1)
        idx += 1
    while idx > to:
        _swap(cfg, idx, idx - 1)
        idx -= 1
    return to


def page_delete(cfg, ref, move_to, now: int) -> int:
    idx, tot = _existing(cfg, ref), total(cfg)
    if idx == 1:
        raise Bad("home_page", "page 1 is the Home and cannot be removed")
    w = cfg.setdefault("widgets", [])
    dest = -1 if move_to is None else _existing(cfg, move_to)
    if dest == idx:
        raise Bad("bad_page", "moveTo is the page being removed")
    moved = 0
    for o in list(reversed(w)):
        if o.get("page", 1) != idx:
            continue
        if dest > 0:
            o["page"] = -1
            slot = first_free(w, dest, o.get("w", 1), o.get("h", 1), o.get("id"))
            if slot is None or count_on(w, dest) >= MAX_PER_PAGE:
                o["page"] = idx
                raise Bad("cell_taken", f"no room on page {dest} for {o.get('id')}", 409)
            o["page"], o["x"], o["y"] = dest, slot[0], slot[1]
            moved += 1
        else:
            w.remove(o)
    cfg["widgetPages"] = [o for o in cfg.get("widgetPages") or [] if o.get("index") != idx]
    _shift(cfg, idx + 1, tot, -1)
    return moved


def cleanup(cfg, now: int) -> int:
    removed = 0
    for i in range(total(cfg), 1, -1):
        if count(cfg, i) > 0:
            continue
        m = meta(cfg, i)
        if now - (m.get("touched", 0) if m else 0) < EMPTY_TTL_S:
            continue
        page_delete(cfg, i, None, now)
        removed += 1
    return removed


def touch(cfg, index, now: int):
    m = meta(cfg, index)
    if m is not None:
        m["touched"] = now


def visible(cfg):
    out = []
    for i in range(1, total(cfg) + 1):
        m = meta(cfg, i)
        if i == 1 or not (m and m.get("hidden")):
            out.append(i)
    return out


def has_pages(cfg) -> bool:
    return bool(cfg.get("widgets"))


# ---------------------------------------------------------------- the store and the routes

def store_path() -> str:
    base = os.environ.get("XDG_CONFIG_HOME") or os.path.join(os.path.expanduser("~"), ".config")
    return os.path.join(base, "omarchy-tv", "home-widgets.json")


class Store:
    """The bridge's widgets and pages, in one JSON file (0600), written atomically; one change at a time."""

    def __init__(self, path: str | None = None):
        self.path = path or store_path()
        self.lock = threading.Lock()

    def load(self) -> dict:
        try:
            with open(self.path, encoding="utf-8") as f:
                d = json.load(f)
            return d if isinstance(d, dict) else {}
        except (OSError, ValueError):
            return {}

    def save(self, cfg: dict):
        os.makedirs(os.path.dirname(self.path), exist_ok=True)
        tmp = self.path + ".tmp"
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(cfg, f, ensure_ascii=False, separators=(",", ":"))
        os.replace(tmp, self.path)

    def edit(self, op):
        """Runs op(cfg) on a copy; saves only when it returns without an exception."""
        with self.lock:
            cfg = self.load()
            out = op(cfg)
            self.save(cfg)
            return out


WIDGET_ITEM = re.compile(r"^/v1/widgets/item/([^/?#]{1,64})$")
WPAGE = re.compile(r"^/v1/widget-pages/([^/?#]{1,40})(/show)?$")


def handles(path: str) -> bool:
    return path.startswith("/v1/widgets/") or path == "/v1/widget-pages" or path.startswith("/v1/widget-pages/")


def _query(target: str, key: str):
    q = urllib.parse.parse_qs(urllib.parse.urlsplit(target).query)
    v = q.get(key)
    return v[0] if v else None


def route(store: Store, method: str, target: str, body: dict, owner: str = "pc", now: int | None = None,
          shown: int = 0) -> tuple[int, dict]:
    """The agents' widget and page API, as on Android TV (an agent on the bridge has the control scope)."""
    now = int(time.time()) if now is None else now
    path = urllib.parse.urlsplit(target).path
    try:
        if path.startswith("/v1/widgets/"):
            return _widget_route(store, method, path, target, body, owner, now, shown)
        return _page_route(store, method, path, target, body, now, shown)
    except Bad as e:
        return e.status, e.answer()


def _ok(**kw) -> dict:
    return {"ok": True, **kw}


def _widget_route(store, m, p, target, body, owner, now, shown):
    if m == "GET" and p == "/v1/widgets/types":
        return 200, types()
    if m == "GET" and p == "/v1/widgets/list":
        q = _query(target, "page")
        page = 0
        if q:
            if not re.fullmatch(r"\d{1,4}", q):
                raise Bad("bad_page", "page: a number")
            page = int(q)
        all_ = store.load().get("widgets") or []
        out = {"widgets": listing(all_, page), "grid": {"cols": COLS, "rows": ROWS}}
        if page > 0:
            out["page"], out["free"] = page, free_slots(all_, page, 1, 1)
        return 200, out
    if m == "POST" and p == "/v1/widgets/item":
        def op(cfg):
            all_ = cfg.setdefault("widgets", [])
            b = dict(body)
            if "page" in b:
                b["page"] = on_demand(cfg, b["page"], b.get("strict") is True, now)
            wd = create(all_, b, owner, now)
            touch(cfg, wd["page"], now)
            return wd
        return 200, _ok(created=True, widget=store.edit(op))
    im = WIDGET_ITEM.match(p)
    if not im:
        return 404, {"error": "not_found"}
    wid = urllib.parse.unquote(im.group(1))
    if m == "DELETE":
        def op(cfg):
            all_ = cfg.setdefault("widgets", [])
            i = index_of(all_, wid)
            if i < 0:
                raise Bad("no_such_widget", wid, 404)
            return all_.pop(i)
        return 200, _ok(deleted=True, widget=store.edit(op))
    if m in ("PATCH", "PUT"):
        if "type" in body:
            raise Bad("bad_type", "the type cannot change: delete and create")
        shape = any(k in body for k in ("page", "x", "y", "w", "h"))
        content = any(k in body for k in ("title", "icon", "ttl", "data"))

        def op(cfg):
            all_ = cfg.setdefault("widgets", [])
            b = dict(body)
            if "page" in b:
                b["page"] = on_demand(cfg, b["page"], b.get("strict") is True, now)
            wd = None
            if shape:
                wd = move(all_, wid, b, now)
            if content or not shape:
                wd = update(all_, wid, b, now)
            touch(cfg, wd.get("page", 1), now)
            return wd
        wd = store.edit(op)
        return 200, _ok(**{("moved" if shape else "updated"): True, "widget": wd})
    return 405, {"error": "bad_method"}


def _current(cfg, shown: int) -> dict:
    m = meta(cfg, shown) if shown else None
    return {"index": shown, "name": "Home" if shown == 0 else name(cfg, shown),
            "id": "home" if shown == 0 else (m.get("id", "") if m else ""), "total": total(cfg), "classicHome": shown == 0}


def _page_route(store, m, p, target, body, now, shown):
    if m == "GET" and p in ("/v1/widget-pages", "/v1/widget-pages/current"):
        cfg = store.load()
        cur = _current(cfg, shown)
        if p.endswith("/current"):
            return 200, cur
        return 200, {"pages": page_list(json.loads(json.dumps(cfg)), now), "total": total(cfg), "current": cur}
    if m == "POST" and p == "/v1/widget-pages":
        at = store.edit(lambda cfg: page_create(cfg, body, now))
        return 200, _ok(created=True, index=at, name=name(store.load(), at))
    pm = WPAGE.match(p)
    if not pm:
        return 404, {"error": "not_found"}
    ref = urllib.parse.unquote(pm.group(1))
    r0 = int(ref) if re.fullmatch(r"\d{1,4}", ref) else ref
    if pm.group(2):
        if m != "POST":
            return 405, {"error": "bad_method"}

        def show(cfg):
            idx = resolve(cfg, r0)
            if idx < 1 or idx > total(cfg):
                raise Bad("no_such_page", ref, 404)
            cfg["widgetPageCurrent"], cfg["widgetPageShowAt"] = idx, int(time.time() * 1000)
            return idx
        at = store.edit(show)
        return 200, _ok(shown=True, index=at, name=name(store.load(), at))
    if m in ("PATCH", "PUT"):
        def op(cfg):
            idx = resolve(cfg, r0)
            if "name" in body:
                page_rename(cfg, idx if idx > 0 else r0, body.get("name"), now)
            if "to" in body:
                idx = page_reorder(cfg, idx if idx > 0 else r0, _int(body.get("to"), 0), now)
            if "rotate" in body or "hidden" in body:
                if idx < 1 or idx > total(cfg):
                    raise Bad("no_such_page", ref, 404)
                page_rename(cfg, idx, name(cfg, idx), now)
                mt = meta(cfg, idx)
                if "rotate" in body:
                    mt["rotate"] = body.get("rotate") is True
                if "hidden" in body:
                    mt["hidden"] = idx != 1 and body.get("hidden") is True
            return idx
        at = store.edit(op)
        return 200, _ok(updated=True, index=at, name=name(store.load(), at))
    if m == "DELETE":
        move_to = body.get("moveWidgetsTo") if "moveWidgetsTo" in body else _query(target, "moveTo")
        if isinstance(move_to, str) and re.fullmatch(r"\d{1,4}", move_to):
            move_to = int(move_to)
        moved = store.edit(lambda cfg: page_delete(cfg, r0, move_to, now))
        return 200, _ok(deleted=True, movedWidgets=moved)
    return 405, {"error": "bad_method"}


def tv_view(store: Store, now: int | None = None) -> dict:
    """GET /v1/home for the TV app: every widget, the pages (names, hidden, rotate) and the agent's last "show"."""
    now = int(time.time()) if now is None else now
    cfg = store.load()
    pages = page_list(json.loads(json.dumps(cfg)), now)
    return {"grid": {"cols": COLS, "rows": ROWS}, "widgets": listing(cfg.get("widgets") or [], 0), "pages": pages,
            "visible": visible(cfg), "show": {"index": cfg.get("widgetPageCurrent", 0), "at": cfg.get("widgetPageShowAt", 0)},
            "now": now}
