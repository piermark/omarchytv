"""Shared test setup: an isolated HOME with a fake Omarchy theme, and the mock TV."""
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
# resolve(): on macOS the temp dir is /var/..., a symlink to /private/var/..., and the code under test sees the real path.
TMP = Path(tempfile.mkdtemp(prefix="omarchy-link-test-")).resolve()
HOME = TMP / "home"
os.environ.update(HOME=str(HOME), XDG_RUNTIME_DIR=str(TMP / "run"), XDG_CONFIG_HOME=str(HOME / ".config"),
                  XDG_STATE_HOME=str(HOME / ".local/state"), XDG_CACHE_HOME=str(HOME / ".cache"))
os.environ.pop("OMARCHY_LINK_MDNS", None)
# Hermetic: the real AI CLIs (and cast/recorder/clipboard tools) installed on this machine (claude, codex, ...) must never run in tests.
# Running one in the fake HOME makes it write ~/.claude.json, which then reads as "signed in" for
# every later test. Tests that want a CLI put a stub on PATH themselves.
AI_CLIS = ("claude", "codex", "gemini", "grok", "ollama", "omarchy-agent-usage-update", "claudebar", "ai-usagebar",
           "catt", "wf-recorder", "gpu-screen-recorder", "wl-paste", "xclip", "pbpaste", "notify-send",
           "omarchy-notification-send", "osascript")


def _hide_clis(d: str, n: int) -> str:
    """d itself, or (when it holds one of AI_CLIS) a copy of it made of symlinks to everything else. Dropping
    the whole dir would also hide what shares it, e.g. ffmpeg next to claude in Homebrew's /opt/homebrew/bin."""
    if not any(os.access(os.path.join(d, c), os.X_OK) for c in AI_CLIS):
        return d
    shadow = TMP / "path" / str(n)
    shadow.mkdir(parents=True)
    for name in os.listdir(d):
        if name not in AI_CLIS:
            (shadow / name).symlink_to(os.path.join(d, name))
    return str(shadow)


os.environ["PATH"] = os.pathsep.join(
    _hide_clis(d, n) for n, d in enumerate(os.environ.get("PATH", "/usr/bin:/bin").split(os.pathsep)) if d)
for k in ("ANTHROPIC_API_KEY", "OPENAI_API_KEY", "XAI_API_KEY", "GROK_DEPLOYMENT_KEY", "GEMINI_API_KEY",
          "GOOGLE_API_KEY", "GOOGLE_GENAI_USE_VERTEXAI", "CLAUDE_CONFIG_DIR", "CODEX_HOME", "OLLAMA_HOST",
          "OPENROUTER_API_KEY", "DEEPSEEK_API_KEY", "T3CODE_HOME"):
    os.environ.pop(k, None)
os.environ["OLLAMA_HOST"] = "127.0.0.1:9"  # nothing listens there
(TMP / "run").mkdir(parents=True, exist_ok=True)
sys.path.insert(0, str(ROOT / "lib"))
sys.path.insert(0, str(ROOT / "mock"))

OMARCHY = HOME / ".local/state/omarchy/current"
CLI = str(ROOT / "bin/omarchy-link")

TOKYO = """mode = "dark"
accent = "#7aa2f7"
background = "#1a1b26"
foreground = "#a9b1d6"
blue = "#7aa2f7"
magenta = "#ad8ee6"
bright_magenta = "#bb9af7"
red = "#f7768e"
"""
GRUV = TOKYO.replace("#7aa2f7", "#d79921").replace("#bb9af7", "#d3869b").replace("#1a1b26", "#282828")


def set_theme(name: str, colors: str, bg_color: str = "0x1a1b26", size: str = "7680x4320"):
    theme = OMARCHY / "theme"
    if theme.exists():
        shutil.rmtree(theme)
    theme.mkdir(parents=True)
    (theme / "colors.toml").write_text(colors)
    (OMARCHY / "theme.name").write_text(name + "\n")
    bgdir = HOME / "backgrounds"
    bgdir.mkdir(parents=True, exist_ok=True)
    img = bgdir / f"{name}.png"
    if not img.exists():
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", f"color=c={bg_color}:s={size}",
                        "-frames:v", "1", str(img)], check=True)
    link = OMARCHY / "background"
    if link.is_symlink() or link.exists():
        link.unlink()
    link.symlink_to(img)


def cli(*args, check=True, input_text=None):
    r = subprocess.run([CLI, *args], capture_output=True, text=True, input=input_text, timeout=60)
    if check and r.returncode != 0:
        raise AssertionError(f"omarchy-link {' '.join(args)} -> {r.returncode}\n{r.stdout}\n{r.stderr}")
    return r


# Everything a test may plant that the AI collectors / widget detection would pick up on their own.
# Must all be wiped between tests, whatever the order (the TV repo's runner orders modules differently).
USER_AI_STATE = (".local/state/omarchy-link", ".config/omarchy-link",
                 ".claude", ".claude.json", ".codex", ".grok", ".gemini", ".ollama",
                 ".config/omarchy/plugins", ".config/omarchy/shell.json",
                 ".local/state/omarchy/agents", ".local/state/omarchy/io.github.baranskyi.omacash")


def reset_pc_state():
    """Fresh simulated user: no pairing/config, no signed-in AI CLIs or their logs, no bar widgets."""
    for rel in USER_AI_STATE:
        p = HOME / rel
        if p.is_dir() and not p.is_symlink():
            shutil.rmtree(p, ignore_errors=True)
        else:
            p.unlink(missing_ok=True)
    from omarchy_link import clis
    clis.clear_cache()
