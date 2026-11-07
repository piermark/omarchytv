#!/usr/bin/env python3
"""
Build launcher/app/src/main/assets/racing_2026.json, the bundled F1 + MotoGP 2026 session calendar the
Android launcher's "racing" home widget (Racing.java) falls back to when the TV cannot reach the sources.

Sources (both official, no key):
  F1     https://www.formula1.com/en/racing/2026/<slug>   (each race page embeds its session times)
  MotoGP https://api.motogp.pulselive.com/motogp/v1/events?seasonYear=2026   (the API behind motogp.com)

Every time is stored as UTC ("...Z"); the TV converts to the viewer's zone (Asia/Bangkok by default).
Re-run when a calendar changes, then rebuild the APK:  python3 tools/racing_calendar.py
"""
import datetime as dt
import json
import os
import re
import sys
import urllib.request

YEAR = 2026
UA = "Mozilla/5.0 (X11; Linux x86_64) OmarchyTV-racing-calendar"
OUT = os.path.join(os.path.dirname(__file__), "..", "launcher", "app", "src", "main", "assets", "racing_%d.json" % YEAR)

# MotoGP class sessions shown on the TV (warm-up left out); names as the TV prints them.
MGP_CODES = {"FP1": "FP1", "PR": "PR", "FP2": "FP2", "Q1": "Q1", "Q2": "Q2", "SPR": "SPR", "RAC": "RACE"}


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode("utf-8")


def utc(local, offset):
    """'2026-11-19T16:30:00' + '-08:00' -> '2026-11-20T00:30:00Z'."""
    t = dt.datetime.fromisoformat(local + offset)
    return t.astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def iso_utc(s):
    """MotoGP '2026-11-27T09:00:00+0100' (or +01:00) -> UTC 'Z' string."""
    s = re.sub(r"([+-]\d\d)(\d\d)$", r"\1:\2", s)
    return dt.datetime.fromisoformat(s).astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def f1_code(s):
    st = (s.get("sessionType") or "").lower()
    short = (s.get("shortName") or "").upper()
    desc = (s.get("description") or "").lower()
    if "sprint" in desc and "qual" in desc or short in ("SQ", "SS"):
        return "SQ"
    if "sprint" in desc or short == "S":
        return "SPR"
    if st == "practice" or short.startswith("FP"):
        return short or "FP%d" % s.get("sessionNumber", 0)
    if st == "qualifying":
        return "QUALI"
    if st == "race":
        return "RACE"
    return short or desc.upper()


def f1_page(slug):
    """Meeting dict from one formula1.com race page, or None (tests, pages without session times)."""
    t = get("https://www.formula1.com/en/racing/%d/%s" % (YEAR, slug)).replace('\\"', '"')
    sessions, seen = [], set()
    for m in re.finditer(r'\{[^{}]*"startTime":"[^"]+"[^{}]*\}', t):
        try:
            s = json.loads(m.group(0))
        except ValueError:
            continue
        if "sessionType" not in s or "gmtOffset" not in s:
            continue
        key = (s.get("startTime"), s.get("description"))   # the page repeats some sessions (hero + schedule)
        if key in seen or "cancel" in (s.get("state") or "").lower():
            continue
        seen.add(key)
        sessions.append({
            "code": f1_code(s),
            "name": s.get("description") or s.get("sessionType"),
            "start": utc(s["startTime"], s["gmtOffset"]),
            "end": utc(s["endTime"], s["gmtOffset"]) if s.get("endTime") else None,
            "state": s.get("state", ""),
        })
    if not sessions:
        return None
    sessions.sort(key=lambda x: x["start"])
    name = re.search(r'"meetingName":"([^"]*)"', t)
    official = re.search(r'"meetingOfficialName":"([^"]*)"', t)
    return {
        "slug": slug,
        "name": (name.group(1) if name else slug).replace(" Grand Prix", " GP"),
        "official": official.group(1) if official else "",
        "sessions": sessions,
    }


def f1():
    season = get("https://www.formula1.com/en/racing/%d" % YEAR)
    slugs = []
    for s in re.findall(r"/en/racing/%d/([a-z0-9-]+)" % YEAR, season):
        if s not in slugs and "testing" not in s:
            slugs.append(s)
    out = []
    for slug in slugs:
        m = f1_page(slug)
        if m:
            out.append(m)
        else:
            print("f1: no sessions on", slug, file=sys.stderr)
    out.sort(key=lambda m: m["sessions"][0]["start"])
    return out


def motogp_parse(events):
    """Same shape as f1(): GP weekends only, MotoGP class only. Shared with Racing.java's parser (same rules)."""
    out = []
    for e in events:
        if e.get("kind") != "GP" or e.get("test"):
            continue
        sessions = []
        for b in e.get("broadcasts") or []:
            cat = (b.get("category") or {}).get("acronym")
            code = MGP_CODES.get((b.get("shortname") or "").upper())
            if b.get("type") != "SESSION" or cat != "MGP" or not code:
                continue
            if "CANCEL" in (b.get("status") or "").upper() or "cancel" in (b.get("name") or "").lower():
                continue
            start, end = iso_utc(b["date_start"]), iso_utc(b["date_end"]) if b.get("date_end") else None
            sessions.append({"code": code, "name": (b.get("name") or "").strip(), "start": start,
                             "end": end if end and end != start else None, "state": b.get("status", "")})
        if not sessions:
            continue
        sessions.sort(key=lambda x: x["start"])
        name = (e.get("name") or "").strip()
        place = re.sub(r"^.*GRAND PRIX OF (THE )?", "", name).strip().title()
        out.append({"slug": e.get("id"), "name": place + " GP" if place else name.title(),
                    "official": name, "sessions": sessions})
    out.sort(key=lambda m: m["sessions"][0]["start"])
    return out


def motogp():
    return motogp_parse(json.loads(get("https://api.motogp.pulselive.com/motogp/v1/events?seasonYear=%d" % YEAR)))


def main():
    data = {
        "year": YEAR,
        "generated": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "sources": {
            "f1": "https://www.formula1.com/en/racing/%d" % YEAR,
            "motogp": "https://www.motogp.com/en/calendar (api.motogp.pulselive.com)",
        },
        "f1": f1(),
        "motogp": motogp(),
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(data, fh, ensure_ascii=False, indent=1)
        fh.write("\n")
    print("wrote %s: %d F1 meetings, %d MotoGP meetings" % (os.path.normpath(OUT), len(data["f1"]), len(data["motogp"])))


if __name__ == "__main__":
    main()
