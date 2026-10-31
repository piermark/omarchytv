package com.omarchytv.home;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

public class YoutubeLaunchTest {
    @Test
    public void tvAppFirstWithBothUriFormsThenPhoneSmartTubeAndAnyHandler() {
        List<YoutubeLaunch.Step> s = YoutubeLaunch.appSteps("F7fe9pa8OeE", 0);
        assertEquals("com.google.android.youtube.tv https://www.youtube.com/watch?v=F7fe9pa8OeE", s.get(0).toString());
        assertEquals("com.google.android.youtube.tv vnd.youtube:F7fe9pa8OeE", s.get(1).toString());
        assertEquals("com.google.android.youtube", s.get(2).pkg);
        assertEquals("com.teamsmart.videomanager.tv", s.get(4).pkg);
        assertNull(s.get(s.size() - 1).pkg);
    }

    @Test
    public void startTimeAndBadIds() {
        assertEquals("https://www.youtube.com/watch?v=F7fe9pa8OeE&t=90s", YoutubeLaunch.appSteps("F7fe9pa8OeE", 90).get(0).uri);
        assertEquals("vnd.youtube:F7fe9pa8OeE?t=90", YoutubeLaunch.appSteps("F7fe9pa8OeE", 90).get(1).uri);
        assertTrue(YoutubeLaunch.appSteps("bad id", 0).isEmpty());
        assertTrue(YoutubeLaunch.appSteps(null, 0).isEmpty());
    }

    @Test
    public void idOfYoutubePagesOnly() {
        assertEquals("F7fe9pa8OeE", YoutubeLaunch.idOf("https://www.youtube.com/watch?v=F7fe9pa8OeE&t=3"));
        assertEquals("F7fe9pa8OeE", YoutubeLaunch.idOf("https://youtu.be/F7fe9pa8OeE"));
        assertEquals("F7fe9pa8OeE", YoutubeLaunch.idOf("vnd.youtube:F7fe9pa8OeE?t=1"));
        assertEquals("", YoutubeLaunch.idOf("https://www.youtube.com/tv"));
        assertEquals("", YoutubeLaunch.idOf("https://rr3---sn-x.googlevideo.com/videoplayback?id=o-AB&expire=1"));
        assertEquals("", YoutubeLaunch.idOf("https://example.com/watch?v=F7fe9pa8OeE"));
        assertEquals("", YoutubeLaunch.idOf(null));
    }

    @Test
    public void expiredGooglevideoStreamsAreNotRawErrors() {
        assertTrue(YoutubeLaunch.youtubeOrigin("https://rr3---sn-x.googlevideo.com/videoplayback?expire=1"));
        assertTrue(YoutubeLaunch.youtubeOrigin("https://m.youtube.com/watch?v=F7fe9pa8OeE"));
        assertFalse(YoutubeLaunch.youtubeOrigin("https://evilyoutube.com/x"));
        assertFalse(YoutubeLaunch.youtubeOrigin("https://commondatastorage.googleapis.com/a.mp4"));
        assertEquals("expired", YoutubeLaunch.onHttpError("https://rr3---sn-x.googlevideo.com/videoplayback", 403));
        assertEquals("app", YoutubeLaunch.onHttpError("https://www.youtube.com/watch?v=F7fe9pa8OeE", 410));
        assertEquals("refused", YoutubeLaunch.onHttpError("https://example.com/a.mp4", 403));
        assertEquals("refused", YoutubeLaunch.onHttpError("https://rr3---sn-x.googlevideo.com/videoplayback", 503));
    }
}
