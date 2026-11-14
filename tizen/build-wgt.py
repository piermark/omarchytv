"""Build a complete unsigned TV widget without a Tizen Studio dependency."""
import base64
import argparse
import hashlib
from pathlib import Path
import re
import zipfile
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("output", nargs="?", type=Path, default=root / "tizen/out")
parser.add_argument("--stage", action="store_true", help="Also write a complete stage/ directory for Tizen Studio signing")
args = parser.parse_args()
out = args.output.resolve()
out.mkdir(parents=True, exist_ok=True)
version = ET.parse(root / "tizen/config.xml").getroot().get("version")
if not version or not re.fullmatch(r"\d+\.\d+\.\d+", version):
    raise ValueError("Invalid widget version")
target = out / ("OmarchyTV-tizen-" + version + "-unsigned.wgt")
if target.exists() or (args.stage and (out / "stage").exists()):
    parser.error("Build output already exists; choose a new output directory")
payload = {}
for path in (root / "webos/app").rglob("*"):
    if path.is_file():
        name = path.relative_to(root / "webos/app").as_posix()
        if name != "appinfo.json" and not name.startswith("rooted/"):
            payload[name] = path.read_bytes()
main = root / "launcher/app/src/main"
cal = main / "assets/racing_2026.json"
if cal.is_file():
    payload["racing_2026.json"] = cal.read_bytes()
for prefix, source in [("fonts", main / "res/font"), ("wallpapers", main / "assets/wallpapers"),
                       ("fractals", main / "assets/fractals"),
                       ("licenses", main / "assets/licenses"), ("saver", main / "assets/saver")]:
    for path in source.rglob("*"):
        if path.is_file() and (prefix != "fonts" or path.suffix == ".ttf"):
            name = prefix + "/" + path.relative_to(source).as_posix()
            # Like webos/build-ipk.sh, keep the shared web app's saver/index.html (the webOS page with the
            # Aquarium); the Android page has no Aquarium and the wrong font path.
            if name != "saver/index.html" or name not in payload:
                payload[name] = path.read_bytes()
icons = (root / "webos/build-ipk.sh").read_text()
for marker, name in [("B64_ICON", "icon.png"), ("B64_LARGE", "largeIcon.png"), ("B64_SPLASH", "splash.png")]:
    match = re.search(r"<< '" + marker + r"'[^\n]*\n(.*?)\n" + marker, icons, re.S)
    if not match:
        raise ValueError("Missing bundled icon: " + marker)
    payload[name] = base64.b64decode(match[1])
for name in ["config.xml", "tizen-shim.js"]:
    payload[name] = (root / "tizen" / name).read_bytes()
html = payload["index.html"].decode()
assert html.count('<script src="compat.js"></script>') == 1
payload["index.html"] = html.replace('<script src="compat.js"></script>', '<script src="tizen-shim.js"></script>\n<script src="compat.js"></script>').encode()
for name, data in list(payload.items()):
    if name.endswith(".webp") and data.startswith(b"UklG"):
        payload[name] = base64.b64decode(data)
assert payload["icon.png"].startswith(b"\x89PNG")
assert "fonts/caskaydia_mono_regular.ttf" in payload and "saver/tte-bundle.js" in payload
if args.stage:
    stage = out / "stage"
    stage.mkdir()
    for name, data in sorted(payload.items()):
        path = stage / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
with zipfile.ZipFile(target, "x", zipfile.ZIP_DEFLATED) as archive:
    for name, data in sorted(payload.items()):
        entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
        entry.compress_type = zipfile.ZIP_DEFLATED
        entry.external_attr = 0o100644 << 16
        archive.writestr(entry, data)
print(str(target))
print(hashlib.sha256(target.read_bytes()).hexdigest())
