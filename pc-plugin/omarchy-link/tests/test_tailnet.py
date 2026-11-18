"""v2.21 Tailscale: the PC talks to tailnet TVs only when "tailscale": true, never to the internet."""
import json
import types
import unittest

import helpers  # noqa: F401  (hermetic env + sys.path)
from omarchy_link import config, netcheck, tailnet

STATUS = {
    "Self": {"HostName": "omarchy", "TailscaleIPs": ["100.100.1.1", "fd7a:115c:a1e0::1"]},
    "Peer": {
        "k1": {"HostName": "living-tv", "DNSName": "living-tv.tail1234.ts.net.", "TailscaleIPs": ["100.101.2.3"],
               "Online": True, "OS": "android"},
        "k2": {"HostName": "phone", "DNSName": "phone.tail1234.ts.net.", "TailscaleIPs": ["100.101.9.9"],
               "Online": True, "OS": "iOS"},
        "k3": {"HostName": "old-tv", "DNSName": "old-tv.tail1234.ts.net.", "TailscaleIPs": ["100.101.7.7"],
               "Online": False, "OS": "android"},
        "k4": {"HostName": "bad", "TailscaleIPs": ["8.8.8.8"], "Online": True},
    },
}


class TailnetTest(unittest.TestCase):
    def tearDown(self):
        netcheck.allow_tailnet = False

    def test_tailnet_needs_the_switch_and_skips_the_subnet_rule(self):
        self.assertTrue(netcheck.is_tailnet("100.101.2.3"))
        self.assertFalse(netcheck.is_tailnet("100.128.0.1"))
        self.assertFalse(netcheck.is_lan_address("100.101.2.3"))
        with self.assertRaises(ValueError) as e:
            netcheck.check_host("100.101.2.3")
        self.assertIn("omarchy-link tailscale on", str(e.exception))
        netcheck.allow_tailnet = True
        self.assertTrue(netcheck.is_lan_address("100.101.2.3"))
        netcheck.check_host("100.101.2.3", strict_subnet=True)   # a tailnet is flat: no same-subnet rule
        self.assertTrue(netcheck.is_lan_address("fd7a:115c:a1e0::5"))
        self.assertFalse(netcheck.is_lan_address("8.8.8.8"))
        with self.assertRaises(ValueError):
            netcheck.check_host("8.8.8.8")

    def test_config_sets_the_switch(self):
        config.save_config({"tailscale": True})
        config.load_config()
        self.assertTrue(netcheck.allow_tailnet)
        config.save_config({"tailscale": None})
        config.load_config()
        self.assertFalse(netcheck.allow_tailnet)

    def test_resolve_magicdns_names_from_status(self):
        self.assertEqual(tailnet.resolve("living-tv", STATUS), "100.101.2.3")
        self.assertEqual(tailnet.resolve("living-tv.tail1234.ts.net", STATUS), "100.101.2.3")
        self.assertEqual(tailnet.resolve("LIVING-TV.tail1234.ts.net.", STATUS), "100.101.2.3")
        self.assertIsNone(tailnet.resolve("bad", STATUS))   # not a tailnet address
        self.assertIsNone(tailnet.resolve("nope", STATUS))
        self.assertEqual(tailnet.self_address(STATUS), "100.100.1.1")

    def test_discover_probes_online_peers_and_keeps_only_omarchytv(self):
        seen = []

        def probe(host, port):
            seen.append(host)
            if host == "100.101.2.3":
                return {"service": "omarchytv-link", "protocol": 1, "tvId": "tv-1", "name": "Living", "pairingOpen": True}
            raise OSError("refused")

        tvs = tailnet.discover(STATUS, probe)
        self.assertEqual(sorted(seen), ["100.101.2.3", "100.101.9.9"])   # offline and non-tailnet peers skipped
        self.assertEqual(len(tvs), 1)
        self.assertEqual(tvs[0]["address"], "100.101.2.3")
        self.assertEqual(tvs[0]["txt"]["pair"], "1")
        self.assertEqual(tvs[0]["via"], "tailscale")

    def test_status_parses_the_cli(self):
        def run(argv, **kw):
            self.assertEqual(argv, ["tailscale", "status", "--json"])
            return types.SimpleNamespace(returncode=0, stdout=json.dumps(STATUS))
        orig = tailnet.shutil.which
        tailnet.shutil.which = lambda n: "/usr/bin/tailscale"
        try:
            self.assertEqual(tailnet.status(run)["Self"]["HostName"], "omarchy")
            self.assertIsNone(tailnet.status(lambda *a, **k: types.SimpleNamespace(returncode=1, stdout="")))
        finally:
            tailnet.shutil.which = orig


class PairNameTest(unittest.TestCase):
    def test_hex_looking_names_are_names(self):
        from omarchy_link import cli
        for name in ("cafe", "beef", "dead-beef", "abc", "living-tv.tail1234.ts.net"):
            self.assertFalse(cli._is_ip(name), name)
        for ip in ("100.101.2.3", "192.168.1.5", "fd7a:115c:a1e0::1", "[fd7a:115c:a1e0::1]"):
            self.assertTrue(cli._is_ip(ip), ip)


class HostPortTest(unittest.TestCase):
    def test_ipv4_ipv6_and_names(self):
        from omarchy_link import cli
        self.assertEqual(cli._host_port("192.168.1.5"), ("192.168.1.5", 47800))
        self.assertEqual(cli._host_port("192.168.1.5:5000"), ("192.168.1.5", 5000))
        self.assertEqual(cli._host_port("fd7a:115c:a1e0::1"), ("fd7a:115c:a1e0::1", 47800))
        self.assertEqual(cli._host_port("[fd7a:115c:a1e0::1]:47801"), ("fd7a:115c:a1e0::1", 47801))
        self.assertEqual(cli._host_port("[fd7a:115c:a1e0::1]"), ("fd7a:115c:a1e0::1", 47800))
        self.assertEqual(cli._host_port("cafe"), ("cafe", 47800))
        self.assertEqual(cli._host_port("living-tv:47800"), ("living-tv", 47800))


class AclTest(unittest.TestCase):
    def test_acl_example_is_narrow(self):
        a = tailnet.acl_example()
        self.assertEqual([r["dst"] for r in a["acls"]], [["tag:omarchytv:47800"]])


class RelayTest(unittest.TestCase):
    def test_relayed_only_without_a_direct_path(self):
        st = {"Peer": {"a": {"TailscaleIPs": ["100.101.2.3"], "Relay": "fra", "CurAddr": "", "Active": True},
                       "b": {"TailscaleIPs": ["100.101.2.4"], "Relay": "fra", "CurAddr": "192.0.2.7:41641", "Active": True},
                       "c": {"TailscaleIPs": ["100.101.2.5"], "Relay": "fra", "CurAddr": "", "Active": False}}}
        self.assertTrue(tailnet.relayed(st, "100.101.2.3"))
        self.assertFalse(tailnet.relayed(st, "100.101.2.4"))
        self.assertFalse(tailnet.relayed(st, "100.101.2.5"), "idle is not relayed")
        self.assertFalse(tailnet.relayed(st, "100.101.9.9"))
        self.assertFalse(tailnet.relayed(None, "100.101.2.3"))


if __name__ == "__main__":
    unittest.main()
