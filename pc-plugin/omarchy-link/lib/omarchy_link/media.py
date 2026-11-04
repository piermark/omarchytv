"""PC media for Cinemarchy: the Omarchy PC's own folders, browsed read-only from the TV.

Roots are the XDG user folders (Videos, Music, Pictures, Download, as set in ~/.config/user-dirs.dirs, so their
localised names work: "Video", "Scaricati", ...), plus ~/Movies when it exists; OMARCHY_MEDIA_DIRS (":"-separated)
replaces them. Paths on the wire are "<root name>/<sub>/<file>": never absolute, never "..", never hidden entries,
and every resolved path must stay inside its root after symlinks. Listings are capped. Files are served only by the
callers (the LG/Samsung bridge with a signed URL, Android through omarchy-link's one-time FileServer).
"""
from __future__ import annotations

import hashlib
import hmac
import os
import re
from pathlib import Path

VIDEO = (".mp4", ".m4v", ".mkv", ".webm", ".mov", ".avi", ".ts", ".m2ts", ".mpg", ".mpeg", ".wmv", ".flv", ".3gp")
AUDIO = (".mp3", ".m4a", ".aac", ".flac", ".ogg", ".opus", ".wav")
IMAGE = (".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp")
SUBS = (".srt", ".vtt")
MIME = {".mp4": "video/mp4", ".m4v": "video/mp4", ".mkv": "video/x-matroska", ".webm": "video/webm", ".mov": "video/quicktime",
        ".avi": "video/x-msvideo", ".ts": "video/mp2t", ".m2ts": "video/mp2t", ".mpg": "video/mpeg", ".mpeg": "video/mpeg",
        ".wmv": "video/x-ms-wmv", ".flv": "video/x-flv", ".3gp": "video/3gpp", ".mp3": "audio/mpeg", ".m4a": "audio/mp4",
        ".aac": "audio/aac", ".flac": "audio/flac", ".ogg": "audio/ogg", ".opus": "audio/ogg", ".wav": "audio/wav",
        ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png", ".webp": "image/webp", ".gif": "image/gif",
        ".bmp": "image/bmp", ".srt": "application/x-subrip", ".vtt": "text/vtt"}
LIMIT = 2000
XDG_KEYS = ("XDG_VIDEOS_DIR", "XDG_MUSIC_DIR", "XDG_PICTURES_DIR", "XDG_DOWNLOAD_DIR")


def kind_of(name: str) -> str | None:
    ext = os.path.splitext(name)[1].lower()
    return "video" if ext in VIDEO else "audio" if ext in AUDIO else "image" if ext in IMAGE else None


def mime_of(name: str) -> str:
    return MIME.get(os.path.splitext(name)[1].lower(), "application/octet-stream")


def _xdg_dirs(home: Path) -> list[Path]:
    out = []
    try:
        text = (home / ".config" / "user-dirs.dirs").read_text(encoding="utf-8")
    except OSError:
        text = ""
    found = dict(re.findall(r'^(XDG_[A-Z]+_DIR)="([^"]*)"', text, re.M))
    for k, default in zip(XDG_KEYS, ("Videos", "Music", "Pictures", "Downloads")):
        v = found.get(k)
        p = Path(v.replace("$HOME", str(home))) if v else home / default
        if p != home:   # an unset XDG folder points at $HOME: never share the whole home
            out.append(p)
    out.append(home / "Movies")
    return out


def roots(env=os.environ, home: Path | None = None) -> list[tuple[str, Path]]:
    """[(name, real path)] of the shared folders that exist, each name unique."""
    home = home or Path.home()
    raw = [Path(p).expanduser() for p in env.get("OMARCHY_MEDIA_DIRS", "").split(":") if p.strip()] or _xdg_dirs(home)
    out, seen, names = [], set(), set()
    for p in raw:
        try:
            r = p.resolve(strict=True)
        except OSError:
            continue
        if not r.is_dir() or r in seen or r == home.resolve() or str(r) == "/":
            continue
        seen.add(r)
        name = r.name or "media"
        while name in names:
            name += "_"
        names.add(name)
        out.append((name, r))
    return out


def resolve(rel: str, env=os.environ, home: Path | None = None) -> Path | None:
    """The real path for "<root>/<sub>/<file>", or None when it is not inside a shared folder."""
    parts = [p for p in str(rel or "").replace("\\", "/").split("/") if p]
    if not parts or any(p in (".", "..") or p.startswith(".") for p in parts) or len(rel) > 1024:
        return None
    for name, root in roots(env, home):
        if name != parts[0]:
            continue
        try:
            p = root.joinpath(*parts[1:]).resolve(strict=True)
        except OSError:
            return None
        if p == root or root in p.parents:
            return p
        return None
    return None


def listing(rel: str = "", env=os.environ, home: Path | None = None) -> dict:
    """{path, parent, dirs: [{name, path}], files: [{name, path, kind, size, sub?}]}; "" lists the shared folders."""
    rel = str(rel or "").strip("/")
    if not rel:
        return {"path": "", "parent": None, "dirs": [{"name": n, "path": n} for n, _ in roots(env, home)], "files": []}
    p = resolve(rel, env, home)
    if p is None or not p.is_dir():
        return {"path": rel, "parent": "", "error": "not_found", "dirs": [], "files": []}
    dirs, files, names = [], [], {}
    try:
        entries = sorted(os.scandir(p), key=lambda e: e.name.casefold())
    except OSError:
        entries = []
    for e in entries:
        names[e.name.casefold()] = e.name
    for e in entries:
        if e.name.startswith(".") or len(dirs) + len(files) >= LIMIT:
            continue
        try:
            if e.is_symlink() and resolve(rel + "/" + e.name, env, home) is None:
                continue   # a link out of the shared folder is not shown at all
            if e.is_dir(follow_symlinks=True):
                dirs.append({"name": e.name, "path": rel + "/" + e.name})
                continue
            k = kind_of(e.name)
            if not k or not e.is_file(follow_symlinks=True):
                continue
            item = {"name": e.name, "path": rel + "/" + e.name, "kind": k, "size": e.stat().st_size}
            if k == "video":   # film.srt / film.vtt next to film.mkv
                stem = os.path.splitext(e.name)[0]
                for ext in SUBS:
                    s = names.get((stem + ext).casefold())
                    if s:
                        item["sub"] = rel + "/" + s
                        break
            files.append(item)
        except OSError:
            continue
    return {"path": rel, "parent": rel.rsplit("/", 1)[0] if "/" in rel else "", "dirs": dirs, "files": files}


def sign(secret: str, rel: str) -> str:
    return hmac.new(secret.encode(), rel.encode("utf-8"), hashlib.sha256).hexdigest()[:32]


def verify(secret: str, rel: str, sig: str) -> bool:
    return bool(secret) and hmac.compare_digest(sign(secret, rel), str(sig or ""))


def srt_to_vtt(text: str) -> str:
    body = re.sub(r"(\d\d:\d\d:\d\d),(\d\d\d)", r"\1.\2", text.replace("\r\n", "\n").lstrip("\ufeff"))
    return body if body.startswith("WEBVTT") else "WEBVTT\n\n" + body
