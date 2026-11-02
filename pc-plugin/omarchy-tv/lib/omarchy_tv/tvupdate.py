"""LG webOS / Samsung Tizen: "Update via PC" from the TV.

The TV (POST /v1/update/tv on webos-serve) asks; this PC looks at the official OmarchyTV releases, downloads the
newest package for that platform, checks it and installs it on that TV:
- LG:      omarchytv-webos-<ver>[-testing].ipk -> appinfo id must be com.omarchytv.home -> ares-install, ares-launch
- Samsung: OmarchyTV-tizen-<ver>-unsigned.wgt -> signed here with YOUR Samsung certificate profile
           ("tizenProfile" in omarchy-link config.json) -> sdb connect <TV IP> -> tizen install
Only assets of github.com/piermark/omarchytv releases, only newer than what the TV runs, one job at a time.
Progress goes to the liftoff state, so the TV shows the rocket countdown and an alarm if it fails.
"""

from __future__ import annotations

import hashlib
import ipaddress
import json
import re
import shutil
import subprocess
import tempfile
import threading
import urllib.request
import zipfile
from pathlib import Path

from . import update

RELEASES_URL = "https://api.github.com/repos/piermark/omarchytv/releases?per_page=30"
DOWNLOAD_PREFIX = "https://github.com/piermark/omarchytv/releases/download/"
MAX_BYTES = 64 * 1024 * 1024
VER = r"(\d+\.\d+\.\d+(?:-(?!testing)[0-9A-Za-z.]+)?)"
ASSET = {
    "webos": re.compile(r"^omarchytv-webos-" + VER + r"(?:-testing)?\.ipk$"),
    "tizen": re.compile(r"^OmarchyTV-tizen-" + VER + r"-unsigned\.wgt$"),
}
_job = threading.Lock()


class UpdateError(Exception):
    def __init__(self, code: str, message: str = ""):
        super().__init__(message or code)
        self.code = code


def version_key(v: str) -> tuple:
    """2.13.11 > 2.13.11-beta > 2.13.10 (a pre-release sorts before its release)."""
    m = re.match(r"^(\d+)\.(\d+)\.(\d+)(?:-(.+))?$", str(v or "").strip().lstrip("v"))
    if not m:
        return (-1,)
    nums = tuple(int(x) for x in m.groups()[:3])
    pre = m.group(4)
    if pre and pre.startswith("testing"):
        pre = None   # "-testing" names the channel of an ordinary LG build, not a pre-release
    return nums + ((1, "") if not pre else (0, pre))


def pick(releases: list, platform: str, current: str) -> dict | None:
    """Newest asset for platform strictly newer than current: {"version", "name", "url", "size", "sha256"}."""
    rx = ASSET.get(platform)
    if not rx or not isinstance(releases, list):
        return None
    best = None
    for rel in releases:
        if not isinstance(rel, dict) or rel.get("draft"):
            continue
        for a in rel.get("assets") or []:
            m = rx.match(str(a.get("name", "")))
            url = str(a.get("browser_download_url", ""))
            if not m or not url.startswith(DOWNLOAD_PREFIX):
                continue
            ver = m.group(1)
            if best and version_key(ver) <= version_key(best["version"]):
                continue
            dg = str(a.get("digest") or "")
            best = {"version": ver, "name": a["name"], "url": url, "size": int(a.get("size") or 0),
                    "sha256": dg[7:].lower() if dg.startswith("sha256:") else None}
    if best and version_key(best["version"]) > version_key(current):
        return best
    return None


def _get(url: str, limit: int, timeout: float = 20.0) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "omarchy-tv", "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:   # noqa: S310 (fixed https URLs only)
        data = r.read(limit + 1)
    if len(data) > limit:
        raise UpdateError("too_large", "download larger than allowed")
    return data


def releases() -> list:
    try:
        v = json.loads(_get(RELEASES_URL, 4 * 1024 * 1024))
    except (OSError, ValueError) as e:
        raise UpdateError("offline", f"cannot read the releases: {e}") from e
    return v if isinstance(v, list) else []


def check(platform: str, current: str) -> dict:
    """{"current", "latest" (or None), "name"} for the TV's "Update via PC" confirm."""
    if platform not in ASSET:
        raise UpdateError("bad_platform", "platform must be webos or tizen")
    a = pick(releases(), platform, current)
    return {"current": current, "latest": a["version"] if a else None, "name": a["name"] if a else None}


def _run(argv: list[str], timeout: int = 300) -> subprocess.CompletedProcess:
    return subprocess.run(argv, capture_output=True, text=True, timeout=timeout, stdin=subprocess.DEVNULL)


def _fail(r: subprocess.CompletedProcess, what: str) -> None:
    if r.returncode != 0:
        last = (r.stderr or r.stdout or "").strip().splitlines()[-1:] or [""]
        raise UpdateError("install_failed", f"{what}: {last[0][:160]}")


