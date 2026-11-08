"""Read the current Omarchy theme and background, and build the TV payloads."""

from __future__ import annotations

import hashlib
import os
import re
import shutil
import subprocess
import tempfile
import threading
import time
from pathlib import Path

from . import config

HEX = re.compile(r"^#[0-9a-fA-F]{6}$")
VIDEO = re.compile(r"\.(mp4|m4v|mov|webm|mkv|avi)$", re.I)
TV_W, TV_H = 3840, 2160
MAX_JPEG = 8 * 1024 * 1024

# Keys the TV gets (resolved the way omarchy-theme-color resolves them).
PALETTE_KEYS = [
    "background", "dark_background", "darker_background", "lighter_background",
    "foreground", "dark_foreground", "light_foreground", "bright_foreground",
    "accent", "selection", "muted",
    "red", "yellow", "orange", "green", "cyan", "blue", "magenta",
    "bright_red", "bright_yellow", "bright_green", "bright_cyan", "bright_blue", "bright_magenta",
]


THEMES_DIR = config.HOME / ".config/omarchy/themes"
# omarchy-theme-set also accepts the packaged themes. User themes stay under THEMES_DIR.
STOCK_THEMES = Path(os.environ.get("OMARCHY_PATH") or "/usr/share/omarchy") / "themes"
THEME_NAME = re.compile(r"^[a-z0-9][a-z0-9._-]{0,63}$")
SET_GAP_S = 2.0          # the TV may ask at most once every 2 s (a held remote key must not queue ten theme switches)
_set_lock = threading.Lock()
_last_set = 0.0


def installed_themes() -> list[str]:
    """Names omarchy-theme-set accepts: user themes and the stock Omarchy themes."""
    names: set[str] = set()
    for root in (THEMES_DIR, STOCK_THEMES):
        try:
            names.update(p.name for p in root.iterdir() if p.is_dir() and THEME_NAME.match(p.name))
        except OSError:
            continue
    return sorted(names)


def theme_slug(name: str) -> str:
    """Fold a name the way omarchy-theme-set does before it looks up a directory."""
    return re.sub(r"<[^>]+>", "", name).strip().lower().replace(" ", "-")


def set_theme(name: object, now: float | None = None) -> str:
    """
    Reverse theme sync: the TV picked a theme, switch the PC to it with omarchy-theme-set.
    Only names of themes installed here, no shell, rate limited. Returns "ok", "same", "unknown", "busy" or
    "failed". The normal PC -> TV sync then sends the new palette back, so both stay in step.
    """
    global _last_set
    slug = theme_slug(name) if isinstance(name, str) else ""
    if not THEME_NAME.match(slug) or slug not in installed_themes():
        return "unknown"
    if slug == theme_name():
        return "same"
    with _set_lock:
        t = time.monotonic() if now is None else now
        if t - _last_set < SET_GAP_S:
            return "busy"
        _last_set = t
    try:
        subprocess.Popen(["omarchy-theme-set", slug], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                         stderr=subprocess.DEVNULL, start_new_session=True)
    except OSError:
        return "failed"
    return "ok"


def theme_dir() -> Path:
    return config.OMARCHY_STATE / "theme"


def theme_name() -> str:
    try:
        return (config.OMARCHY_STATE / "theme.name").read_text().strip() or "unknown"
    except OSError:
        return "unknown"


def parse_colors_toml(path: Path) -> dict:
    """Same tolerant parse as omarchy-theme-color (key = "value")."""
    out = {}
    try:
        text = path.read_text(encoding="utf-8")
    except OSError:
        return out
    for line in text.splitlines():
        key, sep, value = line.partition("=")
        key = key.strip().strip("\"'")
        if not sep or not key or key.startswith("#"):
            continue
        value = value.strip()
        m = re.match(r"""^["']([^"']*)["']""", value)
        out[key] = m.group(1) if m else value.split("#", 1)[0].strip()
    return out


def _mix(a: str, b: str, amount: float) -> str:
    a, b = a.lstrip("#"), b.lstrip("#")
    ch = [round(int(a[i:i + 2], 16) * (1 - amount) + int(b[i:i + 2], 16) * amount) for i in (0, 2, 4)]
    return "#%02x%02x%02x" % tuple(ch)


def resolve_palette(raw: dict) -> dict:
    c = dict(raw)
    for k, legacy in {"background": "bg", "foreground": "fg", "dark_background": "dark_bg",
                      "darker_background": "darker_bg", "lighter_background": "lighter_bg",
                      "dark_foreground": "dark_fg", "light_foreground": "light_fg",
                      "bright_foreground": "bright_fg"}.items():
        c.setdefault(k, c.get(legacy, ""))
    c["background"] = c.get("background") or c.get("color0", "#1a1b26")
    c["foreground"] = c.get("foreground") or c.get("color7", "#a9b1d6")
    for name, idx in {"red": 1, "green": 2, "yellow": 3, "blue": 4, "magenta": 5, "cyan": 6,
                      "bright_red": 9, "bright_green": 10, "bright_yellow": 11, "bright_blue": 12,
                      "bright_magenta": 13, "bright_cyan": 14}.items():
        c[name] = c.get(name) or c.get(f"color{idx}", "")
    c["magenta"] = c["magenta"] or c.get("purple", "")
    c.setdefault("muted", c.get("color8", c.get("dark_foreground", "")))
    c["muted"] = c["muted"] or c.get("dark_foreground", "")
    c["light_foreground"] = c.get("light_foreground") or c["foreground"]
    c["bright_foreground"] = c.get("bright_foreground") or c.get("color15") or c["foreground"]
    c["lighter_background"] = c.get("lighter_background") or c["background"]
    c["dark_foreground"] = c.get("dark_foreground") or c.get("color8") or c["foreground"]
    c["selection"] = c.get("selection") or c.get("selection_background") or c.get("color8") or c["background"]
    c["orange"] = c.get("orange") or c["yellow"]
    bg = c["background"] if HEX.match(c["background"]) else "#1a1b26"
    c["dark_background"] = c.get("dark_background") or _mix(bg, "#000000", 0.25)
    c["darker_background"] = c.get("darker_background") or _mix(bg, "#000000", 0.5)
    for base in ("red", "yellow", "green", "cyan", "blue", "magenta"):
        if not c.get("bright_" + base) and HEX.match(c.get(base, "")):
            c["bright_" + base] = _mix(c[base], "#ffffff", 0.2)
    c["accent"] = c.get("accent") or c.get("blue") or "#7aa2f7"
    return c


