"""#79: `omarchy-link session --daemon` keeps the session up when stdin closes, so it can run under systemd."""
import io
import os
import subprocess
import sys
import threading
import time
import unittest

import helpers  # noqa: F401  (hermetic env + sys.path)
from omarchy_link import session


class DaemonMode(unittest.TestCase):
    def run_session(self, daemon):
        out = io.StringIO()
        s = session.Session(out=out)
        s.out = out
        done = threading.Event()

        def go():
            s.run(daemon=daemon)
            done.set()

        old = sys.stdin
        sys.stdin = io.StringIO("")   # what systemd hands a unit without StandardInput: an immediate EOF
        try:
            threading.Thread(target=go, daemon=True).start()
            time.sleep(0.4)
            alive = not done.is_set()
        finally:
            s.stop.set()
            sys.stdin = old
            done.wait(2)
        return alive, out.getvalue()

    def test_daemon_ignores_stdin_eof(self):
        alive, out = self.run_session(daemon=True)
        self.assertTrue(alive, "the session ended on stdin EOF although --daemon was given")
        self.assertIn('"event":"state"', out)     # still publishes
        self.assertIn("resta attiva", out)        # and says why it stayed

    def test_without_the_flag_a_closed_stdin_still_ends_it(self):
        alive, _ = self.run_session(daemon=False)
        self.assertFalse(alive, "the widget's session must still end when its stdin closes")

    def test_the_cli_has_the_flag(self):
        p = subprocess.run([sys.executable, "-c",
                            "import sys; sys.path.insert(0, %r); from omarchy_link.cli import main; "
                            "main(['session','--help'])" % str(helpers.ROOT / "lib")],
                           capture_output=True, text=True, timeout=60, env=dict(os.environ, COLUMNS="100"))
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertIn("--daemon", p.stdout)

    def test_the_unit_example_in_the_readme_uses_the_flag(self):
        readme = (helpers.ROOT / "README.md").read_text()
        self.assertIn("omarchy-link session --daemon", readme)
