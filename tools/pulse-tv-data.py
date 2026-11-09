#!/usr/bin/env python3
"""OmarchyTV 2.15: build pulse-tv-data.js (stars, constellations, meteor showers, planet elements, skyline keys) from
Omarchy Pulse's own hub, so the TV's sky is the PC's sky. Usage: tools/pulse-tv-data.py path/to/omarchy-pulse
Writes webos/app/vendor/pulse/pulse-tv-data.js and launcher/app/src/main/assets/pulse/pulse-tv-data.js."""
import ast
import json
import re
import sys
from pathlib import Path

src = Path(sys.argv[1]).read_text()


def grab(name):
    m = re.search(r"^" + name + r"\s*=\s*", src, re.M)
    i = j = m.end()
    depth, started = 0, False
    while True:
        ch = src[j]
        if ch in "([{":
            depth, started = depth + 1, True
        elif ch in ")]}":
            depth -= 1
        elif ch == "#" and depth > 0:
            j = src.index("\n", j)
            continue
        j += 1
        if started and depth == 0:
            break
    txt = src[i:j]
    return ast.literal_eval(txt[4:-1] if txt.startswith("set(") else txt)


data = {"STARS": grab("STARS"), "CONSTELLATIONS": grab("CONSTELLATIONS"), "SHOWERS": grab("SHOWERS"), "PLANETS": grab("PLANETS"),
        "CITY_KEYS": sorted(grab("CITY_KEYS")), "CITY_ALIASES": grab("CITY_ALIASES")}
out = ("// Generated from omarchy-pulse 1.6 (MIT, Marco Porro): the same stars, constellations, meteor showers, planet\n"
       "// elements and skyline keys as the PC's hub. Do not edit by hand: tools/pulse-tv-data.py.\n(function (root) {\n"
       "  root.OmarchyPulseData = " + json.dumps(data, ensure_ascii=False) + ";\n})(typeof window !== \"undefined\" ? window : this);\n")
root = Path(__file__).resolve().parent.parent
for p in ("webos/app/vendor/pulse/pulse-tv-data.js", "launcher/app/src/main/assets/pulse/pulse-tv-data.js"):
    (root / p).write_text(out)
    print("wrote", p)
