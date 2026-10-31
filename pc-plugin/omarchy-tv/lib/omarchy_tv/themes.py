"""User themes (~/.config/omarchy/themes/<name>) -> TV (PUT /v1/themes/<name>, ZIP). Bundled themes are in the APK.

ZIP layout (checked by the TV): theme.json {mode, accent, accent2, colors{role: #rrggbb}} + backgrounds/NN-name.ext
(jpg/png/webp, at most 32). Every custom theme on the TV together: at most 50 MB.
"""
import io, re, shutil, subprocess, tempfile, zipfile
from dataclasses import dataclass
from pathlib import Path

USER = Path.home() / ".config/omarchy/themes"
BUNDLED = {"catppuccin", "catppuccin-latte", "blue-mosaic", "cumulus", "ethereal", "everforest", "flexoki-light", "green-mosaic", "gruvbox", "hackerman",
           "hermarchy", "jade-horse", "kanagawa", "last-horizon", "lumon", "lupine", "matte-black", "miasma", "neon-glow", "norrsken",
           "nord", "osaka-jade", "retro-82", "ristretto", "rose-pine", "solitude", "starship", "tokyo-night",
           "tokyo-roadster", "vantablack", "white", "year-of-the-horse"}
IMG = (".jpg", ".jpeg", ".png", ".webp")
CAP = 50 * 1024 * 1024
MAX_IMAGES = 32
NAME = re.compile(r"^[a-z0-9][a-z0-9-]{0,39}$")


@dataclass
class UserTheme:
    name: str
    dir: Path
    colors: Path
    backgrounds: list


def tv_name(name: str) -> str:
    n = re.sub(r"[^a-z0-9-]+", "-", name.lower()).strip("-")[:40]
    if not NAME.match(n or ""):
        raise ValueError(f"cannot make a TV theme name from {name!r}")
    return n


def user_themes(root: Path = USER, include_bundled: bool = False) -> list:
    out = []
    if not root.is_dir():
        return out
    for d in sorted(p for p in root.iterdir() if p.is_dir()):
        if (d.name in BUNDLED and not include_bundled) or not (d / "colors.toml").is_file():
            continue
        bgd = d / "backgrounds"
        bg = sorted(p for p in bgd.iterdir() if p.suffix.lower() in IMG) if bgd.is_dir() else []
        out.append(UserTheme(d.name, d, d / "colors.toml", bg))
    return out


def parse_colors(text: str) -> dict:
    out = {}
    for line in text.splitlines():
        m = re.match(r'\s*([a-z_0-9]+)\s*=\s*"(#[0-9a-fA-F]{6})"', line)
        if m:
            out[m.group(1)] = m.group(2).lower()
    return out


def theme_json(t: UserTheme) -> dict:
    c = parse_colors(t.colors.read_text())
    accent = c.get("accent") or c.get("color4") or "#7aa2f7"
    mode = "light" if (t.dir / "light.mode").exists() else "dark"
    return {"mode": mode, "accent": accent, "accent2": c.get("color5", accent), "colors": c}


def encode_background(src: Path, max_w=3840, max_h=2160, quality=82) -> tuple[bytes, str]:
    """<= 3840x2160 (scale down + centre crop). Pillow, else ffmpeg, else the file as is (TV checks it)."""
    try:
        from PIL import Image  # optional
        im = Image.open(src).convert("RGB")
        s = max(max_w / im.width, max_h / im.height)
        if s < 1:
            im = im.resize((round(im.width * s), round(im.height * s)), Image.LANCZOS)
        x, y = max(0, (im.width - max_w) // 2), max(0, (im.height - max_h) // 2)
        im = im.crop((x, y, x + min(im.width, max_w), y + min(im.height, max_h)))
        b = io.BytesIO()
        im.save(b, "WEBP", quality=quality, method=5)
        return b.getvalue(), "webp"
    except ImportError:
        pass
    if shutil.which("ffmpeg"):
        with tempfile.TemporaryDirectory() as td:
            out = Path(td) / "o.jpg"
            vf = f"scale='if(gt(iw,{max_w})+gt(ih,{max_h}),{max_w},iw)':'if(gt(iw,{max_w})+gt(ih,{max_h}),{max_h},ih)':" \
                 f"force_original_aspect_ratio=increase,crop='min(iw,{max_w})':'min(ih,{max_h})'"
            r = subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(src), "-vf", vf, "-q:v", "3", str(out)],
                               capture_output=True)
            if r.returncode == 0 and out.exists():
                return out.read_bytes(), "jpg"
    ext = src.suffix.lower().lstrip(".")
    return src.read_bytes(), "jpg" if ext == "jpeg" else ext


def build_zip(t: UserTheme, encode=encode_background) -> bytes:
    import json
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_STORED) as z:
        z.writestr("theme.json", json.dumps(theme_json(t)))
        for i, src in enumerate(t.backgrounds[:MAX_IMAGES], 1):
            data, ext = encode(src)
            stem = re.sub(r"[^A-Za-z0-9._-]+", "-", src.stem).strip("-.")[:50] or "bg"
            z.writestr(f"backgrounds/{i:02d}-{stem}.{ext}", data)
    data = buf.getvalue()
    if len(data) > CAP:
        raise ValueError(f"theme {t.name}: {len(data) / 1e6:.1f} MB, over the 50 MB TV cap")
    return data


def push(client, t: UserTheme, encode=encode_background) -> dict:
    data = build_zip(t, encode)
    return client.request("PUT", f"/v1/themes/{tv_name(t.name)}", raw=data, content_type="application/zip",
                          timeout=120)


def list_tv(client) -> dict:
    return client.request("GET", "/v1/themes")


def delete(client, name: str) -> dict:
    return client.request("DELETE", f"/v1/themes/{tv_name(name)}")


def select(client, name: str, bg: int = 0) -> dict:
    return client.request("POST", f"/v1/themes/{tv_name(name)}/select", {"bg": bg})
