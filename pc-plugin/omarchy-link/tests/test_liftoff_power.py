"""Liftoff (build/update countdown for the TV) and TV and PC power together."""
import io
import json
import sys
import time
import unittest
from unittest import mock

import helpers  # noqa: F401  (hermetic env + sys.path)
from omarchy_link import liftoff, power, session


class Liftoff(unittest.TestCase):
    def setUp(self):
        for f in (liftoff.STATE_FILE, liftoff.HISTORY_FILE):
            try:
                f.unlink()
            except OSError:
                pass

    def test_ok_build_is_remembered_for_the_next_countdown(self):
        out = io.StringIO()
        code = liftoff.run([sys.executable, "-c", "print('compiling'); print('done')"], "demo", out)
        self.assertEqual(code, 0)
        self.assertIn("compiling", out.getvalue())
        pub = liftoff.public(liftoff.read_state())
        self.assertEqual((pub["kind"], pub["name"], pub["state"], pub["pct"], pub["tail"]), ("build", "demo", "ok", 100, []))
        self.assertIsNotNone(liftoff.expected_for("demo"))
        liftoff.run([sys.executable, "-c", "pass"], "demo", io.StringIO())
        self.assertEqual(json.loads(liftoff.STATE_FILE.read_text())["expectedS"], 1)   # T-minus from the last run

    def test_failed_build_carries_the_last_lines_without_escapes(self):
        code = liftoff.run([sys.executable, "-c",
                            "import sys; print('\\x1b[31merror: boom\\x1b[0m'); sys.exit(3)"], None, io.StringIO())
        self.assertEqual(code, 3)
        pub = liftoff.public(liftoff.read_state())
        self.assertEqual((pub["state"], pub["code"]), ("failed", 3))
        self.assertEqual(pub["tail"], ["error: boom"])
        self.assertTrue(pub["name"].startswith("python"))

    def test_missing_command(self):
        self.assertEqual(liftoff.run(["/nonexistent/xyz"], "x", io.StringIO()), 127)
        self.assertEqual(liftoff.public(liftoff.read_state())["state"], "failed")

    def test_public_hides_old_and_invalid_and_dead(self):
        now = time.time()
        self.assertIsNone(liftoff.public({"kind": "build", "state": "ok", "startedAt": now - 500, "endedAt": now - 400}, now))
        self.assertIsNone(liftoff.public({"kind": "rm -rf", "state": "running"}, now))
        dead = liftoff.public({"kind": "build", "state": "running", "startedAt": now - 5, "pid": 2 ** 22 + 12345,
                               "updatedAt": now - 1}, now)
        self.assertEqual(dead["state"], "failed")


class Power(unittest.TestCase):
    def tearDown(self):
        power.tv_power("on", {})

    def test_standby_then_on_cancels(self):
        logs = []
        with mock.patch.object(power, "_suspend") as sus:
            self.assertEqual(power.tv_power("standby", {"powerGraceS": 10}, logs.append), "scheduled")
            self.assertTrue(power.pending())
            self.assertEqual(power.tv_power("standby", {}, logs.append), "scheduled")
            self.assertEqual(power.tv_power("on", {}, logs.append), "cancelled")
            self.assertFalse(power.pending())
            sus.assert_not_called()

    def test_disabled_and_junk(self):
        self.assertEqual(power.tv_power("standby", {"powerFromTv": False}), "disabled")
        self.assertEqual(power.tv_power("reboot", {}), "ignored")
        self.assertFalse(power.pending())

    def test_suspend_skipped_while_a_liftoff_runs(self):
        logs = []
        with mock.patch.object(power.liftoff, "public", return_value={"state": "running"}), \
             mock.patch.object(power.subprocess, "Popen") as po:
            power._suspend(logs.append)
            po.assert_not_called()
        with mock.patch.object(power.liftoff, "public", return_value=None), \
             mock.patch.object(power, "suspend_argv", return_value=["systemctl", "suspend"]), \
             mock.patch.object(power.subprocess, "Popen") as po:
            power._suspend(logs.append)
            self.assertEqual(po.call_args[0][0], ["systemctl", "suspend"])

    def test_wol_info(self):
        self.assertIsNone(power.wol_info("not an ip"))
        route = [{"dev": "eth0"}]
        addr = [{"addr_info": [{"family": "inet", "local": "192.168.1.20", "prefixlen": 24}]}]
        with mock.patch.object(power, "_ip_json", side_effect=[route, addr]), \
             mock.patch.object(power, "SYS_NET", helpers.TMP / "sysnet"):
            d = helpers.TMP / "sysnet/eth0"
            d.mkdir(parents=True, exist_ok=True)
            (d / "address").write_text("AA:BB:CC:00:11:22\n")
            self.assertEqual(power.wol_info("192.168.1.50"),
                             {"mac": "aa:bb:cc:00:11:22", "broadcast": "192.168.1.255", "wifi": False})


class SessionEvents(unittest.TestCase):
    def test_tv_power_and_liftoff_push(self):
        s = session.Session.__new__(session.Session)
        s.cfg, s.logs = {}, []
        s.log = s.logs.append
        s.stream = mock.Mock()
        with mock.patch.object(power, "tv_power") as tp:
            s.handle_event({"type": "tv_power", "data": {"state": "standby"}})
            tp.assert_called_once_with("standby", s.cfg, s.log)
        pub = {"id": "a", "state": "running", "stage": "x", "pct": None, "elapsedS": 3}
        with mock.patch.object(liftoff, "public", return_value=pub), mock.patch.object(liftoff, "read_state"):
            s.push_liftoff()
            s.push_liftoff()   # unchanged: not sent twice
        s.stream.send.assert_called_once_with("liftoff", pub)


if __name__ == "__main__":
    unittest.main()
