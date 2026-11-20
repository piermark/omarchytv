package com.omarchytv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;

import java.util.List;

/**
 * v2.22 (#162) the Radio plugin's mini panel, from the bar pill or the radio widget: play / pause, next, previous,
 * volume, sleep timer, the station list and Radiomarchy itself, without leaving the Home. Plain dialogs (no fractals,
 * light on 2 GB TVs); the one player of {@link RadioPlayer} does the playing.
 */
final class RadioMini {
    private RadioMini() {
    }

    static void show(Activity a) {
        RadioPlayer p = RadioPlayer.get(a);
        RadioCatalog.Track t = p.current();
        String title = t == null ? "♪ Radio" : (p.playing() ? "♫ " : "‖ ") + t.title
                + (p.stationId() != null ? (p.icyTitle().isEmpty() ? "" : " — " + p.icyTitle()) : (t.artist.isEmpty() ? "" : " — " + t.artist));
        int sleep = p.sleepLeft();
        String[] items = {
                p.playing() ? "‖  pause" : "▶  play",
                "⏭  next",
                "⏮  previous",
                "🔉  −10  (" + p.volume() + "%)",
                "🔊  +10",
                "⏾  " + a.getString(R.string.radio_sleep) + (sleep > 0 ? " · " + a.getString(R.string.radio_sleep_left, sleep) : ""),
                "☰  " + a.getString(R.string.radio_stations),
                "♫  " + a.getString(R.string.radio_hub)};
        new AlertDialog.Builder(a).setTitle(title).setItems(items, (d, which) -> {
            boolean again = true;
            switch (which) {
                case 0:
                    if (t == null) p.playOrStart();
                    else p.toggle();
                    break;
                case 1: p.advance(1, true); break;
                case 2: p.advance(-1, true); break;
                case 3: p.setVolume(p.volume() - 10); break;
                case 4: p.setVolume(p.volume() + 10); break;
                case 5: p.cycleSleep(); break;
                case 6: stations(a); again = false; break;
                default:
                    a.startActivity(new Intent(a, RadioActivity.class));
                    again = false;
                    break;
            }
            if (again) a.getWindow().getDecorView().postDelayed(() -> show(a), 250);
        }).show();
    }

    static void stations(Activity a) {
        org.json.JSONObject radio = LauncherConfig.readJson(a).optJSONObject("radio");
        List<RadioStations.Station> all = RadioStations.list(radio);
        RadioPlayer p = RadioPlayer.get(a);
        String cur = p.stationId() != null ? p.stationId() : RadioStations.OMARCHY;
        String[] names = new String[all.size()];
        for (int i = 0; i < all.size(); i++) names[i] = (all.get(i).id.equals(cur) ? "● " : (all.get(i).fav ? "★ " : "   ")) + all.get(i).name;
        new AlertDialog.Builder(a).setTitle(R.string.radio_stations).setItems(names, (d, which) -> {
            p.playStation(all.get(which), radio != null && radio.optBoolean("allowHttp"));
            a.getWindow().getDecorView().postDelayed(() -> show(a), 600);
        }).show();
    }
}
