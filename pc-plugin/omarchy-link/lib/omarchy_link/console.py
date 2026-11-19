"""v2.21 couch console: a terminal of this PC mirrored on the TV, read-only.

`omarchy-link console` shows a tmux pane (the current one inside tmux, or --tmux TARGET) or the end of a log file
(--file PATH) as a page on the TV, tiled like any page. A small HTTP server on this PC's LAN address serves it under
a random path, refreshing once a second; only the paired TV's address may read it (plain HTTP
on the LAN, like the TV's own pages), and nothing can be typed from the TV. Ctrl+C or SIGTERM stops it and closes the page.
Keep an agent in the tmux session of your Super+~ drop-down terminal and watch it from the couch.
"""

from __future__ import annotations

import html
import http.server
import json
import re
import secrets
import socket
import subprocess
import threading
from pathlib import Path

ANSI = re.compile(r"\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b\][^\x07]*(\x07|\x1b\\)")
MAX_LINES = 60


def strip(text: str) -> str:
    return "".join(ch for ch in ANSI.sub("", text) if ch == "\n" or ch.isprintable())


def tmux_text(target: str | None, run=subprocess.run) -> str:
    cmd = ["tmux", "capture-pane", "-p", "-J"] + (["-t", target] if target else [])
    r = run(cmd, capture_output=True, text=True, timeout=3)
    if r.returncode != 0:
        raise RuntimeError((r.stderr or "tmux capture-pane failed").strip()[:120])
    return r.stdout


def file_text(path: str) -> str:
    p = Path(path).expanduser()
    with open(p, "rb") as f:
        f.seek(0, 2)
        size = f.tell()
        f.seek(max(0, size - 64 * 1024))
        return f.read().decode("utf-8", "replace")


def snapshot(source) -> str:
    lines = strip(source()).rstrip("\n").split("\n")
    return "\n".join(lines[-MAX_LINES:])


PAGE = """<!doctype html><meta charset="utf-8"><title>{title}</title>
<style>html,body{{margin:0;background:#1a1b26;color:#c0caf5;font:22px/1.3 monospace}}
#t{{white-space:pre-wrap;padding:18px 24px}}#h{{color:#7aa2f7;padding:10px 24px 0}}</style>
<div id="h">{title} · read-only</div><div id="t"></div>
<script>
var tk="{token}",fg=null;
function tick(){{var x=new XMLHttpRequest();x.open("GET","/"+tk+"/text",true);
x.onload=function(){{if(x.status==200){{var d=JSON.parse(x.responseText);
document.getElementById("t").textContent=d.text;window.scrollTo(0,document.body.scrollHeight);}}}};x.send();}}
tick();setInterval(tick,1000);
</script>"""


def make_handler(token: str, source, title: str, allow=None):
    class H(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def _send(self, code, body, ctype):
            b = body.encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(b)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(b)

        def do_GET(self):
            peer = self.client_address[0].split("%", 1)[0].removeprefix("::ffff:")
            if allow and peer not in allow:   # only the paired TV reads it (and the random path on top)
                return self._send(403, "forbidden", "text/plain")
            if self.path == f"/{token}/":
                return self._send(200, PAGE.format(title=html.escape(title), token=token), "text/html; charset=utf-8")
            if self.path == f"/{token}/text":
                try:
                    text = snapshot(source)
                except Exception as e:   # the pane went away: say so on the TV
                    text = f"[{e}]"
                return self._send(200, json.dumps({"text": text}), "application/json")
            return self._send(404, "not found", "text/plain")
    return H


def lan_ip(tv_host: str) -> str:
    """The address of this PC the TV can reach: the one the route to the TV leaves from."""
    s = socket.socket(socket.AF_INET6 if ":" in tv_host else socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect((tv_host, 47800))
        return s.getsockname()[0]
    finally:
        s.close()


def serve(bind: str, source, title: str, allow=None):
    token = secrets.token_urlsafe(12)
    cls = http.server.ThreadingHTTPServer
    if ":" in bind:
        cls = type("V6", (http.server.ThreadingHTTPServer,), {"address_family": socket.AF_INET6})
    srv = cls((bind, 0), make_handler(token, source, title, allow))
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    host = f"[{bind}]" if ":" in bind else bind
    return srv, f"http://{host}:{srv.server_address[1]}/{token}/"