def current_palette() -> dict:
    """Prefer Omarchy's own resolver; fall back to parsing colors.toml."""
    resolved = {}
    tool = shutil.which("omarchy-theme-color")
    colors = theme_dir() / "colors.toml"
    if tool and colors.is_file():
        try:
            out = subprocess.run([tool, "--all"], capture_output=True, text=True, timeout=5).stdout
            resolved = dict(line.split("\t", 1) for line in out.splitlines() if "\t" in line)
        except (OSError, subprocess.SubprocessError, ValueError):
            resolved = {}
    if not resolved:
        resolved = resolve_palette(parse_colors_toml(colors))
    return resolved


def theme_payload(wallpaper_sha: str | None = None) -> dict:
    pal = current_palette()
    colors = {k: pal[k].lower() for k in PALETTE_KEYS if HEX.match(pal.get(k, ""))}
    mode = pal.get("mode") or ("light" if (theme_dir() / "light.mode").exists() else "dark")
    accent = colors.get("accent", "#7aa2f7")
    accent2 = colors.get("bright_magenta") or colors.get("magenta") or accent
    payload = {
        "name": theme_name(),
        "mode": mode if mode in ("dark", "light") else "dark",
        "accent": accent,
        "accent2": accent2,
        "colors": colors,
    }
    if wallpaper_sha:
        payload["wallpaper"] = {"sha256": wallpaper_sha}
    return payload


# ------------------------------------------------------------ wallpaper

def current_background() -> Path | None:
    link = config.OMARCHY_STATE / "background"
    try:
        target = Path(os.path.realpath(link))
    except OSError:
        return None
    return target if target.is_file() else None


def fingerprint() -> str:
    """Cheap identity of (theme, background) to detect changes without re-encoding."""
    colors = theme_dir() / "colors.toml"
    bg = current_background()
    parts = [theme_name()]
    for p in (colors, bg):
        try:
            st = p.stat() if p else None
            parts.append(f"{p}:{st.st_size}:{int(st.st_mtime)}" if st else "-")
        except OSError:
            parts.append("-")
    return hashlib.sha256("|".join(parts).encode()).hexdigest()


def _ffmpeg_filter() -> str:
    # centre-crop to 16:9, then shrink to at most 3840x2160 (never upscale)
    return ("crop='min(iw,ih*16/9)':'min(ih,iw*9/16)',"
            f"scale='min({TV_W},iw)':-2:flags=lanczos,format=yuvj420p")


def render_wallpaper(src: Path, out_dir: Path | None = None) -> tuple[bytes, str]:
    """Returns (jpeg bytes, sha256 hex). Uses ffmpeg (stills and videos), or ImageMagick."""
    out_dir = config.ensure_private_dir(out_dir or config.CACHE_DIR)
    key = hashlib.sha256(f"{src}:{src.stat().st_size}:{int(src.stat().st_mtime)}".encode()).hexdigest()[:24]
    cached = out_dir / f"wallpaper-{key}.jpg"
    if cached.is_file():
        data = cached.read_bytes()
        return data, hashlib.sha256(data).hexdigest()
    with tempfile.TemporaryDirectory(dir=out_dir) as tmp:
        dst = Path(tmp) / "out.jpg"
        for quality in (3, 6, 10):
            if shutil.which("ffmpeg"):
                cmd = ["ffmpeg", "-nostdin", "-loglevel", "error", "-y"]
                if VIDEO.search(src.name):
                    cmd += ["-ss", "1"]
                cmd += ["-i", str(src), "-frames:v", "1", "-vf", _ffmpeg_filter(), "-q:v", str(quality), str(dst)]
            elif shutil.which("magick") and not VIDEO.search(src.name):
                q = {3: 88, 6: 78, 10: 65}[quality]
                cmd = ["magick", str(src) + "[0]", "-auto-orient", "-gravity", "center",
                       "-resize", f"{TV_W}x{TV_H}>", "-strip", "-quality", str(q), str(dst)]
            else:
                raise RuntimeError("ffmpeg (or ImageMagick) is required to send the wallpaper")
            subprocess.run(cmd, check=True, capture_output=True, timeout=120)
            data = dst.read_bytes()
            if len(data) <= MAX_JPEG:
                break
        else:
            raise RuntimeError("wallpaper stays above 8 MiB even at low quality")
        # drop older cache entries
        for old in out_dir.glob("wallpaper-*.jpg"):
            try:
                old.unlink()
            except OSError:
                pass
        cached.write_bytes(data)
        os.chmod(cached, 0o600)
    return data, hashlib.sha256(data).hexdigest()
