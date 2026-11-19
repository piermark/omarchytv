"""omarchy-tv wg: push a WireGuard config to an Android TV and toggle the tunnel over ADB.

Uses the official WireGuard Android app (com.wireguard.android, Apache-2.0, not bundled).
Toggling needs "Allow remote control apps" enabled in the WireGuard app's Advanced settings;
the app documents SET_TUNNEL_UP / SET_TUNNEL_DOWN broadcasts for that.
Importing the pushed file is still one manual step in the app (+ -> Import from file).
"""
import os, re, shutil, subprocess

PKG = "com.wireguard.android"
RECEIVER = PKG + "/.model.TunnelManager$IntentReceiver"
REMOTE_DIR = "/sdcard/Download"
NAME_RE = re.compile(r"^[a-zA-Z0-9_=+.-]{1,15}$")  # WireGuard interface-name rules


def tunnel_name(conf_path: str) -> str:
    name = os.path.splitext(os.path.basename(conf_path))[0]
    if not NAME_RE.match(name):
        raise ValueError(f"tunnel name '{name}' must be 1-15 chars of [a-zA-Z0-9_=+.-]")
    return name


def validate_conf(text: str) -> None:
    """Minimal sanity check; never prints the private key."""
    if "[Interface]" not in text or "[Peer]" not in text:
        raise ValueError("not a WireGuard config: needs [Interface] and [Peer] sections")
    if not re.search(r"(?m)^\s*PrivateKey\s*=", text):
        raise ValueError("[Interface] has no PrivateKey")


def adb_base(serial: str | None) -> list[str]:
    return ["adb", "-s", serial] if serial else ["adb"]


def push_cmd(serial, conf_path):
    return adb_base(serial) + ["push", conf_path, f"{REMOTE_DIR}/{os.path.basename(conf_path)}"]


def clean_cmd(serial, name):
    return adb_base(serial) + ["shell", "rm", "-f", f"{REMOTE_DIR}/{name}.conf"]


def toggle_cmd(serial, name, up: bool):
    action = PKG + (".action.SET_TUNNEL_UP" if up else ".action.SET_TUNNEL_DOWN")
    return adb_base(serial) + ["shell", "am", "broadcast", "-a", action, "-n", RECEIVER, "-e", "tunnel", name]


def parse_args(args):
    serial = None
    rest = []
    i = 0
    while i < len(args):
        if args[i] in ("--tv", "--device") and i + 1 < len(args):
            serial = args[i + 1]; i += 2; continue
        rest.append(args[i]); i += 1
    return serial, rest


USAGE = "omarchy-tv wg push <wg0.conf> | clean <NAME> | up <NAME> | down <NAME> [--tv IP:5555|DEVICE]"


def run(args, runner=subprocess.run, say=print) -> int:
    serial, rest = parse_args(args)
    if len(rest) < 2 or rest[0] not in ("push", "clean", "up", "down"):
        say(USAGE); return 2
    if runner is subprocess.run and not shutil.which("adb"):
        say("✗ adb not found (pacman -S android-tools)"); return 1
    if serial and ":" in serial:
        runner(["adb", "connect", serial], capture_output=True, timeout=15)
    op, arg = rest[0], rest[1]
    if op == "push":
        with open(arg, encoding="utf-8") as f:
            validate_conf(f.read())
        name = tunnel_name(arg)
        r = runner(push_cmd(serial, arg))
        if r.returncode == 0:
            say(f"✓ pushed to {REMOTE_DIR}/{os.path.basename(arg)}. On the TV: WireGuard → + → Import from file, "
                f"then enable Advanced → Allow remote control apps, and use 'omarchy-tv wg up {name}'.")
            say(f"! that file holds the tunnel's private key and any app with storage access can read it: once "
                f"imported, delete it with 'omarchy-tv wg clean {name}'.")
        return r.returncode
    if not NAME_RE.match(arg):
        say(f"✗ invalid tunnel name '{arg}'"); return 2
    if op == "clean":   # v2.21: the pushed .conf (with its PrivateKey) off the TV's Download folder
        r = runner(clean_cmd(serial, arg))
        if r.returncode == 0:
            say(f"✓ removed {REMOTE_DIR}/{arg}.conf from the TV (the tunnel stays in the WireGuard app)")
        return r.returncode
    r = runner(toggle_cmd(serial, arg, op == "up"))
    if r.returncode == 0:
        say(f"✓ tunnel {arg} {op} requested (check the WireGuard app if remote control is not allowed)")
    return r.returncode
