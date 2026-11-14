import json, os, sys, tempfile, unittest
from unittest import mock
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "lib"))
from omarchy_tv import plugins as p

# synthetic feed in the SpaceX TV cache shape (not copied from upstream)
SPACEX = {
    "starship_playlist": {"media": [{"title": "Film\x07 One", "fhdLink": "https://cdn.example/f1.mp4"},
                                    {"title": "Bad", "fhdLink": "http://cdn.example/insecure.mp4"}]},
    "processed_cards": {"entries": {
        "post:1": {"fingerprint": "id:1|t:Liftoff! https://t.co/x\nmore|at:1", "contentKind": "video",
                   "streamURL": "https://v.example/1.mp4"},
        "post:2": {"fingerprint": "id:2|t:Photo|at:2", "contentKind": "photo", "streamURL": "https://v.example/2.jpg"}}},
}


class TestPlugins(unittest.TestCase):
    def test_spacex_adapter_filters_and_cleans(self):
        its = p.items_spacex(SPACEX)
        self.assertEqual([i["url"] for i in its], ["https://cdn.example/f1.mp4", "https://v.example/1.mp4"])
        self.assertNotIn("\x07", its[0]["title"])
        self.assertEqual(its[1]["title"], "Liftoff!")

    def test_media_list_https_only(self):
        its = p.items_media_list({"items": [{"title": "a", "url": "https://x/a"}, {"title": "b", "url": "file:///etc/passwd"}]})
        self.assertEqual(len(its), 1)

    def test_manifest_needs_credits_and_https(self):
        good = {"id": "my-feed", "name": "N", "author": "A", "license": "MIT",
                "feed": {"url": "https://x/f.json", "format": "media-list"}}
        p.validate_manifest(good)
        for bad in ({**good, "author": ""}, {**good, "feed": {"url": "http://x", "format": "media-list"}},
                    {**good, "id": "../evil"}, {**good, "feed": {"url": "https://x", "format": "python"}}):
            with self.assertRaises(ValueError):
                p.validate_manifest(bad)

    def test_fetch_refuses_http(self):
        with self.assertRaises(ValueError):
            p.fetch_json("http://example.com/feed.json")

    def test_user_plugin_loaded_and_played(self):
        with tempfile.TemporaryDirectory() as d:
            os.makedirs(os.path.join(d, "omarchy-tv", "plugins"))
            m = {"id": "mine", "name": "Mine", "author": "Me", "license": "CC-BY-4.0",
                 "feed": {"url": "https://x/f.json", "format": "media-list"}}
            with open(os.path.join(d, "omarchy-tv", "plugins", "mine.json"), "w") as f:
                json.dump(m, f)
            with mock.patch.dict(os.environ, {"XDG_CONFIG_HOME": d}):
                self.assertIn("mine", p.load_all())
                sent = []
                rc = p.run(["play", "mine", "1"], client=None, say=lambda s: None,
                           fetch=lambda u: {"items": [{"title": "Clip", "url": "https://x/c.mp4"}]},
                           send_run=lambda a, c: sent.append(a) or 0)
        self.assertEqual(rc, 0)
        self.assertEqual(sent[0], ["https://x/c.mp4", "--title", "Clip"])

    def test_list_shows_credit(self):
        out = []
        p.run(["list"], client=None, say=out.append)
        self.assertTrue(any("sighmon" in l and "MIT" in l for l in out))



    def test_builtin_catalog_and_enable_disable_sync(self):
        with tempfile.TemporaryDirectory() as d:
            with mock.patch.dict(os.environ, {"XDG_CONFIG_HOME": d}):
                all_p = p.load_all()
                self.assertIn("com.sighmon.spacex-tv", all_p)
                self.assertIn("io.github.tcballard.grokbot-desktop", all_p)
                en = p.load_enabled()
                self.assertFalse(en["com.sighmon.spacex-tv"])  # 2.20: off until enabled
                self.assertTrue(en["io.github.tcballard.grokbot-desktop"])
                out = []
                p.run(["list"], client=None, say=out.append)
                self.assertTrue(any("com.sighmon.spacex-tv" in l and "sighmon" in l for l in out))
                self.assertTrue(any("io.github.tcballard.grokbot-desktop" in l and "tcballard" in l for l in out))

                # disable spacex-tv via alias
                pushed = []
                class FakeClient:
                    def request(self, m, path, body=None):
                        pushed.append((m, path, body))
                        return {"ok": True}
                p.run(["disable", "spacex-tv"], client=FakeClient(), say=lambda s: None)
                self.assertFalse(p.load_enabled()["com.sighmon.spacex-tv"])
                self.assertEqual(pushed[-1][1], "/v1/plugins")
                self.assertFalse(pushed[-1][2]["spacexTv"])

                # enable back and sync
                p.run(["enable", "com.sighmon.spacex-tv"], client=FakeClient(), say=lambda s: None)
                self.assertTrue(p.load_enabled()["com.sighmon.spacex-tv"])
                p.run(["sync"], client=FakeClient(), say=lambda s: None)
                self.assertTrue(pushed[-1][2]["spacexTv"])
                self.assertTrue(pushed[-1][2]["grokbot"])

                # grokbot companion message
                p.run(["grokbot", "Hello", "from", "PC"], client=FakeClient(), say=lambda s: None)
                self.assertEqual(pushed[-1][1], "/v1/plugins/grokbot")
                self.assertEqual(pushed[-1][2]["message"], "Hello from PC")


if __name__ == "__main__":
    unittest.main()
