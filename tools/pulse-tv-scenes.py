#!/usr/bin/env python3
"""Pulse's scenes in code on the TV (2.15.1): one self-contained page per scene, the scene and Pulse's scene runtime
inlined unchanged, as the PC's hub serves them at /scene/NAME. A sandboxed frame can't load a script beside it on
LG and Samsung (file://), so nothing is left outside the page.

    python3 tools/pulse-tv-scenes.py /path/to/omarchy-pulse-theme      # at the Pulse version to ship
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = [ROOT / "launcher/app/src/main/assets/pulse", ROOT / "webos/app/vendor/pulse"]
SCENES = ("mars", "monolith")


def page(name: str, runtime: str, scene: str) -> str:
    safe = lambda x: x.replace("</script", "<\\/script")   # noqa: E731
    return ('<!doctype html><html><head><meta charset="utf-8"><!-- Omarchy Pulse: the scene "%s" and the scene runtime, '
            'unchanged (c) 2026 Marco Porro, MIT. Written by tools/pulse-tv-scenes.py. -->\n'
            '<style>html,body{margin:0;height:100%%;background:transparent;overflow:hidden}'
            'canvas{position:fixed;top:0;left:0;width:100%%;height:100%%;display:block}</style></head><body>\n'
            '<canvas id="c"></canvas>\n<script>\n%s</script>\n<script>\n%s</script>\n</body></html>\n') % (name, safe(runtime), safe(scene))


def main() -> None:
    web = pathlib.Path(sys.argv[1]) / "extras/pulse/web"
    runtime = (web / "scene-runtime.js").read_text(encoding="utf-8")
    for n in SCENES:
        html = page(n, runtime, (web / "scenes" / (n + ".js")).read_text(encoding="utf-8"))
        for d in OUT:
            (d / ("scene-%s.html" % n)).write_text(html, encoding="utf-8")
        print("scene-%s.html" % n, len(html))


if __name__ == "__main__":
    main()
