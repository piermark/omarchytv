import os, sys, unittest
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "lib"))
from omarchy_tv import wireguard as wg


class TestWg(unittest.TestCase):
    def test_toggle(self):
        c = wg.toggle_cmd("1.2.3.4:5555", "home", True)
        self.assertEqual(c[:3], ["adb", "-s", "1.2.3.4:5555"])
        self.assertEqual(c[-2:], ["tunnel", "home"])

    def test_name(self):
        self.assertEqual(wg.tunnel_name("/x/wg0.conf"), "wg0")
        with self.assertRaises(ValueError):
            wg.tunnel_name("/x/this-name-is-too-long.conf")

    def test_push_warns_and_clean_removes_the_key_file(self):
        import tempfile
        from types import SimpleNamespace
        d = tempfile.mkdtemp()
        conf = os.path.join(d, "home.conf")
        with open(conf, "w") as f:
            f.write("[Interface]\nPrivateKey = x\nAddress = 10.0.0.2/32\n[Peer]\nPublicKey = y\nEndpoint = 1.2.3.4:51820\nAllowedIPs = 0.0.0.0/0\n")
        calls, said = [], []
        run = lambda c, **k: calls.append(c) or SimpleNamespace(returncode=0)
        self.assertEqual(wg.run(["push", conf], runner=run, say=said.append), 0)
        self.assertIn("wg clean home", " ".join(said))
        self.assertEqual(wg.run(["clean", "home", "--tv", "TV1"], runner=run, say=said.append), 0)
        self.assertEqual(calls[-1], ["adb", "-s", "TV1", "shell", "rm", "-f", "/sdcard/Download/home.conf"])
        self.assertEqual(wg.run(["clean", "../etc"], runner=run, say=said.append), 2)   # a name, never a path

    def test_usage(self):
        self.assertEqual(wg.run([], runner=None, say=lambda s: None), 2)


if __name__ == "__main__":
    unittest.main()
