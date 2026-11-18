"""v2.21 Tailscale: find OmarchyTVs on this PC's tailnet and resolve MagicDNS names, from `tailscale status --json`.

No DNS lookups and no Tailscale API: the local tailscaled already knows every peer. Funnel and Serve are never used;
the TV is reached directly on its tailnet address, and only when "tailscale": true (omarchy-link tailscale on).
"""

from __future__ import annotations

import ipaddress
import json
import shutil
import subprocess
from concurrent.futures import ThreadPoolExecutor

from . import netcheck


def status(run=subprocess.run) -> dict | None:
    """`tailscale status --json`, or None without tailscale / when it is stopped."""
    if not shutil.which("tailscale"):
        return None
    try:
        r = run(["tailscale", "status", "--json"], capture_output=True, text=True, timeout=4)
        if r.returncode != 0:
            return None
        data = json.loads(r.stdout or "{}")
        return data if isinstance(data, dict) else None
    except (OSError, ValueError, subprocess.SubprocessError):
        return None


def _v4(ips) -> str | None:
    for ip in ips or []:
        try:
            if ipaddress.ip_address(ip).version == 4 and netcheck.is_tailnet(ip):
                return ip
        except ValueError:
            pass
    return None


def peers(st: dict | None) -> list:
    """[{name, dns, address, online, os}] for every peer with a tailnet IPv4."""
    out = []
    for p in ((st or {}).get("Peer") or {}).values():
        if not isinstance(p, dict):
            continue
        addr = _v4(p.get("TailscaleIPs"))
        if not addr:
            continue
        out.append({"name": str(p.get("HostName") or ""), "dns": str(p.get("DNSName") or "").rstrip("."),
                    "address": addr, "online": p.get("Online") is True, "os": str(p.get("OS") or "")})
    return out


def self_address(st: dict | None) -> str | None:
    return _v4(((st or {}).get("Self") or {}).get("TailscaleIPs"))


def resolve(name: str, st: dict | None) -> str | None:
    """A peer's tailnet IPv4 from its host name, MagicDNS name or the MagicDNS name's first label."""
    n = name.strip().rstrip(".").lower()
    if not n:
        return None
    for p in peers(st):
        dns = p["dns"].lower()
        if n in (p["name"].lower(), dns, dns.split(".", 1)[0]):
            return p["address"]
    return None


def discover(st: dict | None, probe, port: int = 47800, workers: int = 8) -> list:
    """Online peers that answer GET /v1/info as omarchytv-link. `probe(host, port)` -> info dict or raises."""
    cands = [p for p in peers(st) if p["online"]]

    def one(p):
        try:
            info = probe(p["address"], port)
        except Exception:
            return None
        if not isinstance(info, dict) or info.get("service") != "omarchytv-link":
            return None
        return {"name": info.get("name") or p["name"], "address": p["address"], "port": port, "dns": p["dns"],
                "txt": {"pair": "1" if info.get("pairingOpen") else "0", "id": str(info.get("tvId") or "")},
                "via": "tailscale"}

    if not cands:
        return []
    with ThreadPoolExecutor(max_workers=min(workers, len(cands))) as ex:
        return [d for d in ex.map(one, cands) if d]
