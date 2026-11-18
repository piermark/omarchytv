"""v2.21 Cloudflare Access in front of `omarchy-tv mcp --cloudflare`: verify the Cf-Access-Jwt-Assertion header.

Cloudflare signs that JWT (RS256) with the team's keys, published at https://<team>.cloudflareaccess.com/cdn-cgi/access/certs.
With --access-team and --access-aud the server checks the signature, the audience (the Access application's AUD tag),
the issuer and the expiry; without them, --access only requires the header (the origin is 127.0.0.1 and only
cloudflared reaches it). Needs python-cryptography for the signature (already optional for omarchy-link).
"""

from __future__ import annotations

import base64
import json
import re
import threading
import time
import urllib.request

TEAM = re.compile(r"^[a-z0-9-]{1,63}$")


def _b64(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


class AccessVerifier:
    def __init__(self, team: str, aud: str, fetch=None, now=time.time):
        if not TEAM.match(team or ""):
            raise ValueError("--access-team: the team name, e.g. myteam (from myteam.cloudflareaccess.com)")
        if not re.fullmatch(r"[0-9a-f]{64}", aud or ""):
            raise ValueError("--access-aud: the Application Audience (AUD) tag, 64 hex characters")
        self.issuer = f"https://{team}.cloudflareaccess.com"
        self.aud = aud
        self._fetch = fetch or self._http
        self._now = now
        self._keys: dict = {}
        self._at = 0.0
        self._lock = threading.Lock()

    def _http(self, url: str) -> dict:
        with urllib.request.urlopen(url, timeout=8) as r:
            return json.loads(r.read(256 * 1024))

    def _key(self, kid: str):
        with self._lock:
            if kid not in self._keys or self._now() - self._at > 3600:
                data = self._fetch(self.issuer + "/cdn-cgi/access/certs")
                from cryptography.hazmat.primitives.asymmetric.rsa import RSAPublicNumbers
                keys = {}
                for k in data.get("keys", []):
                    if k.get("kty") == "RSA" and k.get("kid"):
                        n = int.from_bytes(_b64(k["n"]), "big")
                        e = int.from_bytes(_b64(k["e"]), "big")
                        keys[k["kid"]] = RSAPublicNumbers(e, n).public_key()
                self._keys, self._at = keys, self._now()
            return self._keys.get(kid)

    def check(self, token: str) -> str | None:
        """None when the JWT is good, else the reason."""
        try:
            h64, p64, s64 = token.split(".")
            head, claims = json.loads(_b64(h64)), json.loads(_b64(p64))
        except (ValueError, json.JSONDecodeError):
            return "malformed"
        if head.get("alg") != "RS256":
            return "alg"
        key = self._key(str(head.get("kid", "")))
        if key is None:
            return "unknown key"
        from cryptography.exceptions import InvalidSignature
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.asymmetric import padding
        try:
            key.verify(_b64(s64), f"{h64}.{p64}".encode(), padding.PKCS1v15(), hashes.SHA256())
        except InvalidSignature:
            return "signature"
        aud = claims.get("aud")
        if self.aud not in (aud if isinstance(aud, list) else [aud]):
            return "audience"
        if claims.get("iss") != self.issuer:
            return "issuer"
        now = self._now()
        if not isinstance(claims.get("exp"), (int, float)) or claims["exp"] < now - 30:
            return "expired"
        if isinstance(claims.get("nbf"), (int, float)) and claims["nbf"] > now + 30:
            return "not yet valid"
        return None
