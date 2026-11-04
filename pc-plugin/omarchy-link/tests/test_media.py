"""Cinemarchy browses the PC's media folders: XDG roots, read-only, nothing outside them, TV events."""
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import helpers  # noqa: F401  (hermetic env + sys.path)
from omarchy_link import media, session


def make_home() -> Path:
    home = Path(tempfile.mkdtemp())
    (home / ".config").mkdir()
    (home / ".config/user-dirs.dirs").write_text('XDG_VIDEOS_DIR="$HOME/Video"\nXDG_DOWNLOAD_DIR="$HOME/Scaricati"\nXDG_MUSIC_DIR="$HOME"\n')
    (home / "Video/Serie").mkdir(parents=True)
    (home / "Scaricati").mkdir()
    (home / "secret").mkdir()
    (home / "Video/film.mkv").write_bytes(b"x" * 1000)
    (home / "Video/film.srt").write_text("1\n00:00:01,000 --> 00:00:02,000\nCiao\n")
    (home / "Video/.hidden.mp4").write_bytes(b"x")
    (home / "Video/notes.txt").write_text("x")
    (home / "secret/pw.mp4").write_bytes(b"x")
    os.symlink(home / "secret", home / "Video/escape")
    return home


class Roots(unittest.TestCase):
    def test_xdg_names_and_never_home(self):
        home = make_home()
        self.assertEqual([n for n, _ in media.roots({}, home)], ["Video", "Scaricati"])   # an unset XDG dir is $HOME: not shared
        self.assertEqual([n for n, _ in media.roots({"OMARCHY_MEDIA_DIRS": str(home / "Scaricati")}, home)], ["Scaricati"])

    def test_listing_media_only_with_subtitles(self):
        home = make_home()
        top = media.listing("", {}, home)
        self.assertEqual([d["path"] for d in top["dirs"]], ["Video", "Scaricati"])
        v = media.listing("Video", {}, home)
        self.assertEqual([d["name"] for d in v["dirs"]], ["Serie"])   # the link out of the folder is not shown
        self.assertEqual(v["files"], [{"name": "film.mkv", "path": "Video/film.mkv", "kind": "video", "size": 1000, "sub": "Video/film.srt"}])

    def test_nothing_outside(self):
        home = make_home()
        for bad in ("Video/escape/pw.mp4", "Video/../secret/pw.mp4", "/etc/passwd", "Video/.hidden.mp4", "secret/pw.mp4", ""):
            self.assertIsNone(media.resolve(bad, {}, home), bad)
        self.assertEqual(media.resolve("Video/film.mkv", {}, home), (home / "Video/film.mkv").resolve())
        self.assertEqual(media.listing("Video/escape", {}, home).get("error"), "not_found")

    def test_signatures_and_vtt(self):
        s = media.sign("ABC", "Video/film.mkv")
        self.assertTrue(media.verify("ABC", "Video/film.mkv", s))
        self.assertFalse(media.verify("ABC", "Video/film.srt", s))
        self.assertFalse(media.verify("", "Video/film.mkv", s))
        vtt = media.srt_to_vtt("﻿1\r\n00:00:01,000 --> 00:00:02,500\r\nCiao\r\n")
        self.assertTrue(vtt.startswith("WEBVTT\n\n1\n00:00:01.000 --> 00:00:02.500"))


class TvEvents(unittest.TestCase):
    def _session(self):
        s = session.Session.__new__(session.Session)
        s.cfg, s.log, s.stream, s.client = {}, lambda m: None, mock.Mock(), mock.Mock()
        return s

    def test_list_answers_and_can_be_off(self):
        s = self._session()
        with mock.patch.object(media, "listing", return_value={"path": "", "dirs": [], "files": []}) as li:
            s.handle_media_list({"path": "Video"})
        li.assert_called_once_with("Video")
        s.stream.send.assert_called_once_with("media_list", {"path": "", "dirs": [], "files": []})
        s.cfg = {"mediaFromTv": False}
        s.stream.reset_mock()
        s.handle_media_list({"path": "Video"})
        self.assertEqual(s.stream.send.call_args[0][1]["error"], "disabled")

    def test_play_streams_through_the_cast_path(self):
        home = make_home()
        s = self._session()
        real_resolve = media.resolve
        from omarchy_link import cast
        with mock.patch.object(media, "resolve", side_effect=lambda p, *a: real_resolve(p, {}, home)), \
             mock.patch.object(cast, "via_link") as via, mock.patch.object(session.threading, "Thread") as th:
            th.side_effect = lambda target, daemon: mock.Mock(start=target)
            s.handle_media_play({"path": "Video/film.mkv", "position": 42})
        item = via.call_args[0][1]
        self.assertEqual(item["path"], str((home / "Video/film.mkv").resolve()))
        self.assertEqual(item["position"], 42.0)
        self.assertTrue(via.call_args[1]["serve_ok"])
        with mock.patch.object(media, "resolve", return_value=None), mock.patch.object(cast, "via_link") as via2:
            s.handle_media_play({"path": "../etc/passwd"})
        via2.assert_not_called()
        self.assertEqual(s.stream.send.call_args[0][1]["error"], "not_found")


if __name__ == "__main__":
    unittest.main()
