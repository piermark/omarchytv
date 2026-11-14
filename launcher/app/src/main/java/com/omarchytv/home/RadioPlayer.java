package com.omarchytv.home;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaMetadata;
import android.view.KeyEvent;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.TeeAudioProcessor;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * v2.20 Radiomarchy: the one player behind the music app, the Home mini player, the media keys and the fractal
 * demo. It lives in the launcher's process (not in an activity), so the music goes on while you go back to the
 * Home screen, open an app row or let the screensaver come; {@link RadioService} keeps it in the foreground with
 * a notification, and a MediaSession takes the remote's play / pause / next keys and shows "now playing" on TVs
 * that have such a card. One song at a time (ExoPlayer, streamed from radio.omarchy.org); at the end of the list
 * it goes round again, like the site's deck. A tee in the audio sink feeds {@link AudioBands}, which is how the
 * fractals see the music without a microphone. Podcast episodes remember where you stopped. Main thread only,
 * except {@link #frame}, which the GL thread calls.
 */
@OptIn(markerClass = UnstableApi.class)
final class RadioPlayer {
    interface Listener {
        void onRadio();
    }

    static final int REPEAT_ALL = 0, REPEAT_ONE = 1, REPEAT_OFF = 2;
    private static RadioPlayer inst;

    private final Context app;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    final AudioBands bands = new AudioBands();
    private ExoPlayer player;
    private MediaSession session;
    private final List<RadioCatalog.Track> queue = new ArrayList<>();
    private final List<Integer> order = new ArrayList<>();   // play order (shuffled or not) of queue indices
    private int at = -1;                                       // index into order
    private boolean shuffle;
    private int repeat = REPEAT_ALL;
    private String error = "";
    // the play clock the GL thread reads (written on the main thread)
    private volatile long clockPosMs, clockAtMs;
    private volatile boolean clockRunning, hasAudio;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            syncClock();
            saveEpisode(false);
            if (player != null) h.postDelayed(this, 250);
        }
    };
    private long lastSave;

    static RadioPlayer get(Context c) {
        if (inst == null) inst = new RadioPlayer(c.getApplicationContext());
        return inst;
    }

    /** Is there one, and is it playing (or about to)? Never creates the player. */
    static boolean active() {
        return inst != null && inst.player != null && inst.player.getPlayWhenReady() && inst.current() != null;
    }

    static RadioPlayer peek() {
        return inst;
    }

    private RadioPlayer(Context app) {
        this.app = app;
        SharedPreferences p = prefs();
        shuffle = p.getBoolean("shuffle", true);   // v2.20: random by default, like the fractals
        repeat = p.getInt("repeat", REPEAT_ALL);
    }

    SharedPreferences prefs() {
        return app.getSharedPreferences("radiomarchy", Context.MODE_PRIVATE);
    }

    void addListener(Listener l) {
        listeners.addIfAbsent(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void changed() {
        syncClock();
        updateSession();
        for (Listener l : listeners) l.onRadio();
    }

    // ---- the player -----------------------------------------------------------------------------------

    private ExoPlayer player() {
        if (player != null) return player;
        TeeAudioProcessor tee = new TeeAudioProcessor(new TeeAudioProcessor.AudioBufferSink() {
            private int channels = 2, encoding = C.ENCODING_PCM_16BIT;
            private short[] s16 = new short[0];
            private float[] f32 = new float[0];

            @Override
            public void flush(int sampleRateHz, int channelCount, int enc) {
                channels = Math.max(1, channelCount);
                encoding = enc;
                bands.flush(sampleRateHz);
            }

            @Override
            public void handleBuffer(ByteBuffer buffer) {
                ByteBuffer b = buffer.duplicate().order(ByteOrder.nativeOrder());
                if (encoding == C.ENCODING_PCM_FLOAT) {
                    int n = b.remaining() / 4;
                    if (f32.length < n) f32 = new float[n];
                    b.asFloatBuffer().get(f32, 0, n);
                    bands.pcmFloat(f32, n / channels, channels);
                } else if (encoding == C.ENCODING_PCM_16BIT) {
                    int n = b.remaining() / 2;
                    if (s16.length < n) s16 = new short[n];
                    b.asShortBuffer().get(s16, 0, n);
                    bands.pcm16(s16, n / channels, channels);
                }
                hasAudio = true;
            }
        });
        DefaultRenderersFactory rf = new DefaultRenderersFactory(app) {
            @Override
            protected AudioSink buildAudioSink(Context c, boolean enableFloatOutput, boolean enablePlaybackParams) {
                return new DefaultAudioSink.Builder(c)
                        .setAudioProcessors(new AudioProcessor[]{tee})
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(enablePlaybackParams)
                        .build();
            }
        };
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory().setUserAgent("OmarchyTV-Radiomarchy")
                .setConnectTimeoutMs(10_000).setReadTimeoutMs(20_000).setAllowCrossProtocolRedirects(false);
        player = new ExoPlayer.Builder(app, rf)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(app, http)))
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int s) {
                if (s == Player.STATE_ENDED) {
                    saveEpisode(true);
                    if (repeat == REPEAT_ONE) start(0);
                    else if (!advance(1, false)) stopService();
                }
                changed();
            }

            @Override
            public void onIsPlayingChanged(boolean playing) {
                if (!playing) saveEpisode(true);
                changed();
            }

            @Override
            public void onPlayerError(PlaybackException e) {
                error = e.getErrorCodeName();
                changed();
                // a song that will not play: on to the next one after a moment (never a tight loop: at most the list once)
                if (++errorsInARow < Math.max(2, order.size())) h.postDelayed(() -> advance(1, false), 1500);
                else errorsInARow = 0;
            }
        });
        h.post(tick);
        return player;
    }

    private int errorsInARow;

    /** Play {@code list} from {@code index} (the list is copied). */
    void play(List<RadioCatalog.Track> list, int index) {
        if (list == null || list.isEmpty()) return;
        queue.clear();
        queue.addAll(list);
        index = Math.max(0, Math.min(index, list.size() - 1));
        buildOrder(index);
        at = 0;
        start(resumeAt(current()));
    }

    /** Add to the end of what is playing (or start it). */
    void enqueue(RadioCatalog.Track t) {
        if (t == null) return;
        if (queue.isEmpty()) {
            play(Collections.singletonList(t), 0);
            return;
        }
        queue.add(t);
        order.add(queue.size() - 1);
        changed();
    }

    private void buildOrder(int first) {
        order.clear();
        for (int i = 0; i < queue.size(); i++) if (i != first) order.add(i);
        if (shuffle) Collections.shuffle(order, new Random());
        order.add(0, first);
    }

    private void start(long fromMs) {
        RadioCatalog.Track t = current();
        if (t == null) return;
        error = "";
        ExoPlayer p = player();
        bands.startAt(fromMs * 1000);
        p.setMediaItem(new MediaItem.Builder().setUri(Uri.parse(t.url)).setMediaId(t.url).build(), Math.max(0, fromMs));
        p.prepare();
        p.setPlayWhenReady(true);
        startService();
        changed();
    }

    /** Next (+1) or previous (-1); false at the end of a list that does not go round. */
    boolean advance(int dir, boolean user) {
        if (order.isEmpty()) return false;
        if (dir < 0 && player != null && player.getCurrentPosition() > 4000) {   // "previous" first goes back to the start
            seekTo(0);
            return true;
        }
        int n = at + dir;
        if (n >= order.size() || n < 0) {
            if (repeat == REPEAT_OFF && !user) {
                if (player != null) player.setPlayWhenReady(false);
                return false;
            }
            n = (n + order.size()) % order.size();
        }
        if (dir > 0 && user) errorsInARow = 0;
        at = n;
        start(resumeAt(current()));
        return true;
    }

    void toggle() {
        if (current() == null) return;
        ExoPlayer p = player();
        if (p.getPlaybackState() == Player.STATE_IDLE || p.getPlaybackState() == Player.STATE_ENDED) {
            start(resumeAt(current()));
            return;
        }
        p.setPlayWhenReady(!p.getPlayWhenReady());
        if (p.getPlayWhenReady()) startService();
        changed();
    }

    void pause() {
        if (player != null) player.setPlayWhenReady(false);
    }

    void seekTo(long ms) {
        if (player == null) return;
        long d = duration();
        ms = Math.max(0, d > 0 ? Math.min(ms, d - 500) : ms);
        bands.startAt(ms * 1000);
        player.seekTo(ms);
        changed();
    }

    void seekBy(long ms) {
        seekTo(position() + ms);
    }

    /** Stop and let go of the player (the notification goes too). */
    void stop() {
        saveEpisode(true);
        if (player != null) {
            player.release();
            player = null;
        }
        hasAudio = false;
        stopService();
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        changed();
    }

    // ---- state ----------------------------------------------------------------------------------------

    RadioCatalog.Track current() {
        return at >= 0 && at < order.size() && order.get(at) < queue.size() ? queue.get(order.get(at)) : null;
    }

    /** What comes after the current one, in play order (at most {@code max}). */
    List<RadioCatalog.Track> upNext(int max) {
        List<RadioCatalog.Track> out = new ArrayList<>();
        for (int i = 1; i <= max && i < order.size(); i++) {
            int k = at + i;
            if (k >= order.size()) {
                if (repeat == REPEAT_OFF) break;
                k %= order.size();
            }
            out.add(queue.get(order.get(k)));
        }
        return out;
    }

    boolean playing() {
        return player != null && player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_ENDED;
    }

    boolean buffering() {
        return player != null && player.getPlaybackState() == Player.STATE_BUFFERING;
    }

    long position() {
        return player != null ? Math.max(0, player.getCurrentPosition()) : 0;
    }

    long duration() {
        long d = player != null ? player.getDuration() : C.TIME_UNSET;
        if (d == C.TIME_UNSET || d <= 0) {
            RadioCatalog.Track t = current();
            return t != null ? t.durationMs : 0;
        }
        return d;
    }

    String error() {
        return error;
    }

    boolean shuffle() {
        return shuffle;
    }

    int repeat() {
        return repeat;
    }

    void setShuffle(boolean on) {
        shuffle = on;
        prefs().edit().putBoolean("shuffle", on).apply();
        if (!order.isEmpty()) {
            int cur = order.get(at);
            buildOrder(cur);
            at = 0;
        }
        changed();
    }

    void cycleRepeat() {
        repeat = (repeat + 1) % 3;
        prefs().edit().putInt("repeat", repeat).apply();
        changed();
    }

    // ---- what the fractals read (GL thread) ------------------------------------------------------------

    private void syncClock() {
        if (player == null) {
            clockRunning = false;
            return;
        }
        clockPosMs = Math.max(0, player.getCurrentPosition());
        clockAtMs = SystemClock.elapsedRealtime();
        clockRunning = player.isPlaying();
    }

    /**
     * The music as it is heard right now; false (and a gentle make-believe pulse in {@code out}) when nothing is
     * playing. Any thread.
     */
    boolean frame(AudioBands.Frame out) {
        long now = SystemClock.elapsedRealtime();
        if (player != null && hasAudio && clockRunning) {
            long pos = clockPosMs + (now - clockAtMs);
            if (bands.at(pos * 1000, out)) {
                out.timeUs = pos * 1000;
                return true;
            }
        }
        AudioBands.idle(now * 1000, out);
        return false;
    }

    // ---- episodes remember where you stopped --------------------------------------------------------

    private long resumeAt(RadioCatalog.Track t) {
        if (t == null || !t.episode()) return 0;
        return Math.max(0, prefs().getLong("pos:" + t.url, 0) - 3000);
    }

    long savedPosition(RadioCatalog.Track t) {
        return t == null ? 0 : prefs().getLong("pos:" + t.url, 0);
    }

    private void saveEpisode(boolean now) {
        RadioCatalog.Track t = current();
        if (player == null || t == null || !t.episode()) return;
        long ms = SystemClock.elapsedRealtime();
        if (!now && ms - lastSave < 15000) return;
        lastSave = ms;
        long pos = player.getCurrentPosition(), d = duration();
        boolean done = player.getPlaybackState() == Player.STATE_ENDED || (d > 0 && pos > d - 30000);
        SharedPreferences.Editor e = prefs().edit();
        if (done) e.remove("pos:" + t.url);
        else if (pos > 10000) e.putLong("pos:" + t.url, pos);
        e.apply();
    }

    // ---- media keys, "now playing", foreground ---------------------------------------------------------

    private void updateSession() {
        RadioCatalog.Track t = current();
        if (t == null || player == null) return;
        if (session == null) {
            session = new MediaSession(app, "Radiomarchy");
            session.setCallback(new MediaSession.Callback() {
                @Override
                public void onPlay() {
                    if (!playing()) toggle();
                }

                @Override
                public void onPause() {
                    pause();
                }

                @Override
                public void onSkipToNext() {
                    advance(1, true);
                }

                @Override
                public void onSkipToPrevious() {
                    advance(-1, true);
                }

                @Override
                public void onSeekTo(long pos) {
                    seekTo(pos);
                }

                @Override
                public void onFastForward() {
                    seekBy(15000);
                }

                @Override
                public void onRewind() {
                    seekBy(-15000);
                }

                @Override
                public void onStop() {
                    stop();
                }
            });
            //noinspection deprecation
            session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            Intent open = new Intent(app, RadioActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int pi = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
            session.setSessionActivity(PendingIntent.getActivity(app, 7, open, pi));
            Intent mb = new Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(new ComponentName(app, RadioService.Buttons.class));
            session.setMediaButtonReceiver(PendingIntent.getBroadcast(app, 8, mb, pi));
        }
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, t.album.isEmpty() ? (t.episode() ? RadioCatalog.SHOW : "Omarchy Radio") : t.album)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, t.artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration())
                .build());
        int st = player.getPlaybackState() == Player.STATE_BUFFERING ? PlaybackState.STATE_BUFFERING
                : playing() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
                        | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND | PlaybackState.ACTION_STOP)
                .setState(st, position(), playing() ? 1f : 0f, SystemClock.elapsedRealtime())
                .build());
        session.setActive(true);
        RadioService.refresh(app);
    }

    MediaSession.Token token() {
        return session != null ? session.getSessionToken() : null;
    }

    /**
     * Keep the MediaSession as the media-button receiver after another app has taken audio focus
     * (YouTube etc.): ExoPlayer pauses with {@code handleAudioFocus=true}, but the session must stay
     * up so PLAY / PLAY_PAUSE on Home resume the song without opening Radiomarchy again.
     */
    void claimSession() {
        if (current() == null || player == null) return;
        updateSession();
        startService();
    }

    /** MEDIA_* keys: true if this player has a current track and handled the key. */
    static boolean isMediaKey(int kc) {
        switch (kc) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                return true;
            default:
                return false;
        }
    }

    boolean mediaKey(int kc) {
        if (current() == null) return false;
        switch (kc) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                toggle();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (!playing()) toggle();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                pause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                advance(1, true);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                advance(-1, true);
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seekBy(15000);
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seekBy(-15000);
                return true;
            default:
                return false;
        }
    }

    private void startService() {
        RadioService.start(app);
    }

    private void stopService() {
        RadioService.stop(app);
    }
}
