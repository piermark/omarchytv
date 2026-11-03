"""Cinemarchy on LG/Samsung browses the PC's media through the bridge: token, signed URLs, Range, WebVTT."""
import json, os, sys, tempfile, threading, unittest, urllib.error, urllib.request
from pathlib import Path
from unittest import mock
from http.server import ThreadingHTTPServer
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "lib"))
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "omarchy-link", "lib"))
from omarchy_tv import webos


class MediaBridgeTest(unittest.TestCase):
    def setUp(self):
        self.home = Path(tempfile.mkdtemp())
        (self.home / "Video").mkdir()
        (self.home / "Video/film.mkv").write_bytes(bytes(range(256)) * 4)
        (self.home / "Video/film.srt").write_text("1\n00:00:01,000 --> 00:00:02,000\nCiao\n")
        (self.home / "secret.mp4").write_bytes(b"x")
        self.env = mock.patch.dict(os.environ, {"OMARCHY_MEDIA_DIRS": str(self.home / "Video")})
        self.env.start()
        self.tok = mock.patch.object(webos, "bridge_token", return_value="ABCD1234")
        self.tok.start()
        self.srv = ThreadingHTTPServer(("127.0.0.1", 0), webos.WebOSHandler)
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()

    def tearDown(self):
        self.srv.shutdown()
        self.tok.stop()
        self.env.stop()

    def get(self, path, hdr=None):
        req = urllib.request.Request(f"http://127.0.0.1:{self.srv.server_port}{path}", headers=hdr or {})
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                return r.status, dict(r.headers), r.read()
        except urllib.error.HTTPError as e:
            return e.code, dict(e.headers), e.read()

    def test_list_needs_the_code_and_signs_every_file(self):
        self.assertEqual(self.get("/v1/media/list?path=Video")[0], 401)
        st, _, b = self.get("/v1/media/list?path=", {"X-Omarchy-Token": "ABCD1234"})
        self.assertEqual(st, 200)
        self.assertEqual(json.loads(b)["dirs"], [{"name": "Video", "path": "Video"}])
        st, _, b = self.get("/v1/media/list?path=Video", {"X-Omarchy-Token": "abcd1234"})
        f = json.loads(b)["files"][0]
        self.assertEqual(f["name"], "film.mkv")
        self.assertTrue(f["url"].startswith("/v1/media/f/") and f["subUrl"].startswith("/v1/media/s/"))
        st, h, body = self.get(f["url"])
        self.assertEqual((st, len(body), h["Accept-Ranges"], h["Access-Control-Allow-Origin"]), (200, 1024, "bytes", "*"))
        st, h, body = self.get(f["url"], {"Range": "bytes=256-511"})
        self.assertEqual((st, h["Content-Range"], body), (206, "bytes 256-511/1024", bytes(range(256))))
        st, h, body = self.get(f["url"], {"Range": "bytes=-10"})
        self.assertEqual((st, len(body)), (206, 10))
        self.assertEqual(self.get(f["url"], {"Range": "bytes=5000-"})[0], 416)
        st, h, body = self.get(f["subUrl"])
        self.assertTrue(body.startswith(b"WEBVTT") and b"00:00:01.000" in body and h["Content-Type"].startswith("text/vtt"))

    def test_forged_or_outside_paths_are_refused(self):
        st, _, b = self.get("/v1/media/list?path=Video", {"X-Omarchy-Token": "ABCD1234"})
        url = json.loads(b)["files"][0]["url"]
        self.assertEqual(self.get(url.replace("film.mkv", "film.srt"))[0], 403)   # a signature is for one path
        self.assertEqual(self.get("/v1/media/f/" + "0" * 32 + "/Video%2Ffilm.mkv")[0], 403)
        from omarchy_link import media
        rel = "Video/../secret.mp4"
        self.assertEqual(self.get(f"/v1/media/f/{media.sign('ABCD1234', rel)}/{rel}")[0], 404)   # signed, still outside


if __name__ == "__main__":
    unittest.main()
