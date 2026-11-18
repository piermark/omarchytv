"""v2.21 Cloudflare Access JWT checks for omarchy-tv mcp --cloudflare."""
import base64
import json
import unittest

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding, rsa

from omarchy_tv import cf_access, mcp_server

AUD = "a" * 64


def b64(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


class Keys:
    def __init__(self):
        self.priv = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        n = self.priv.public_key().public_numbers()
        self.jwks = {"keys": [{"kty": "RSA", "kid": "k1", "n": b64(n.n.to_bytes(256, "big")), "e": b64(n.e.to_bytes(3, "big"))}]}

    def jwt(self, claims, kid="k1", alg="RS256", key=None):
        h = b64(json.dumps({"alg": alg, "kid": kid}).encode())
        p = b64(json.dumps(claims).encode())
        sig = (key or self.priv).sign(f"{h}.{p}".encode(), padding.PKCS1v15(), hashes.SHA256())
        return f"{h}.{p}.{b64(sig)}"


class AccessTest(unittest.TestCase):
    def setUp(self):
        self.k = Keys()
        self.fetched = []
        self.v = cf_access.AccessVerifier("myteam", AUD, fetch=lambda u: self.fetched.append(u) or self.k.jwks, now=lambda: 1000)
        self.good = {"aud": [AUD], "iss": "https://myteam.cloudflareaccess.com", "exp": 2000, "nbf": 900}

    def test_good_token(self):
        self.assertIsNone(self.v.check(self.k.jwt(self.good)))
        self.assertEqual(self.fetched, ["https://myteam.cloudflareaccess.com/cdn-cgi/access/certs"])

    def test_refusals(self):
        self.assertEqual(self.v.check(self.k.jwt(dict(self.good, aud=["b" * 64]))), "audience")
        self.assertEqual(self.v.check(self.k.jwt(dict(self.good, iss="https://evil.cloudflareaccess.com"))), "issuer")
        self.assertEqual(self.v.check(self.k.jwt(dict(self.good, exp=10))), "expired")
        self.assertEqual(self.v.check(self.k.jwt(self.good, kid="other")), "unknown key")
        other = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        self.assertEqual(self.v.check(self.k.jwt(self.good, key=other)), "signature")
        self.assertEqual(self.v.check("not.a.jwt"), "malformed")
        self.assertEqual(self.v.check(self.k.jwt(self.good, alg="HS256")), "alg")

    def test_bad_settings(self):
        with self.assertRaises(ValueError):
            cf_access.AccessVerifier("my team!", AUD)
        with self.assertRaises(ValueError):
            cf_access.AccessVerifier("myteam", "short")


class ServerTest(unittest.TestCase):
    def test_server_refuses_a_bad_access_token(self):
        import http.client, tempfile, threading
        from pathlib import Path

        class V:
            def check(self, t):
                return None if t == "good" else "signature"

        with tempfile.TemporaryDirectory() as tmp:
            tok = mcp_server.ensure_token(Path(tmp) / "t")
            srv = mcp_server.create_http_server(lambda: None, tok, "127.0.0.1", 0, scope="read", require_access=True, verifier=V())
            threading.Thread(target=srv.serve_forever, daemon=True).start()
            try:
                for jwt, want in (("bad", 403), ("good", 200)):
                    c = http.client.HTTPConnection("127.0.0.1", srv.server_address[1], timeout=5)
                    c.request("POST", "/mcp", body=b'{"jsonrpc":"2.0","id":1,"method":"ping"}',
                              headers={"Authorization": f"Bearer {tok}", "Cf-Access-Jwt-Assertion": jwt})
                    self.assertEqual(c.getresponse().status, want)
                    c.close()
            finally:
                srv.shutdown()


class HelpersTest(unittest.TestCase):
    def test_setup_commands(self):
        c = mcp_server.setup_commands("omarchytv-mcp", "tv-mcp.example.com")
        self.assertEqual(c[0], ["cloudflared", "tunnel", "create", "omarchytv-mcp"])
        self.assertEqual(c[1][-2:], ["omarchytv-mcp", "tv-mcp.example.com"])
        for bad in (("x;rm", "a.example.com"), ("ok", "no_dots"), ("ok", "evil.com; rm -rf")):
            with self.assertRaises(ValueError):
                mcp_server.setup_commands(*bad)

    def test_service_unit(self):
        u = mcp_server.service_unit(["/usr/bin/omarchy-tv", "mcp", "--cloudflare", "n", "--access-aud", AUD])
        self.assertIn("ExecStart=/usr/bin/omarchy-tv mcp --cloudflare n --access-aud " + AUD, u)
        self.assertIn("Restart=on-failure", u)


if __name__ == "__main__":
    unittest.main()
