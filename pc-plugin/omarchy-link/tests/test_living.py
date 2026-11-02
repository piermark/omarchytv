"""Living screensaver feed: notifications from D-Bus, GitHub events, X (notifications or API, with a budget)."""
import json
import time
import unittest
from unittest import mock

import helpers  # noqa: F401  (hermetic env + sys.path)
from omarchy_link import living, session

BLOCK = '''method call time=1730563200.1 sender=:1.45 -> destination=org.freedesktop.Notifications serial=7 path=/org/freedesktop/Notifications; interface=org.freedesktop.Notifications; member=Notify
   string "Slack"
   uint32 0
   string ""
   string "Ola"
   string "ci sei?
seconda <b>riga</b>"
   array [
   ]
   int32 -1'''.split("\n")


class Notes(unittest.TestCase):
    def test_parse_notify_multiline_and_markup(self):
        self.assertEqual(living.parse_notify(BLOCK), {"app": "Slack", "title": "Ola", "body": "ci sei? seconda riga"})
        self.assertIsNone(living.parse_notify(BLOCK[:3]))

    def test_x_from_browser_notification(self):
        n = {"app": "Chromium", "title": "X", "body": "@ana mentioned you: bella la TV x.com"}
        self.assertEqual(living.x_from_note(n)["kind"], "notify")
        self.assertIsNone(living.x_from_note({"app": "Chromium", "title": "GitHub", "body": "PR merged"}))
        self.assertIsNone(living.x_from_note({"app": "Slack", "title": "X", "body": "x.com link"}))

    def test_collector_keeps_what_the_tv_asked(self):
        lv = living.Living()
        lv.prefs = living.parse_prefs({"notes": False, "x": "notify", "github": False})
        lv._on_note({"app": "Slack", "title": "Ola", "body": "ciao"})
        lv._on_note({"app": "Chromium", "title": "X", "body": "@ana replied on x.com"})
        p = lv.payload()
        self.assertEqual((p["notes"], len(p["x"]), p["xMode"]), ([], 1, "notify"))
        lv.prefs = living.parse_prefs({"notes": True, "x": "off"})
        lv._on_note({"app": "Slack", "title": "Ola", "body": "ciao"})
        p = lv.payload()
        self.assertEqual((p["notes"][0]["title"], p["x"]), ("Ola", []))

    def test_prefs(self):
        self.assertEqual(living.parse_prefs(None), {"notes": False, "github": True, "x": "off", "radar": False})
        self.assertTrue(living.parse_prefs({"radar": True})["radar"])
        self.assertEqual(living.parse_prefs({"notes": "yes", "x": "scrape"})["x"], "off")


class GitHub(unittest.TestCase):
    def test_describe(self):
        push = {"type": "PushEvent", "repo": {"name": "piermark/omarchytv"}, "actor": {"login": "piermark"},
                "created_at": "2026-11-02T10:00:00Z",
                "payload": {"size": 2, "commits": [{"message": "a"}, {"message": "Boot screen: CRT\n\nbody"}]}}
        d = living.describe_event(push)
        self.assertEqual((d["what"], d["text"], d["repo"]), ("push 2", "Boot screen: CRT", "piermark/omarchytv"))
        star = {"type": "WatchEvent", "repo": {"name": "piermark/omarchytv"}, "actor": {"login": "fan"}, "payload": {}}
        self.assertEqual(living.describe_event(star)["text"], "fan")
        self.assertIsNone(living.describe_event({"type": "GollumEvent"}))

    def test_items_skip_bad_names(self):
        with mock.patch.object(living, "_gh_json", return_value=[]) as g, mock.patch.object(living.shutil, "which", return_value=None):
            living.github_items({"githubUser": "bad/../user", "githubRepos": ["ok/repo", "../etc"]})
        self.assertEqual([c[0][0] for c in g.call_args_list], ["repos/ok/repo/events?per_page=20"])


class XApi(unittest.TestCase):
    def setUp(self):
        try:
            living.X_FILE.unlink()
        except OSError:
            pass

    def test_not_set_up(self):
        self.assertEqual(living.x_mentions({}), ([], "x_not_set_up"))

    def test_mentions_since_id_and_spend(self):
        living.x_save({"bearer": "T", "username": "MarcoPorro"})
        calls = []

        def get(bearer, path, params):
            calls.append((path, dict(params)))
            if path.startswith("users/by/username"):
                return {"data": {"id": "42"}}
            return {"data": [{"id": "9", "text": "@MarcoPorro bella!", "author_id": "7", "created_at": "2026-11-02T10:00:00Z"}],
                    "includes": {"users": [{"id": "7", "username": "ana"}]}, "meta": {"newest_id": "9"}}
        items, err = living.x_mentions({}, get)
        self.assertEqual((err, items[0]["user"], items[0]["text"]), ("", "@ana", "@MarcoPorro bella!"))
        d = living.x_load()
        self.assertEqual((d["userId"], d["sinceMention"]), ("42", "9"))
        self.assertAlmostEqual(living.x_spent(d), 0.01 + 0.005 + 0.01)
        living.x_mentions({}, get)
        self.assertEqual(calls[-1][1]["since_id"], "9")
        self.assertEqual(len([c for c in calls if c[0].startswith("users/by")]), 1)
        self.assertEqual(living.x_mentions({"xMonthlyUsd": 0.01}, get), ([], "x_budget"))

    def test_token_file_is_private(self):
        living.x_save({"bearer": "T", "username": "u"})
        self.assertEqual(oct(living.X_FILE.stat().st_mode & 0o777), "0o600")


class SessionLiving(unittest.TestCase):
    def test_prefs_event_and_push(self):
        s = session.Session.__new__(session.Session)
        s.cfg, s.logs, s.state = {}, [], {"screensaver": True}
        s.log = s.logs.append
        s.stream = mock.Mock()
        with mock.patch.object(living.Living, "ensure"):
            s.handle_event({"type": "living_prefs", "data": {"notes": True, "github": False}})
            s.living._on_note({"app": "Slack", "title": "Ola", "body": "ciao"})
            s.push_living()
            s.push_living()
        self.assertEqual(s.stream.send.call_count, 1)
        kind, pub = s.stream.send.call_args[0]
        self.assertEqual((kind, pub["notes"][0]["title"]), ("living", "Ola"))
        self.assertTrue(s.living.active())


if __name__ == "__main__":
    unittest.main()