def install_webos(pkg: Path, cfg: dict, tv_ip: str, tracker) -> None:
    why = update.check_ipk(update.ipk_info(str(pkg)))
    if why:
        raise UpdateError("bad_package", why)
    tool = shutil.which("ares-install")
    if not tool:
        raise UpdateError("no_tool", "ares-install not found (npm install -g @webos-tools/cli)")
    dev = cfg.get("webosDevice")
    dev_args = ["--device", str(dev)] if dev else []
    tracker.stage("install", 70)
    _fail(_run([tool, *dev_args, str(pkg)]), "ares-install")
    tracker.stage("launch", 95)
    launch = shutil.which("ares-launch")
    if launch:
        _run([launch, *dev_args, update.PACKAGE], timeout=60)


def _unpack_wgt(pkg: Path, stage: Path) -> None:
    """The unsigned widget's files into stage/ (a .wgt is a zip); refuses odd paths and anything not OmarchyTV."""
    try:
        with zipfile.ZipFile(pkg) as z:
            names = z.namelist()
            if "config.xml" not in names or len(names) > 2000:
                raise UpdateError("bad_package", "not an OmarchyTV widget")
            if b'package="OmarchyTV0"' not in z.read("config.xml"):
                raise UpdateError("bad_package", "not an OmarchyTV widget")
            for n in names:
                p = Path(n)
                if p.is_absolute() or ".." in p.parts:
                    raise UpdateError("bad_package", "bad path in the widget")
                if p.name in ("author-signature.xml", "signature1.xml"):
                    continue
                dest = stage / p
                if n.endswith("/"):
                    dest.mkdir(parents=True, exist_ok=True)
                    continue
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_bytes(z.read(n))
    except zipfile.BadZipFile as e:
        raise UpdateError("bad_package", "not a widget") from e


def install_tizen(pkg: Path, cfg: dict, tv_ip: str, tracker) -> None:
    profile = cfg.get("tizenProfile")
    tizen, sdb = shutil.which("tizen"), shutil.which("sdb")
    if not profile or not re.match(r"^[A-Za-z0-9_.-]{1,64}$", str(profile)):
        raise UpdateError("no_profile", "set your Samsung certificate profile: \"tizenProfile\" in omarchy-link config.json")
    if not tizen or not sdb:
        raise UpdateError("no_tool", "Tizen Studio CLI (tizen, sdb) not found")
    tracker.stage("sign", 55)
    stage, out = pkg.parent / "stage", pkg.parent / "signed"
    out.mkdir()
    _unpack_wgt(pkg, stage)
    # The same command as tizen/build-wgt.sh, on the release's files.
    _fail(_run([tizen, "package", "-t", "wgt", "-s", str(profile), "-o", str(out), "--", str(stage)]), "tizen package")
    signed = next(iter(sorted(out.glob("*.wgt"))), None)
    if not signed:
        raise UpdateError("install_failed", "tizen package made no .wgt")
    tracker.stage("install", 75)
    target = f"{tv_ip}:26101"
    _run([sdb, "connect", tv_ip], timeout=30)
    _fail(_run([tizen, "install", "-n", signed.name, "-t", target, "--", str(out)]), "tizen install")


def start(platform: str, current: str, want: str, tv_ip: str, cfg: dict, tracker_factory) -> str:
    """Begin the update in a thread. Returns "started", "uptodate" or "busy"; raises UpdateError."""
    if platform not in ASSET:
        raise UpdateError("bad_platform", "platform must be webos or tizen")
    try:
        ipaddress.ip_address(tv_ip)
    except ValueError as e:
        raise UpdateError("bad_request", "TV address") from e
    if not _job.acquire(blocking=False):
        return "busy"
    handed_over = False
    try:
        asset = pick(releases(), platform, current)
        if not asset:
            return "uptodate"
        if want and asset["version"] != want:
            raise UpdateError("changed", f"the newest release is now {asset['version']}")
        tracker = tracker_factory("update", f"OmarchyTV {asset['version']}")
        threading.Thread(target=_work, args=(platform, asset, tv_ip, cfg, tracker), daemon=True).start()
        handed_over = True   # _work releases the lock
        return "started"
    finally:
        if not handed_over:
            _job.release()


def _work(platform: str, asset: dict, tv_ip: str, cfg: dict, tracker) -> None:
    try:
        with tempfile.TemporaryDirectory(prefix="omarchytv-update-") as d:
            tracker.stage("download", 10)
            if asset["size"] > MAX_BYTES:
                raise UpdateError("too_large", "package larger than 64 MB")
            data = _get(asset["url"], MAX_BYTES, timeout=120)
            tracker.stage("verify", 40)
            if asset["sha256"] and hashlib.sha256(data).hexdigest() != asset["sha256"]:
                raise UpdateError("bad_package", "checksum does not match the release")
            pkg = Path(d) / asset["name"]
            pkg.write_bytes(data)
            (install_webos if platform == "webos" else install_tizen)(pkg, cfg, tv_ip, tracker)
        tracker.done(True, 0)
    except (UpdateError, OSError, subprocess.SubprocessError) as e:
        tracker.done(False, 1, str(e))
    finally:
        _job.release()
