"""TV and PC power together.

TV on  -> the TV sends a Wake-on-LAN magic packet to this PC (Android TV; the PC tells it its MAC: wol_info()).
TV off -> the TV says "standby"; after powerGraceS (default 60 s) the PC suspends, unless the TV came back on,
          a liftoff (build/update) is running, or powerFromTv is false. The TV only sends this when its own
          "TV and PC power together" setting is on (default off).
"""

from __future__ import annotations

import ipaddress
import json
import shutil
import subprocess
import sys
import threading
from pathlib import Path

from . import liftoff

MAC = __import__("re").compile(r"^([0-9a-f]{2}:){5}[0-9a-f]{2}$")
SYS_NET = Path("/sys/class/net")

_lock = threading.Lock()
_timer: threading.Timer | None = None


def _ip_json(*args: str) -> list:
    try:
        out = subprocess.run(["ip", "-j", *args], capture_output=True, text=True, timeout=5).stdout
        v = json.loads(out or "[]")
        return v if isinstance(v, list) else []
    except (OSError, ValueError, subprocess.SubprocessError):
        return []


def wol_info(tv_ip: str | None) -> dict | None:
    """{"mac", "broadcast", "wifi"} of the interface this PC uses to reach the TV (Linux), or None."""
    try:
        ipaddress.ip_address(tv_ip or "")
    except ValueError:
        return None
    route = _ip_json("route", "get", tv_ip)
    dev = route[0].get("dev") if route and isinstance(route[0], dict) else None
    if not dev or "/" in dev:
        return None
    try:
        mac = (SYS_NET / dev / "address").read_text().strip().lower()
    except OSError:
        return None
    if not MAC.match(mac) or mac == "00:00:00:00:00:00":
        return None
    bcast = None
    for a in _ip_json("-4", "addr", "show", "dev", dev):
        for inf in a.get("addr_info", []) if isinstance(a, dict) else []:
            if inf.get("family") == "inet" and inf.get("local"):
                try:
                    net = ipaddress.ip_network(f"{inf['local']}/{int(inf.get('prefixlen', 24))}", strict=False)
                    bcast = str(net.broadcast_address)
                except (ValueError, TypeError):
                    pass
    return {"mac": mac, "broadcast": bcast, "wifi": (SYS_NET / dev / "wireless").exists()}


def suspend_argv() -> list[str] | None:
    if sys.platform == "darwin":
        return ["pmset", "sleepnow"]
    return ["systemctl", "suspend"] if shutil.which("systemctl") else None


def _suspend(log) -> None:
    global _timer
    with _lock:
        _timer = None
    st = liftoff.public(liftoff.read_state())
    if st and st["state"] == "running":
        log("TV off, but a liftoff is running: not suspending")
        return
    argv = suspend_argv()
    if not argv:
        log("TV off: no suspend command on this PC")
        return
    log("TV off: suspending the PC")
    try:
        subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                         start_new_session=True)
    except OSError as e:
        log(f"suspend failed: {e}")


def tv_power(state: object, cfg: dict, log=lambda m: None) -> str:
    """The TV turned "standby" or "on". Returns "scheduled", "cancelled", "disabled", "ignored"."""
    global _timer
    if cfg.get("powerFromTv", True) is False:
        return "disabled"
    if state == "on":
        with _lock:
            t, _timer = _timer, None
        if t:
            t.cancel()
            log("TV back on: PC suspend cancelled")
            return "cancelled"
        return "ignored"
    if state != "standby":
        return "ignored"
    try:
        grace = max(10, min(3600, int(cfg.get("powerGraceS", 60))))
    except (TypeError, ValueError):
        grace = 60
    with _lock:
        if _timer:
            return "scheduled"
        _timer = threading.Timer(grace, _suspend, args=(log,))
        _timer.daemon = True
        _timer.start()
    log(f"TV off: the PC suspends in {grace} s unless the TV comes back on")
    return "scheduled"


def pending() -> bool:
    with _lock:
        return _timer is not None
