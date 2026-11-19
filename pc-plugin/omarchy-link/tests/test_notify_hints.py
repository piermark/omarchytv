"""v2.21 review: a 403 because the address is refused (not_lan) is not "agent alerts are off"."""
import argparse
import contextlib
import io
import unittest
from unittest import mock

import helpers  # noqa: F401  (hermetic env + sys.path)
from omarchy_link import cli, client as link


def ns(**k):
    base = dict(text=["build done"], title="", level="info", speak=False, quiet=False, source="PC", hint=False)
    base.update(k)
    return argparse.Namespace(**base)


class NotifyHintTest(unittest.TestCase):
    def run_notify(self, err, **k):
        pairing = mock.Mock(paired=True, tv="192.168.1.5:47800", pc_id="pc")
        c = mock.Mock()
        c.request.side_effect = err
        out = io.StringIO()
        with mock.patch.object(cli.config.Pairing, "load", return_value=pairing), \
                mock.patch.object(link, "Client", return_value=c), contextlib.redirect_stderr(out):
            rc = cli.cmd_notify(ns(**k), {})
        return rc, out.getvalue()

    def test_alerts_off_and_address_refused_are_told_apart(self):
        rc, msg = self.run_notify(link.LinkError("forbidden", 403, "notify_off"))
        self.assertEqual(rc, 4)
        self.assertIn("agent alerts", msg)
        rc, msg = self.run_notify(link.LinkError("forbidden", 403, "not_lan"))
        self.assertEqual(rc, 1)
        self.assertIn("Tailscale", msg)
        self.assertNotIn("agent alerts", msg)

    def test_watch_hint_names_the_right_cause(self):
        rc, msg = self.run_notify(link.LinkError("forbidden", 403, "not_tailnet"), quiet=True, hint=True)
        self.assertEqual(rc, 0)
        self.assertIn("Tailscale", msg)
        rc, msg = self.run_notify(link.LinkError("forbidden", 403, ""), quiet=True, hint=True)
        self.assertIn("agent alerts", msg)


if __name__ == "__main__":
    unittest.main()
