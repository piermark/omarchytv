/* Cinemarchy has its own version, independent of the OmarchyTV app version. */
var CINEMARCHY_VERSION = "0.1.0-alpha";
(function () {
  "use strict";

  // One shared app: LG webOS and the Samsung widget share one version number.
  var APP_VERSION = "2.13.11";   // keep in step with webos/app/appinfo.json and tizen/config.xml (checked by tests)

  var DATA = window.OMARCHY_DATA || { version: APP_VERSION, themes: {}, wallpapers: {}, logo: "OMARCHY TV", notice: "" };
  var START_TIME = Date.now(); // real OmarchyTV uptime, no made-up offset (issue #5, point 10)

  var STRINGS = {
    en: {
      menu_plugins: "Plugins", menu_apps: "Apps", menu_setup: "Setup", menu_terminal: "Terminal", menu_hint: "↑↓ select   OK open   Back close",
      tip_cinemarchy: "Cinemarchy (Media & TV)", tip_wifi: "Wi-Fi & network", tip_bt: "Bluetooth", tip_vol: "Audio", tip_inputs: "Inputs (TV / HDMI)", tip_saver: "Start screensaver",
      sys_clock: "Clock",
      box_weather: "weather", box_pc: "omarchy · pc", box_ai: "ai credits · pc", box_tv: "tv", box_app: "app", box_ws_pc: "pc", box_system: "system",
      term_theme: "theme ", term_net: "net ", term_up: "up ",
      wp_title: "Wallpaper", wp_sub: "Any theme's background. A picked one stays when you change theme.",
      wp_follow: "Follow the theme", wp_plain: "Theme colours, no image",
      pc_not_connected: "pc not connected",
      hint_tv: "OK watch TV · ▲ bar · long OK: Walker",
      hint_app: "OK open · ▲ bar · long OK: Move / Hide",
      hint_ws2: "OK switch input · ▲ bar · 1-5 workspaces",
      hint_ws3: "OK open · long OK: Move / Hide · 1-5 workspaces",
      ws4_heading: "Connect Omarchy Linux",
      ws4_desc: "Pair the PC over LAN HTTP (http://<pc-ip>:8765/v1/status) to see live CPU/RAM, AI credits and deploys on LG webOS.",
      sys_webos: "webOS TV settings", sys_themes: "Themes", sys_trans: "Transparency", sys_widgets: "Home widgets",
      sys_arrange: "Arrange panels", sys_saver: "Screensaver", sys_text_size: "Text size", ts_small: "Small", ts_normal: "Normal", ts_large: "Large", ts_xlarge: "Extra large", sys_saver_idle: "Start screensaver after",
      walker_none: "no matching apps",
      sys_logo_anim: "Animated logo", sys_text_tiles: "Text tiles (terminal style)", sys_all: "All OmarchyTV settings", sys_about: "About & licences",
      tile_move: "Move", tile_hide: "Hide", tile_cancel: "Cancel", move_hint: "◀▶▲▼ move · OK save · Back cancel",
      app_hidden: "Hidden. Show it again in All settings › Hidden apps", hidden_apps: "Hidden apps", hidden_none: "No hidden apps",
      hidden_sub: "OK shows the app again", hidden_show: "show", hidden_show_all: "Show all hidden apps", hidden_all_shown: "All apps visible again", random_theme: "Random theme", random_wallpaper: "Random wallpaper / intro", sys_plugins: "Plugins (SpaceX TV & Grok Bot)", reset_order: "Reset app order", order_reset: "App order reset",
      wx_title: "Weather", wx_auto: "Automatic (from IP)", wx_auto_fail: "Couldn't find your location, keeping the current city", wx_search: "Search city…", wx_unit: "Temperature unit",
      root_boot: "Open when the TV starts", root_home: "Home button opens OmarchyTV", root_needed: "rooted TV only",
      root_keep: "Keep loaded in the background", root_keep_na: "not on this TV",
      root_missing: "Needs a rooted TV with Homebrew Channel (unrooted: see homewatch/ in the repo)", root_failed: "Could not change it: ",
      wx_min: "min ", wx_max: "max ",
      theme_to_pc: "Also change the PC theme", theme_not_on_pc: "Not on the PC: only the TV changed",
      boot_screen: "Boot screen",
      boot_mode_boot: "at start",
      boot_mode_wake: "start and wake",
      pc_power: "PC sleeps with the TV",
      pc_power_note: "When the TV turns off, the PC suspends. Waking the PC with the TV needs Android TV.",
      update_via_pc: "Update via PC",
      update_checking: "Checking for updates…",
      update_none: "OmarchyTV %s is the latest version",
      update_confirm: "Install OmarchyTV %s with the PC? The app restarts.",
      update_need_pc: "Pair the Omarchy PC and set the PC ask code first",
      update_failed: "Update failed: %s",
      liftoff_build: "build",
      liftoff_update: "update",
      liftoff_ok: "LIFTOFF",
      liftoff_abort: "ABORT",
      liftoff_hint: "any key: hide",
      update_install: "Install",
      living: "Live screensaver",
      living_notes: "PC notifications on the screensaver",
      living_notes_note: "Every notification from the Omarchy PC will show on the TV while the screensaver runs",
      living_x: "X on the screensaver",
      living_x_notify: "PC notifications",
      living_x_api: "X API",
      living_x_api_note: "Mentions via the X API (X charges per use): set it up on the PC with omarchy-link x setup",
      living_weather: "weather",
      living_notes_title: "notifications · pc",
      living_x_title: "x · mentions",
      living_x_not_set_up: "X API not set up: omarchy-link x setup on the PC",
      living_x_budget: "X API monthly limit reached (xMonthlyUsd)",
      living_x_error: "X API not reachable right now",
      omafire_campfire: "campfire",
      omafire_embers: "embers",
      plugin_retro_crt: "Retro CRT",
      crt_light: "light",
      crt_strong: "strong",
      liftoff_title: "liftoff",
      liftoff_stage_download: "downloading",
      liftoff_stage_verify: "checking",
      liftoff_stage_sign: "signing",
      liftoff_stage_install: "installing",
      liftoff_stage_launch: "starting",
      liftoff_exit: "exit %s",
      living_waiting: "waiting for the Omarchy PC…",
      boot_sound: "Boot sound",
      omafire_sound: "OmaFire sound",
      arcade_sound: "Arcade sound", arcade_pc_theme: "Arcade: RICE WARS changes the PC theme too", share_qr: "OmarchyTV QR code",
      cine_sources: "sources",
      cine_library: "library",
      cine_details: "details",
      cine_src_dhh: "@dhh37 · Omarchy",
      cine_src_stream: "Streaming apps",
      cine_src_live: "TV & inputs",
      cine_src_iptv: "IPTV playlists",
      cine_src_demo: "Open movies",
      cine_src_queue: "Up next",
      cine_src_tools: "Player & tools",
      cine_keys: "▲▼ move · ◀▶ panes · OK play · red: up next · green: ★ · yellow: group · blue: 🎲 · 1-8 sources · Back",
      cine_refresh: "OK refreshes the channel feed",
      cine_loading: "loading…",
      cine_new: "NEW",
      cine_none: "No apps found on this TV",
      cine_add_playlist: "+ Add an M3U playlist (URL)",
      cine_playlist_hint: "only streams you have the right to watch",
      cine_remove_hint: "red removes it",
      cine_group: "Group: %s",
      cine_group_hint: "OK or yellow: next group (all, ★, …)",
      cine_live: "LIVE",
      cine_demo_note: "Blender open movie · CC BY · demo stream",
      cine_queue_empty: "Up next is empty: press red on any video",
      cine_queue_clear: "Clear Up next",
      cine_embed: "Play @dhh37 inside Cinemarchy",
      cine_saver_test: "Preview the pause screensaver",
      cine_add_link: "+ Add a video link to Up next",
      cine_bad_url: "That isn't an http(s) link",
      cine_queued: "Added to Up next",
      cine_resume: "Resumes at %s",
      cine_play: "play",
      cine_fav_hint: "green: ★ favourite",
      cine_queue_hint: "red: add to Up next",
      cine_reload: "reload",
      cine_open: "open",
      cine_back: "Back cancels",
      cine_no_play: "This stream can't be played on this TV",
      cine_player_keys: "OK pause · ◀▶ 10 s · ▲▼ prev/next · blue: VHS/ASCII · yellow: sleep · Back stop",
      cine_fav_added: "★ Added to favourites",
      cine_fav_removed: "Removed from favourites",
      cine_ascii_blocked: "This stream doesn't allow ASCII mode",
      cine_sleep: "Sleep in %s",
      cine_sleep_off: "Sleep timer off",
      cine_sleep_done: "Sleep timer: stopped",
      cine_mode_normal: "Normal picture",
      cine_roulette: "Surprise channel",
      cine_roulette_hint: "a random channel from your playlists",
      cine_src_pc: "Omarchy PC",
      cine_pc_folders: "Browse the PC's folders",
      cine_pc_opening: "Opening from the PC…",
      cine_pc_need: "Pair the Omarchy PC first",
      cine_up: "‹ back",
      cine_pc_empty: "Nothing to play in this folder",
      wmo: { 0: "clear", 1: "mostly clear", 2: "partly cloudy", 3: "overcast", 45: "fog", 51: "drizzle", 61: "rain", 71: "snow", 80: "showers", 95: "thunderstorm" }
    },
    it: {
      menu_plugins: "Plugin", menu_apps: "App", menu_setup: "Impostazioni", menu_terminal: "Terminale", menu_hint: "↑↓ seleziona   OK apri   Indietro chiudi",
      tip_cinemarchy: "Cinemarchy (Media e TV)", tip_wifi: "Wi-Fi e rete", tip_bt: "Bluetooth", tip_vol: "Audio", tip_inputs: "Ingressi (TV / HDMI)", tip_saver: "Avvia salvaschermo",
      sys_clock: "Orologio",
      box_weather: "meteo", box_pc: "omarchy · pc", box_ai: "crediti ia · pc", box_tv: "tv", box_app: "app", box_ws_pc: "pc", box_system: "sistema",
      term_theme: "tema ", term_net: "rete ", term_up: "up ",
      wp_title: "Sfondo", wp_sub: "Lo sfondo di qualsiasi tema. Quello scelto resta anche cambiando tema.",
      wp_follow: "Segui il tema", wp_plain: "Colori del tema, senza immagine",
      pc_not_connected: "pc non connesso",
      hint_tv: "OK guarda TV · ▲ barra · OK lungo: Walker",
      hint_app: "OK apri · ▲ barra · OK lungo: Sposta / Nascondi",
      hint_ws2: "OK cambia ingresso · ▲ barra · 1-5 spazi",
      hint_ws3: "OK apri · OK lungo: Sposta / Nascondi · 1-5 spazi",
      ws4_heading: "Collega Omarchy Linux",
      ws4_desc: "Abbina il PC via HTTP LAN (http://<ip-pc>:8765/v1/status) per vedere stato, crediti IA e deploy qui.",
      sys_webos: "Impostazioni TV webOS", sys_themes: "Temi", sys_trans: "Trasparenza", sys_widgets: "Widget della Home",
      sys_arrange: "Disponi pannelli", sys_saver: "Salvaschermo", sys_text_size: "Dimensione testo", ts_small: "Piccolo", ts_normal: "Normale", ts_large: "Grande", ts_xlarge: "Molto grande", sys_saver_idle: "Avvia salvaschermo dopo",
      walker_none: "nessuna app trovata",
      sys_logo_anim: "Logo animato", sys_text_tiles: "Tile solo testo (stile terminale)", sys_all: "Tutte le impostazioni OmarchyTV", sys_about: "Info e licenze",
      tile_move: "Sposta", tile_hide: "Nascondi", tile_cancel: "Annulla", move_hint: "◀▶▲▼ sposta · OK salva · Indietro annulla",
      app_hidden: "Nascosta. Mostrala di nuovo in Tutte le impostazioni › App nascoste", hidden_apps: "App nascoste", hidden_none: "Nessuna app nascosta",
      hidden_sub: "OK mostra di nuovo l'app", hidden_show: "mostra", hidden_show_all: "Mostra tutte le app nascoste", hidden_all_shown: "Tutte le app sono di nuovo visibili", random_theme: "Tema casuale", random_wallpaper: "Sfondo / intro casuale", sys_plugins: "Plugin (SpaceX TV e Grok Bot)", reset_order: "Ripristina ordine app", order_reset: "Ordine app ripristinato",
      wx_title: "Meteo", wx_auto: "Automatica (da IP)", wx_auto_fail: "Posizione non trovata, resta la città attuale", wx_search: "Cerca città…", wx_unit: "Unità temperatura",
      root_boot: "Apri all'avvio della TV", root_home: "Il tasto Home apre OmarchyTV", root_needed: "solo TV con root",
      root_keep: "Tieni caricata in background", root_keep_na: "non su questa TV",
      root_missing: "Serve una TV con root e Homebrew Channel (senza root: vedi homewatch/ nel repo)", root_failed: "Impossibile cambiarlo: ",
      wx_min: "min ", wx_max: "max ",
      theme_to_pc: "Cambia anche il tema del PC", theme_not_on_pc: "Non presente sul PC: è cambiata solo la TV",
      boot_screen: "Schermata di avvio",
      boot_mode_boot: "all'avvio",
      boot_mode_wake: "avvio e risveglio",
      pc_power: "Il PC dorme con la TV",
      pc_power_note: "Quando spegni la TV, il PC va in sospensione. Per risvegliare il PC con la TV serve Android TV.",
      update_via_pc: "Aggiorna tramite PC",
      update_checking: "Cerco aggiornamenti…",
      update_none: "OmarchyTV %s è l'ultima versione",
      update_confirm: "Installare OmarchyTV %s tramite il PC? L'app si riavvia.",
      update_need_pc: "Prima collega il PC Omarchy e imposta il codice del PC",
      update_failed: "Aggiornamento non riuscito: %s",
      liftoff_build: "build",
      liftoff_update: "aggiornamento",
      liftoff_ok: "DECOLLO",
      liftoff_abort: "ANNULLATO",
      liftoff_hint: "un tasto qualsiasi: nascondi",
      update_install: "Installa",
      living: "Salvaschermo Live",
      living_notes: "Notifiche del PC nel salvaschermo",
      living_notes_note: "Tutte le notifiche del PC Omarchy appariranno sulla TV mentre è attivo il salvaschermo",
      living_x: "X nel salvaschermo",
      living_x_notify: "notifiche del PC",
      living_x_api: "API di X",
      living_x_api_note: "Menzioni tramite l'API di X (X fa pagare a consumo): configurala sul PC con omarchy-link x setup",
      living_weather: "meteo",
      living_notes_title: "notifiche · pc",
      living_x_title: "x · menzioni",
      living_x_not_set_up: "API di X non configurata: omarchy-link x setup sul PC",
      living_x_budget: "Raggiunto il limite mensile dell'API di X (xMonthlyUsd)",
      living_x_error: "API di X non raggiungibile ora",
      omafire_campfire: "falò",
      omafire_embers: "braci",
      plugin_retro_crt: "Retro CRT",
      crt_light: "leggero",
      crt_strong: "forte",
      liftoff_title: "decollo",
      liftoff_stage_download: "download",
      liftoff_stage_verify: "verifica",
      liftoff_stage_sign: "firma",
      liftoff_stage_install: "installazione",
      liftoff_stage_launch: "avvio",
      liftoff_exit: "uscita %s",
      living_waiting: "in attesa del PC Omarchy…",
      boot_sound: "Suono all'avvio",
      omafire_sound: "Suono di OmaFire",
      arcade_sound: "Suoni della sala giochi", arcade_pc_theme: "Sala giochi: RICE WARS cambia tema anche al PC", share_qr: "QR code di OmarchyTV",
      cine_sources: "sorgenti",
      cine_library: "libreria",
      cine_details: "dettagli",
      cine_src_dhh: "@dhh37 · Omarchy",
      cine_src_stream: "App di streaming",
      cine_src_live: "TV e ingressi",
      cine_src_iptv: "Playlist IPTV",
      cine_src_demo: "Film aperti",
      cine_src_queue: "Prossimi",
      cine_src_tools: "Lettore e strumenti",
      cine_keys: "▲▼ muovi · ◀▶ pannelli · OK guarda · rosso: prossimi · verde: ★ · giallo: gruppo · blu: 🎲 · 1-8 sorgenti · Indietro",
      cine_refresh: "OK aggiorna il canale",
      cine_loading: "caricamento…",
      cine_new: "NUOVO",
      cine_none: "Nessuna app trovata su questa TV",
      cine_add_playlist: "+ Aggiungi una playlist M3U (URL)",
      cine_playlist_hint: "solo flussi che hai il diritto di guardare",
      cine_remove_hint: "il rosso la toglie",
      cine_group: "Gruppo: %s",
      cine_group_hint: "OK o giallo: gruppo successivo (tutti, ★, …)",
      cine_live: "LIVE",
      cine_demo_note: "Film aperto Blender · CC BY · flusso demo",
      cine_queue_empty: "Prossimi è vuoto: premi rosso su un video",
      cine_queue_clear: "Svuota Prossimi",
      cine_embed: "Riproduci @dhh37 dentro Cinemarchy",
      cine_saver_test: "Anteprima del salvaschermo di pausa",
      cine_add_link: "+ Aggiungi un link video ai Prossimi",
      cine_bad_url: "Non è un link http(s)",
      cine_queued: "Aggiunto ai Prossimi",
      cine_resume: "Riprende da %s",
      cine_play: "guarda",
      cine_fav_hint: "verde: ★ preferito",
      cine_queue_hint: "rosso: aggiungi ai Prossimi",
      cine_reload: "ricarica",
      cine_open: "apri",
      cine_back: "Indietro annulla",
      cine_no_play: "Questo flusso non si riproduce su questa TV",
      cine_player_keys: "OK pausa · ◀▶ 10 s · ▲▼ prec/succ · blu: VHS/ASCII · giallo: timer · Indietro stop",
      cine_fav_added: "★ Aggiunto ai preferiti",
      cine_fav_removed: "Tolto dai preferiti",
      cine_ascii_blocked: "Questo flusso non permette la modalità ASCII",
      cine_sleep: "Spegnimento tra %s",
      cine_sleep_off: "Timer spento",
      cine_sleep_done: "Timer: riproduzione fermata",
      cine_mode_normal: "Immagine normale",
      cine_roulette: "Canale a sorpresa",
      cine_roulette_hint: "un canale a caso dalle tue playlist",
      cine_src_pc: "PC Omarchy",
      cine_pc_folders: "Sfoglia le cartelle del PC",
      cine_pc_opening: "Apro dal PC…",
      cine_pc_need: "Abbina prima il PC Omarchy",
      cine_up: "‹ indietro",
      cine_pc_empty: "Niente da riprodurre in questa cartella",
      wmo: { 0: "sereno", 1: "poco nuvoloso", 2: "parz. nuvoloso", 3: "coperto", 45: "nebbia", 51: "pioviggine", 61: "pioggia", 71: "neve", 80: "rovesci", 95: "temporale" }
    },
    de: {
      menu_plugins: "Plugins", menu_apps: "Apps", menu_setup: "Einrichtung", menu_terminal: "Terminal", menu_hint: "↑↓ auswählen   OK öffnen   Zurück schließen",
      tip_cinemarchy: "Cinemarchy (Medien & TV)", tip_wifi: "WLAN & Netzwerk", tip_bt: "Bluetooth", tip_vol: "Audio", tip_inputs: "Eingänge (TV / HDMI)", tip_saver: "Bildschirmschoner starten",
      sys_clock: "Uhr",
      box_weather: "wetter", box_pc: "omarchy · pc", box_ai: "ki-guthaben · pc", box_tv: "tv", box_app: "app", box_ws_pc: "pc", box_system: "system",
      term_theme: "thema ", term_net: "netz ", term_up: "up ",
      wp_title: "Hintergrund", wp_sub: "Der Hintergrund eines beliebigen Themas. Ein gewählter bleibt beim Themawechsel.",
      wp_follow: "Dem Thema folgen", wp_plain: "Themafarben, kein Bild",
      pc_not_connected: "pc nicht verbunden",
      hint_tv: "OK TV ansehen · ▲ Leiste · lang OK: Walker",
      hint_app: "OK öffnen · ▲ Leiste · lang OK: Verschieben / Ausblenden",
      hint_ws2: "OK Eingang wechseln · ▲ Leiste · 1-5 Arbeitsbereiche",
      hint_ws3: "OK öffnen · lang OK: Verschieben / Ausblenden · 1-5 Arbeitsbereiche",
      ws4_heading: "Omarchy Linux verbinden",
      ws4_desc: "Koppeln Sie den PC über LAN HTTP (http://<pc-ip>:8765/v1/status), um Status, KI-Guthaben und Deploys anzuzeigen.",
      sys_webos: "webOS TV-Einstellungen", sys_themes: "Themen", sys_trans: "Transparenz", sys_widgets: "Startseiten-Widgets",
      sys_arrange: "Panels anordnen", sys_saver: "Bildschirmschoner", sys_text_size: "Textgröße", ts_small: "Klein", ts_normal: "Normal", ts_large: "Groß", ts_xlarge: "Sehr groß", sys_saver_idle: "Bildschirmschoner starten nach",
      walker_none: "keine passende App",
      sys_logo_anim: "Animiertes Logo", sys_text_tiles: "Text-Kacheln (Terminal-Stil)", sys_all: "Alle OmarchyTV-Einstellungen", sys_about: "Info & Lizenzen",
      tile_move: "Verschieben", tile_hide: "Ausblenden", tile_cancel: "Abbrechen", move_hint: "◀▶▲▼ verschieben · OK speichern · Zurück abbrechen",
      app_hidden: "Ausgeblendet. Wieder einblenden unter Alle Einstellungen › Ausgeblendete Apps", hidden_apps: "Ausgeblendete Apps", hidden_none: "Keine ausgeblendeten Apps",
      hidden_sub: "OK blendet die App wieder ein", hidden_show: "einblenden", hidden_show_all: "Alle ausgeblendeten Apps einblenden", hidden_all_shown: "Alle Apps wieder sichtbar", random_theme: "Zufälliges Thema", random_wallpaper: "Zufälliges Hintergrundbild / Intro", sys_plugins: "Plugins (SpaceX TV & Grok Bot)", reset_order: "App-Reihenfolge zurücksetzen", order_reset: "App-Reihenfolge zurückgesetzt",
      wx_title: "Wetter", wx_auto: "Automatisch (über IP)", wx_auto_fail: "Standort nicht gefunden, die aktuelle Stadt bleibt", wx_search: "Stadt suchen…", wx_unit: "Temperatureinheit",
      root_boot: "Beim Einschalten öffnen", root_home: "Home-Taste öffnet OmarchyTV", root_needed: "nur gerootete TVs",
      root_keep: "Im Hintergrund geladen lassen", root_keep_na: "nicht auf diesem TV",
      root_missing: "Benötigt einen gerooteten TV mit Homebrew Channel (ohne Root: siehe homewatch/ im Repo)", root_failed: "Konnte nicht geändert werden: ",
      wx_min: "Min ", wx_max: "Max ",
      theme_to_pc: "Auch das PC-Theme ändern", theme_not_on_pc: "Nicht auf dem PC: nur der Fernseher wurde geändert",
      boot_screen: "Startbildschirm",
      boot_mode_boot: "beim Start",
      boot_mode_wake: "Start und Aufwachen",
      pc_power: "PC schläft mit dem TV",
      pc_power_note: "Wenn der TV ausgeht, geht der PC in den Ruhezustand. Den PC mit dem TV aufwecken geht nur mit Android TV.",
      update_via_pc: "Über den PC aktualisieren",
      update_checking: "Suche nach Updates…",
      update_none: "OmarchyTV %s ist die neueste Version",
      update_confirm: "OmarchyTV %s über den PC installieren? Die App startet neu.",
      update_need_pc: "Zuerst den Omarchy-PC koppeln und den PC-Code eingeben",
      update_failed: "Update fehlgeschlagen: %s",
      liftoff_build: "Build",
      liftoff_update: "Update",
      liftoff_ok: "ABHEBEN",
      liftoff_abort: "ABBRUCH",
      liftoff_hint: "beliebige Taste: ausblenden",
      update_install: "Installieren",
      living: "Live-Bildschirmschoner",
      living_notes: "PC-Benachrichtigungen im Bildschirmschoner",
      living_notes_note: "Alle Benachrichtigungen des Omarchy-PCs erscheinen auf dem TV, solange der Bildschirmschoner läuft",
      living_x: "X im Bildschirmschoner",
      living_x_notify: "PC-Benachrichtigungen",
      living_x_api: "X-API",
      living_x_api_note: "Erwähnungen über die X-API (X berechnet pro Nutzung): auf dem PC mit omarchy-link x setup einrichten",
      living_weather: "Wetter",
      living_notes_title: "Benachrichtigungen · PC",
      living_x_title: "x · Erwähnungen",
      living_x_not_set_up: "X-API nicht eingerichtet: omarchy-link x setup auf dem PC",
      living_x_budget: "Monatslimit der X-API erreicht (xMonthlyUsd)",
      living_x_error: "X-API gerade nicht erreichbar",
      omafire_campfire: "Lagerfeuer",
      omafire_embers: "Glut",
      plugin_retro_crt: "Retro CRT",
      crt_light: "leicht",
      crt_strong: "stark",
      liftoff_title: "Start",
      liftoff_stage_download: "Download",
      liftoff_stage_verify: "Prüfung",
      liftoff_stage_sign: "Signierung",
      liftoff_stage_install: "Installation",
      liftoff_stage_launch: "Start der App",
      liftoff_exit: "Exit %s",
      living_waiting: "warte auf den Omarchy-PC…",
      boot_sound: "Startton",
      omafire_sound: "OmaFire-Ton",
      arcade_sound: "Spielhallen-Sound", arcade_pc_theme: "Spielhalle: RICE WARS ändert auch das PC-Theme", share_qr: "OmarchyTV-QR-Code",
      cine_sources: "Quellen",
      cine_library: "Mediathek",
      cine_details: "Details",
      cine_src_dhh: "@dhh37 · Omarchy",
      cine_src_stream: "Streaming-Apps",
      cine_src_live: "TV & Eingänge",
      cine_src_iptv: "IPTV-Playlists",
      cine_src_demo: "Offene Filme",
      cine_src_queue: "Als Nächstes",
      cine_src_tools: "Player & Werkzeuge",
      cine_keys: "▲▼ bewegen · ◀▶ Bereiche · OK abspielen · Rot: als Nächstes · Grün: ★ · Gelb: Gruppe · Blau: 🎲 · 1-8 Quellen · Zurück",
      cine_refresh: "OK aktualisiert den Kanal",
      cine_loading: "lädt…",
      cine_new: "NEU",
      cine_none: "Keine Apps auf diesem TV gefunden",
      cine_add_playlist: "+ M3U-Playlist hinzufügen (URL)",
      cine_playlist_hint: "nur Streams, die du sehen darfst",
      cine_remove_hint: "Rot entfernt sie",
      cine_group: "Gruppe: %s",
      cine_group_hint: "OK oder Gelb: nächste Gruppe (alle, ★, …)",
      cine_live: "LIVE",
      cine_demo_note: "Blender Open Movie · CC BY · Demo-Stream",
      cine_queue_empty: "Als Nächstes ist leer: Rot auf einem Video drücken",
      cine_queue_clear: "Als Nächstes leeren",
      cine_embed: "@dhh37 in Cinemarchy abspielen",
      cine_saver_test: "Vorschau des Pausen-Bildschirmschoners",
      cine_add_link: "+ Videolink zu Als Nächstes hinzufügen",
      cine_bad_url: "Das ist kein http(s)-Link",
      cine_queued: "Zu Als Nächstes hinzugefügt",
      cine_resume: "Weiter bei %s",
      cine_play: "abspielen",
      cine_fav_hint: "Grün: ★ Favorit",
      cine_queue_hint: "Rot: zu Als Nächstes",
      cine_reload: "neu laden",
      cine_open: "öffnen",
      cine_back: "Zurück bricht ab",
      cine_no_play: "Dieser Stream läuft auf diesem TV nicht",
      cine_player_keys: "OK Pause · ◀▶ 10 s · ▲▼ zurück/weiter · Blau: VHS/ASCII · Gelb: Timer · Zurück Stopp",
      cine_fav_added: "★ Zu Favoriten hinzugefügt",
      cine_fav_removed: "Aus Favoriten entfernt",
      cine_ascii_blocked: "Dieser Stream erlaubt keinen ASCII-Modus",
      cine_sleep: "Ausschalten in %s",
      cine_sleep_off: "Timer aus",
      cine_sleep_done: "Timer: angehalten",
      cine_mode_normal: "Normales Bild",
      cine_roulette: "Überraschungskanal",
      cine_roulette_hint: "ein zufälliger Kanal aus deinen Playlists",
      cine_src_pc: "Omarchy-PC",
      cine_pc_folders: "Ordner des PCs durchsuchen",
      cine_pc_opening: "Wird vom PC geöffnet…",
      cine_pc_need: "Koppele zuerst den Omarchy-PC",
      cine_up: "‹ zurück",
      cine_pc_empty: "Nichts Abspielbares in diesem Ordner",
      wmo: { 0: "klar", 1: "überwiegend klar", 2: "teilweise bewölkt", 3: "bedeckt", 45: "Nebel", 51: "Nieselregen", 61: "Regen", 71: "Schnee", 80: "Schauer", 95: "Gewitter" }
    }
  };
  // fr, es, pt-PT, pt-BR, nl, pl, sv, da, cs, ro, el, ru: i18n.js (generated with the Android strings), lang.js rules.
  var I18N = window.OMARCHY_I18N || {};
  var LANG = window.OmarchyLang;
  for (var lc in I18N) if (Object.prototype.hasOwnProperty.call(I18N, lc)) STRINGS[lc] = I18N[lc].strings;

  var DEFAULT_CFG = {
    theme: "last-horizon",
    randomTheme: true,
    randomWallpaper: true,
    pluginSpacexTv: true,
    embedPlayer: true,     // Cinemarchy: YouTube plays inside (official embed) by default; an explicit false stays off
    pluginGrokbot: true,   // the Grok Bot companion: on by default, the default assistant
    grokbotMessage: "",
    companionShape: "",    // "" follows the PC bot; else one of Tom's 14 shapes
    companionColor: "",    // "" follows the PC bot; else one of his 11 colours
    companionSize: "medium",
    companionBadge: true,
    companionAnim: true,
    companionAgent: "grokbot",
    companionBot: "",
    grokbotCode: "",
    lang: "",
    opacity: 0.75,
    wallpaperDim: 0,
    wallpaperIdx: 0,
    wallpaper: "",        // "" follows the theme, "plain" = theme colours, else a path from DATA.wallpapers
    proceduralBg: false,
    tempUnit: "C",
    weatherCity: "Alessandria",
    weatherLat: 44.913,
    weatherLon: 8.615,
    logoAnim: true,
    textTiles: false,
    tvEnabled: true,
    textScale: 1,         // launcher text size: 0.9 / 1 (default) / 1.15 / 1.3, CSS var --ts
    saverIdleMin: 2.5,
    saverMode: "aquarium",
    saverSpeed: "normal",
    saverArt: "aquarium",
    saverSound: true,
    saverLogoScale: 0.65,
    saverShowClock: false,
    wallpaperIntro: true,
    wallpaperLoop: false,   // v2.14 "Always-animated wallpaper": loop the intro while Home is on screen
    living: false,          // v2.14 Living screensaver: weather, GitHub, PC notifications, X in ASCII boxes (living.js)
    livingNotes: false,     // ...with every notification from the Omarchy PC (POST /v1/living)
    livingX: "off",         // ..."off" | "notify" (X notifications shown on the PC, free) | "api" (X API on the PC, paid)
    livingGithub: true,
    livingRadar: false,     // ...Omastorm's radar in the weather box (a snapshot of the PC's Omastorm window every 90 s)
    bootSound: true,        // v2.14 boot screen sound (synthesised: CRT power-on, POST beep, disk, keys, wordmark)
    omafireSound: false,    // v2.14 OmaFire wallpaper sound (Wes Grimes' fire loops, log/stoke, night ambience)
    arcadeSound: true,      // v2.14 OmarchyTV Arcade sound effects (synthesised; a game is opened on purpose)
    shareQr: "live",        // v2.14 QR code of OmarchyTV: "off", "live" (small, in the Live screensaver), "boot" (boot screen too)
    arcadePcTheme: false,   // v2.14 RICE WARS re-themes the Omarchy PC per cleared level (its own theme comes back on exit)
    pluginRetroCrt: "off",  // v2.14 Retro CRT plugin: "off" | "light" | "strong" (scanlines, mask, vignette, theme bezel)
    pcPower: false,         // v2.14 LG: the PC suspends when the TV turns off (POST /v1/power; needs TV control approved)
    themeToPc: false,       // v2.14 reverse theme sync: a theme picked here also switches the PC (POST /v1/theme/set)
    bootScreen: "boot",     // v2.14 boot screen (boot.js): "boot" at app start, "wake" also after standby, "off"
    bootFx: true,           // v2.14 boot screen scanlines and glow
    antiBurnIn: true,
    offIdleMin: 0,
    offMode: "screen",
    promptUser: "user",
    promptHost: "omarchytv",
    pcHost: "",
    pcSimulated: false,
    aiCredits: "percent",
    clockFormat: "auto",   // "auto" (12 h in English, 24 h otherwise), "12" or "24"
    widgets: { term: true, weather: true, pc: true, ai: true, deploy: false },
    tvApps: true,         // LG webOS: read the apps installed on the TV over SSAP (tvapps.js), DEFAULT_APPS stay the fallback
    hiddenApps: [],
    appOrder: []   // app ids in the user's order; apps not listed keep their DEFAULT_APPS position after them
  };

  var cfg = Object.assign({}, DEFAULT_CFG);
  try {
    var saved = localStorage.getItem("omarchytv_cfg_2110");
    if (saved) cfg = Object.assign({}, DEFAULT_CFG, JSON.parse(saved));
  } catch (e) {}

  function saveCfg() {
    try { localStorage.setItem("omarchytv_cfg_2110", JSON.stringify(cfg)); } catch (e) {}
  }

  function getLang() {
    var has = function (l) { return !!STRINGS[l]; };
    if (LANG) return LANG.resolve(cfg.lang, navigator.language, has);
    if (cfg.lang && STRINGS[cfg.lang]) return cfg.lang;
    var nav = (navigator.language || "en").slice(0, 2).toLowerCase();
    return STRINGS[nav] ? nav : "en";
  }
  function speechLang() { return LANG ? LANG.speechTag(getLang()) : "en-US"; }
  function askLang() { return LANG ? LANG.askTag(getLang()) : "en"; }

  function tr(key) {
    var l = getLang();
    return (STRINGS[l] && STRINGS[l][key]) || STRINGS.en[key] || key;
  }

  var DEFAULT_APPS = [
    { id: "tv", name: "TV", appId: "com.webos.app.livetv", brand: "tv" },
    { id: "disney", name: "Disney+", appId: "com.disney.disneyplus-prod", brand: "disney", bg: "radial-gradient(circle at 50% 35%, #113ccf 0%, #050b24 85%)", fg: "#ffffff", sub: "DISNEY+" },
    { id: "netflix", name: "Netflix", appId: "netflix", brand: "netflix", bg: "linear-gradient(135deg, #1b0000 0%, #090909 100%)", fg: "#e50914", sub: "NETFLIX" },
    { id: "plex", name: "Plex", appId: "cdp-30", brand: "plex", bg: "linear-gradient(135deg, #1f2326 0%, #121417 100%)", fg: "#e5a00d", sub: "plex" },
    { id: "prime", name: "Prime Video", appId: "amazon", brand: "prime", bg: "linear-gradient(135deg, #00273d 0%, #05111a 100%)", fg: "#00a8e1", sub: "prime video" },
    { id: "spotify", name: "Spotify", appId: "spotify-beehive", brand: "spotify", bg: "linear-gradient(135deg, #0c2916 0%, #080d0a 100%)", fg: "#1db954", sub: "Spotify" },
    { id: "youtube", name: "YouTube", appId: "youtube.leanback.v4", brand: "youtube", bg: "linear-gradient(135deg, #2b0707 0%, #0f0f0f 100%)", fg: "#ff0033", sub: "YouTube" },
    { id: "jellyfin", name: "Jellyfin", appId: "org.jellyfin.webos", brand: "jellyfin", bg: "linear-gradient(135deg, #1b112c 0%, #0b0914 100%)", fg: "#aa5cc3", sub: "Jellyfin" },
    { id: "moonlight", name: "Moonlight", appId: "com.limelight.webos", brand: "moonlight", bg: "linear-gradient(135deg, #1b2432 0%, #0d1117 100%)", fg: "#cdd9e5", sub: "Moonlight" },
    { id: "raiplay", name: "RaiPlay", appId: "it.rai.raiplay", brand: "raiplay", bg: "linear-gradient(135deg, #001c44 0%, #000b1e 100%)", fg: "#ffffff", sub: "RaiPlay" },
    { id: "browser", name: "Web Browser", appId: "com.webos.app.browser", brand: "browser", bg: "linear-gradient(135deg, #1e293b 0%, #0f172a 100%)", fg: "#38bdf8", sub: "WWW" },
    { id: "settings", name: "LG Settings", appId: "com.palm.app.settings", brand: "settings", bg: "linear-gradient(135deg, #27272a 0%, #18181b 100%)", fg: "#f43f5e", sub: "webOS" }
  ];

  // Samsung Tizen build: tizen-shim.js discovers the installed apps and hands them over as
  // window.OMARCHY_TIZEN_APPS and/or the "omarchy-tizen-apps" event (detail = the same array), items
  // { id, name, tizenId, appId?, brand?, iconPath?, iconPaths? }. A valid list replaces the LG apps above; brand
  // styling is reused from the matching LG tile. Empty or invalid lists are ignored so the dock never empties.
  // iconPaths are the installed app's icon candidates in order (store cache, then its own icon); iconPath is the
  // first one, for older adapters. Only local paths are kept.
  var LG_APPS = DEFAULT_APPS;
  function tizenIconPath(p) {
    if (typeof p !== "string" || p.length > 1024 || /[\x00-\x1f"'<>\\]|\/\.\.(\/|$)/.test(p)) return undefined;
    return /^(file:\/\/)?\/[^\/]/.test(p) ? p : undefined;
  }
  function tizenAppList(list) {
    if (!list || typeof list.length !== "number") return null;
    var out = [], seen = {};
    for (var i = 0; i < list.length; i++) {
      var a = list[i];
      if (!a || typeof a.id !== "string" || !a.id || typeof a.name !== "string" || !a.name ||
          typeof a.tizenId !== "string" || !a.tizenId || seen[a.id]) continue;
      seen[a.id] = true;
      var brand = typeof a.brand === "string" && a.brand ? a.brand : a.id;
      var look = LG_APPS.filter(function (x) { return x.brand === brand; })[0] || {};
      var icons = [];
      [a.iconPath].concat(Array.isArray(a.iconPaths) ? a.iconPaths.slice(0, 4) : []).forEach(function (p) {
        p = tizenIconPath(p);
        if (p && icons.indexOf(p) < 0) icons.push(p);
      });
      out.push({ id: a.id, name: a.name.replace(/[<>&"]/g, ""), appId: typeof a.appId === "string" && a.appId ? a.appId : a.tizenId,
        tizenId: a.tizenId, brand: brand, bg: look.bg, fg: look.fg, sub: look.sub, iconPaths: icons.length ? icons : undefined });
    }
    return out.length ? out : null;
  }
  function setTizenApps(list) { applyAppList(tizenAppList(list)); }
  function applyAppList(apps) {
    if (!apps) return;
    var dockApp = visibleApps()[state.dockIdx], ws3App = visibleApps()[state.ws3Idx];
    DEFAULT_APPS = apps;
    if (state.movingId) {
      if (!apps.some(function (a) { return a.id === state.movingId; })) { state.movingId = null; cfg.appOrder = state.moveSnapshot || []; state.moveSnapshot = null; }
      else apps.forEach(function (a) { if (cfg.appOrder.indexOf(a.id) < 0) cfg.appOrder.push(a.id); });
    }
    var vis = visibleApps().map(function (a) { return a.id; });
    if (dockApp && vis.indexOf(dockApp.id) >= 0) state.dockIdx = vis.indexOf(dockApp.id);
    if (ws3App && vis.indexOf(ws3App.id) >= 0) state.ws3Idx = vis.indexOf(ws3App.id);
    renderAll();
  }
  if (window.OMARCHY_PLATFORM === "tizen") {
    DEFAULT_APPS = tizenAppList(window.OMARCHY_TIZEN_APPS) || DEFAULT_APPS;
    window.addEventListener("omarchy-tizen-apps", function (e) { setTizenApps(e && e.detail); });
  }

  var TV_INPUTS = [
    { id: "livetv", name: "TV", sub: "Live TV", appId: "com.webos.app.livetv" },
    { id: "hdmi1", name: "HDMI 1", sub: "HDMI 1", appId: "com.webos.app.hdmi1" },
    { id: "hdmi2", name: "HDMI 2", sub: "HDMI 2", appId: "com.webos.app.hdmi2" },
    { id: "hdmi3", name: "HDMI 3", sub: "HDMI 3", appId: "com.webos.app.hdmi3" },
    { id: "hdmi4", name: "HDMI 4", sub: "HDMI 4", appId: "com.webos.app.hdmi4" }
  ];

  // Samsung Tizen build: the adapter lists the TV's real sources as window.OMARCHY_TIZEN_INPUTS and/or the
  // "omarchy-tizen-inputs" event (detail = the full list), items { id, name, sub? }, and switches them with
  // window.OMARCHY_TIZEN_SWITCH_INPUT(id, ok, err). Until then only TV is shown, never guessed HDMI ports.
  function tizenInputList(list) {
    if (!Array.isArray(list)) return null;
    var out = [], seen = {};
    for (var i = 0; i < list.length && out.length < 16; i++) {
      var p = list[i];
      if (!p || typeof p.id !== "string" || !/^[A-Za-z0-9_.-]{1,40}$/.test(p.id) || typeof p.name !== "string" || !p.name || seen[p.id]) continue;
      seen[p.id] = true;
      var name = p.name.replace(/[<>&"]/g, "");
      out.push({ id: p.id, name: name, sub: typeof p.sub === "string" && p.sub ? p.sub.replace(/[<>&"]/g, "") : name, tizenInput: true });
    }
    return out.length || list.length === 0 ? out : null;
  }
  function setTizenInputs(list) {
    var inputs = tizenInputList(list);
    if (!inputs) return;
    var cur = TV_INPUTS[state.ws2Idx], ids = inputs.map(function (p) { return p.id; });
    TV_INPUTS = inputs;
    state.ws2Idx = cur && ids.indexOf(cur.id) >= 0 ? ids.indexOf(cur.id) : Math.max(0, Math.min(state.ws2Idx, inputs.length - 1));
    if (cineUi) cineUi.refresh();   // Cinemarchy's TV & inputs follow the TV
    renderAll();
  }
  if (window.OMARCHY_PLATFORM === "tizen") {
    TV_INPUTS = tizenInputList(window.OMARCHY_TIZEN_INPUTS) || [{ id: "tv", name: "TV", sub: "TV", tizenInput: true }];
    window.addEventListener("omarchy-tizen-inputs", function (e) { setTizenInputs(e && e.detail); });
  }

  var state = {
    ws: 0,
    zone: "dock", // "bar", "widgets", "dock", "ws2", "ws3", "ws4", "ws5", "modal", "arrange"
    barIdx: 1,
    widgetIdx: 0,
    dockIdx: 0,
    dockScroll: 0,
    ws2Idx: 0,
    ws3Idx: 0,
    ws4Idx: 0,
    ws5Idx: 0,
    modalOpen: false,
    modalItems: [],
    modalIdx: 0,
    movingId: null,     // id of the app being moved (long OK › Move), null otherwise
    moveSnapshot: null, // cfg.appOrder before the move, restored on Back
    okTimer: null,      // pending long-press timer for OK on an app tile
    okHeld: false,      // OK is still held after a long press: ignore its repeats and key-up
    weather: { tempC: 9, minC: 6, maxC: 16, code: 0, city: cfg.weatherCity || "Alessandria" },
    pcStatus: null,
    root: null    // rooted-TV helper: null = not checked yet, false = no Homebrew Channel, else { boot, home }
  };

  window.__OMARCHY_STATE = state;
  window.__OMARCHY_CFG = cfg;

  function hexToRgba(hex, a) {
    var h = (hex || "#1a1b26").replace("#", "");
    if (h.length === 3) h = h[0]+h[0]+h[1]+h[1]+h[2]+h[2];
    var r = parseInt(h.slice(0,2), 16) || 26;
    var g = parseInt(h.slice(2,4), 16) || 27;
    var b = parseInt(h.slice(4,6), 16) || 38;
    return "rgba(" + r + "," + g + "," + b + "," + a + ")";
  }

  function currentThemeObj() {
    var t = DATA.themes[cfg.theme] || DATA.themes["tokyo-night"] || {};
    var n = t.normal || {};
    var b = t.bright || {};
    var bg = t.background || t.bg || "#1a1b26";
    var fg = t.foreground || t.fg || "#a9b1d6";
    return {
      name: cfg.theme || "tokyo-night",
      bg: bg,
      bgAlt: t.lighter_background || n.black || "#24283b",
      fg: fg,
      fgBright: t.bright_foreground || b.white || fg,
      fgDim: t.dark_foreground || b.black || "#565f89",
      muted: t.muted || b.black || "#414868",
      accent: t.accent || t.blue || n.blue || "#7aa2f7",
      accent2: t.bright_magenta || t.magenta || n.magenta || "#bb9af7",
      magenta: t.magenta || n.magenta || "#bb9af7",
      green: t.green || n.green || "#9ece6a",
      yellow: t.yellow || n.yellow || "#e0af68",
      red: t.red || n.red || "#f7768e",
      cyan: t.cyan || n.cyan || "#7dcfff"
    };
  }

  var TEXT_SCALES = [[0.9, "ts_small"], [1, "ts_normal"], [1.15, "ts_large"], [1.3, "ts_xlarge"]];
  function textScaleLabel() {
    for (var i = 0; i < TEXT_SCALES.length; i++) if (TEXT_SCALES[i][0] === cfg.textScale) return tr(TEXT_SCALES[i][1]) + " " + Math.round(cfg.textScale * 100) + "%";
    return Math.round(cfg.textScale * 100) + "%";
  }
  // Every font-size in index.html and the tiles is calc(Npx * var(--ts, 1)), so this scales all launcher text.
  function applyTextScale() {
    var ok = TEXT_SCALES.some(function (t) { return t[0] === cfg.textScale; });
    if (!ok) cfg.textScale = 1;
    try { document.documentElement.style.setProperty("--ts", String(cfg.textScale)); } catch (e) {}
  }
  applyTextScale();

  function applyTheme() {
    var t = currentThemeObj();
    var r = document.documentElement.style;
    r.setProperty("--t-bg", t.bg);
    r.setProperty("--t-bg-alt", t.bgAlt);
    r.setProperty("--t-fg", t.fg);
    r.setProperty("--t-fg-bright", t.fgBright);
    r.setProperty("--t-fg-dim", t.fgDim);
    r.setProperty("--t-muted", t.muted);
    r.setProperty("--t-accent", t.accent);
    r.setProperty("--t-accent2", t.accent2);
    r.setProperty("--t-green", t.green);
    r.setProperty("--t-yellow", t.yellow);
    r.setProperty("--t-red", t.red);
    r.setProperty("--t-cyan", t.cyan);
    r.setProperty("--win-bg", hexToRgba(t.bg, cfg.opacity));
    r.setProperty("--bar-bg", hexToRgba(t.bg, Math.min(0.88, cfg.opacity + 0.05)));
    r.setProperty("--popup-bg", hexToRgba(t.bg, 0.95));

    var wl = document.getElementById("wallpaper-layer");
    var wv = document.getElementById("wallpaper-video");
    var wlist = DATA.wallpapers[cfg.theme] || [];
    var picked = cfg.wallpaper && cfg.wallpaper !== "plain" && allWallpapers().indexOf(cfg.wallpaper) >= 0 ? cfg.wallpaper : "";
    var activeWp = "";
    omafireWall(wl);
    applyCrt();
    if (omafireModeNow()) {
      wl.style.backgroundImage = "none";   // v2.14 OmaFire draws into the layer itself
    } else if (picked) {
      activeWp = picked;
      wl.style.backgroundImage = "url('" + picked + "')";
    } else if (cfg.proceduralBg || cfg.wallpaper === "plain" || !wlist.length) {
      wl.style.backgroundImage = "radial-gradient(circle at 75% 20%, " + hexToRgba(t.accent, 0.22) + " 0%, " + t.bg + " 70%)";
    } else {
      activeWp = wlist[cfg.wallpaperIdx % wlist.length];
      wl.style.backgroundImage = "url('" + activeWp + "')";
    }
    if (wv) {
      var intro = cfg.wallpaperIntro && activeWp && DATA.wallpaperIntros && DATA.wallpaperIntros[activeWp];
      if (intro) {
        if (wv.getAttribute("src") !== intro || !cfg.wallpaperLoop) wv.src = intro;   // once: restart like before
        wv.loop = !!cfg.wallpaperLoop;
        wv.style.display = "block";
        wv.onended = function () { if (!wv.loop) wv.style.display = "none"; };
        wv.onerror = function () { wv.style.display = "none"; };
        try { wv.play().catch(function () { wv.style.display = "none"; }); } catch (e) { wv.style.display = "none"; }
      } else {
        try { wv.pause(); } catch (e) {}
        wv.style.display = "none";
      }
    }
    wallpaperLoopTick();
    var dim = document.getElementById("wallpaper-dim");
    dim.style.background = "rgba(0,0,0," + (cfg.wallpaperDim || 0) + ")";
    pushLightsTheme(t, false);
  }

  // Every bundled background, in theme order (DATA.wallpapers: theme -> list of paths).
  function allWallpapers() {
    var out = [];
    Object.keys(DATA.wallpapers || {}).forEach(function (th) { out = out.concat(DATA.wallpapers[th]); });
    return out;
  }

  function fitStage() {
    var st = document.getElementById("stage");
    var H = window.OmarchyHooks, sc = window.screen || {};
    var s = H ? H.stageScale(window.innerWidth, window.innerHeight, window.OMARCHY_FIT_SCREEN === true, sc.width, sc.height, window.devicePixelRatio)
      : Math.min(window.innerWidth / 1920, window.innerHeight / 1080);
    var crt = typeof crtMode === "function" && crtMode() !== "off";
    if (!crt) { st.style.transform = "scale(" + s + ")"; return; }
    // Retro CRT: the 1920x1080 stage, smaller and centred a little high; the overlay covers exactly that area.
    var k = s * 0.88, w = 1920 * k, h = 1080 * k, x = (window.innerWidth - w) / 2, y = (window.innerHeight - h) / 2 - window.innerHeight * 0.012;
    st.style.transform = "translate(" + x + "px," + y + "px) scale(" + k + ")";
    var el = document.getElementById("crt-overlay"), b = document.getElementById("crt-badge");
    if (el) { el.style.left = x + "px"; el.style.top = y + "px"; el.style.width = w + "px"; el.style.height = h + "px"; }
    // The boot screen plays inside the set too (as on Android, where it is part of the scaled Home).
    var bt = document.getElementById("otv-boot");
    if (bt) { bt.style.left = x + "px"; bt.style.top = y + "px"; bt.style.width = w + "px"; bt.style.height = h + "px"; bt.style.right = "auto"; bt.style.bottom = "auto"; }
    if (b) b.style.top = (y + h + (window.innerHeight - y - h) * 0.32) + "px";
  }
  window.addEventListener("resize", fitStage);
  // Some older engines (webOS 4.0) report the final window size only after load, without a resize event (#72).
  window.addEventListener("load", fitStage);
  setTimeout(fitStage, 500);
  setTimeout(fitStage, 2000);

  function updateClock() {
    var d = new Date();
    var lang = getLang();
    var days = {
      en: ["sun","mon","tue","wed","thu","fri","sat"],
      it: ["dom","lun","mar","mer","gio","ven","sab"],
      de: ["so","mo","di","mi","do","fr","sa"]
    }[lang] || (I18N[lang] && I18N[lang].days) || ["sun","mon","tue","wed","thu","fri","sat"];
    var months = {
      en: ["jan","feb","mar","apr","may","jun","jul","aug","sep","oct","nov","dec"],
      it: ["gen","feb","mar","apr","mag","giu","lug","ago","set","ott","nov","dic"],
      de: ["jan","feb","mär","apr","mai","jun","jul","aug","sep","okt","nov","dez"]
    }[lang] || (I18N[lang] && I18N[lang].months) || ["jan","feb","mar","apr","may","jun","jul","aug","sep","oct","nov","dec"];
    var date = lang === "zh-CN" || lang === "ja"   // 11月2日 周一 / 11月2日(月)
      ? months[d.getMonth()] + d.getDate() + "日 " + days[d.getDay()]
      : days[d.getDay()] + " " + d.getDate() + " " + months[d.getMonth()];
    document.getElementById("bar-clock").textContent = date + "  " + clockText(d);
  }

  // 12 or 24 hour time for the bar and the screensaver clock (cfg.clockFormat, "auto" follows the language).
  function use24h() {
    var H = window.OmarchyHooks;
    if (H) return H.clock24(cfg.clockFormat, getLang(), window.OMARCHY_PREFERS_24H);
    return cfg.clockFormat === "24" || (cfg.clockFormat !== "12" && getLang() !== "en");
  }
  function clockText(d) {
    var h = d.getHours();
    var m = ("0" + d.getMinutes()).slice(-2);
    return use24h() ? ("0" + h).slice(-2) + ":" + m : ((h + 11) % 12 + 1) + ":" + m + (h >= 12 ? " PM" : " AM");
  }

  // Block-letter logos (only "█", "▀", "▄" and spaces) drawn as one SVG path instead of text: TV fonts at
  // 8-9 px merged or swapped the block glyphs, so the wordmark looked squashed or garbled (LG 32LQ63006LA, #60).
  // One path per logo has no seams between cells and stays crisp at any scale. cellW/cellH in px per character.
  function blockArt(text, cellW, cellH, extraStyle) {
    var lines = String(text || "").split("\n"), cols = 0, d = "";
    for (var y = 0; y < lines.length; y++) {
      var ln = lines[y]; if (ln.length > cols) cols = ln.length;
      for (var half = 0; half < 2; half++) {
        var x = 0;
        while (x < ln.length) {
          var c = ln.charAt(x), on = c === "\u2588" || (half === 0 ? c === "\u2580" : c === "\u2584");
          if (!on) { x++; continue; }
          var x0 = x;
          while (x < ln.length) {
            c = ln.charAt(x);
            if (!(c === "\u2588" || (half === 0 ? c === "\u2580" : c === "\u2584"))) break;
            x++;
          }
          d += "M" + x0 + " " + (y * 2 + half) + "h" + (x - x0) + "v1h-" + (x - x0) + "z";
        }
      }
    }
    if (!cols) return "";
    return '<svg class="block-art" role="img" aria-label="logo" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ' + cols + " " + (lines.length * 2) +
      '" preserveAspectRatio="xMinYMin meet" style="display:block;max-width:100%;height:auto;width:' + Math.round(cols * cellW) + 'px;' + (extraStyle || "") +
      '"><path fill="currentColor" d="' + d + '"/></svg>';
  }

  var logoTimer = null;
  // Final logo: the block art as SVG (same 8 px font cell as #term-logo: 0.6 x 1.15 em); plain text otherwise.
  function setLogo(el, target) {
    if (/^[\u2588\u2580\u2584 \n]+$/.test(target) && /[\u2588\u2580\u2584]/.test(target)) el.innerHTML = blockArt(target, 4.8, 9.2);
    else el.textContent = target;
  }

  function animateLogo() {
    var el = document.getElementById("term-logo");
    var target = DATA.logo || "OMARCHY TV";
    if (logoTimer) clearInterval(logoTimer);
    if (!cfg.logoAnim) {
      setLogo(el, target);
      return;
    }
    var glyphs = "░▒▓█▄▀■□▪▫01";
    var step = 0, total = 14;
    logoTimer = setInterval(function () {
      step++;
      if (step >= total) {
        clearInterval(logoTimer);
        logoTimer = null;
        setLogo(el, target);
        return;
      }
      var ratio = step / total;
      var out = "";
      for (var i = 0; i < target.length; i++) {
        var ch = target[i];
        if (ch === "\n" || ch === " ") out += ch;
        else if (Math.random() < ratio) out += ch;
        else out += glyphs[Math.floor(Math.random() * glyphs.length)];
      }
      el.textContent = out;
    }, 65);
  }

  function renderTerminal() {
    var t = currentThemeObj();
    var pr = document.getElementById("term-prompt");
    pr.innerHTML =
      '<span style="color:' + t.green + '">' + cfg.promptUser + '@' + cfg.promptHost + '</span>' +
      '<span style="color:' + t.fg + '">:</span>' +
      '<span style="color:' + t.accent + '">~</span>' +
      '<span style="color:' + t.fg + '">$ </span>' +
      '<span id="term-cursor">█</span>';

    var upMin = Math.max(1, Math.floor((Date.now() - START_TIME) / 60000));
    var upStr = upMin >= 60 ? (Math.floor(upMin / 60) + " h " + (upMin % 60) + " min") : (upMin + " min");
    var info = document.getElementById("term-info");
    info.innerHTML =
      '<span style="color:' + t.accent + '">' + tr("term_theme") + '</span><span style="color:' + t.fg + '">' + cfg.theme + '</span>\n' +
      '<span style="color:' + t.accent + '">' + tr("term_net") + '</span><span style="color:' + t.fg + '">' + (navigator.onLine === false ? "offline" : "online") + '</span>\n' +
      '<span style="color:' + t.accent + '">' + tr("term_up") + '</span><span style="color:' + t.fg + '">' + upStr + '</span>' +
      '<span style="color:' + t.fgDim + '"> · ' + APP_VERSION + '</span>';
  }

  function fmtTemp(c) {
    if (cfg.tempUnit === "F") {
      return Math.round(c * 9 / 5 + 32) + "°F";
    }
    return Math.round(c) + "°";
  }

  function formatAiPane(s) {
    if (!Array.isArray(s.ai)) {
      // No made-up amounts when the PC doesn't send them (issue #5, point 10).
      return "Claude   " + (s.claude ? "$" + s.claude + " left" : "—") + "\nOpenAI   " + (s.openai ? "$" + s.openai + " left" : "—") + "\nxAI Grok " + (s.xai ? "$" + s.xai + " left" : "—");
    }
    var mode = cfg.aiCredits === "dollars" ? "dollars" : "percent";
    var rows = s.ai;
    var seen = {};
    var out = [];
    if (mode === "percent") {
      rows.forEach(function (e) {
        var key = e.id || e.name;
        if (!key || seen[key]) return;
        var err = String(e.error || "").toLowerCase();
        if (e.status === "error" || err.indexOf("waiting for auth") >= 0 || err.indexOf("unavailable") >= 0) return;
        var line = e.plan || (e.used != null ? Math.round(e.used) + "% used" : "");
        if (!line) return;
        seen[key] = 1;
        var label = e.plan ? String(e.name || key).split(" ")[0] : (e.name || key);
        out.push(label + "  " + line);
      });
      return out.length ? out.join("\n") : "No subscription meter";
    }
    rows = rows.filter(function (e) { return e.unit && e.unit !== "%" && e.unit !== "tokens"; });
    if (!rows.length) return "No dollar balances";
    return rows.map(function (e) {
      var rem = e.remaining != null ? e.remaining : (e.limit != null && e.used != null ? Math.round((e.limit - e.used) * 100) / 100 : null);
      return (e.name || "balance") + "  $" + (rem != null ? rem : "?") + " left";
    }).join("\n");
  }

  function renderWidgets() {
    document.getElementById("legend-wx").textContent = tr("box_weather");
    document.getElementById("legend-pc").textContent = tr("box_pc");
    document.getElementById("legend-ai").textContent = tr("box_ai");

    document.getElementById("term-box").style.display = cfg.widgets.term ? "block" : "none";
    document.getElementById("wx-box").style.display = cfg.widgets.weather ? "block" : "none";
    document.getElementById("pc-box").style.display = cfg.widgets.pc ? "block" : "none";
    document.getElementById("ai-box").style.display = cfg.widgets.ai ? "block" : "none";
    document.getElementById("deploy-box").style.display = cfg.widgets.deploy ? "block" : "none";

    var wx = state.weather;
    var wmoMap = tr("wmo") || {};
    var cond = wmoMap[wx.code] || "clear";
    document.getElementById("wx-temp").textContent = fmtTemp(wx.tempC);
    document.getElementById("wx-line1").textContent = (wx.city || cfg.weatherCity) + " · " + cond;
    document.getElementById("wx-line2").textContent = tr("wx_min") + fmtTemp(wx.minC) + " · " + tr("wx_max") + fmtTemp(wx.maxC);

    var pcBody = document.getElementById("pc-body");
    var aiBody = document.getElementById("ai-body");
    var ws4Live = document.getElementById("ws4-live-details");

    if (state.pcStatus) {
      var s = state.pcStatus;
      pcBody.style.color = "var(--t-fg)";
      pcBody.textContent = (s.host || "omarchy-pc") + " · " + (s.kernel || "Arch Linux") + "\nCPU " + s.cpu + "%  [████░░░░░░]\nRAM " + s.ram + "%  [██████░░░░]";
      aiBody.style.color = "var(--t-fg)";
      aiBody.textContent = formatAiPane(s);
      if (ws4Live) {
        ws4Live.textContent = "Connected to " + (s.host || "omarchy-pc") + " (" + (cfg.pcHost || "simulated LAN link") + ")\nCPU: " + s.cpu + "% · RAM: " + s.ram + "% · GPU: " + (s.gpu || "18%") + "\nDeploys: " + (s.deploy || "omarchytv-webos " + APP_VERSION + " ✓");
      }
    } else {
      pcBody.style.color = "var(--t-fg-dim)";
      pcBody.textContent = tr("pc_not_connected");
      aiBody.style.color = "var(--t-fg-dim)";
      aiBody.textContent = tr("pc_not_connected");
      if (ws4Live) ws4Live.textContent = "";
    }
  }

  function pollPcStatus() {
    if (cfg.pcSimulated) {
      state.pcStatus = { host: "omarchy-workstation", kernel: "Linux 6.14-arch", cpu: 14, ram: 38, gpu: "12%", claude: "24.80", openai: "51.20", xai: "110.00" };
      renderWidgets();
      return Promise.resolve(state.pcStatus);
    }
    if (!cfg.pcHost) {
      state.pcStatus = null;
      renderWidgets();
      return Promise.resolve(null);
    }
    var base = cfg.pcHost.indexOf("http") === 0 ? cfg.pcHost : ("http://" + cfg.pcHost + (cfg.pcHost.indexOf(":") > 0 ? "" : ":8765"));
    var url = base.replace(/\/+$/, "") + "/v1/status";
    return fetch(url).then(function (r) { return r.json(); }).then(function (j) {
      state.pcStatus = j;
      liftoffState(j && j.liftoff);
      if (j && j.theme && DATA.themes[j.theme] && cfg.syncPcTheme) {
        cfg.theme = j.theme;
        applyTheme();
      }
      renderWidgets();
      return j;
    }).catch(function () {
      state.pcStatus = null;
      renderWidgets();
      return null;
    });
  }

  function fetchWeather() {
    var url = "https://api.open-meteo.com/v1/forecast?latitude=" + cfg.weatherLat + "&longitude=" + cfg.weatherLon + "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1";
    fetch(url).then(function (r) { return r.json(); }).then(function (j) {
      if (j && j.current) {
        state.weather.tempC = j.current.temperature_2m;
        state.weather.code = j.current.weather_code || 0;
        if (j.daily && j.daily.temperature_2m_min) {
          state.weather.minC = j.daily.temperature_2m_min[0];
          state.weather.maxC = j.daily.temperature_2m_max[0];
        }
        state.weather.city = cfg.weatherCity;
        renderWidgets();
      }
    }).catch(function () {});
  }

  // DEFAULT_APPS sorted by cfg.appOrder; ids missing from the order keep their default position after it.
  function orderedApps() {
    var order = cfg.appOrder || [];
    return DEFAULT_APPS.slice().sort(function (a, b) {
      var ia = order.indexOf(a.id), ib = order.indexOf(b.id);
      if (ia < 0) ia = order.length + DEFAULT_APPS.indexOf(a);
      if (ib < 0) ib = order.length + DEFAULT_APPS.indexOf(b);
      return ia - ib;
    });
  }

  function visibleApps() {
    return orderedApps().filter(function (a) {
      if (a.id === "tv" && !cfg.tvEnabled) return false;
      return cfg.hiddenApps.indexOf(a.id) < 0;
    });
  }

  var brokenIcons = {};   // icon paths that failed to load: never tried again, the next candidate is used instead
  // LG: SSAP icon URLs (http://127.0.0.1:3000/resources/...) only serve while the SSAP session that listed them is
  // open, so a re-render (theme change, renderAll) that re-requests them got errors and fell back to text tiles.
  // Loaded icons are kept: the decoded <img> node is reused across re-renders, and a small PNG copy goes into
  // localStorage by app id (when the TV allows reading the pixels), so the icons also survive restarts.
  var ICON_CACHE = "omarchytv_icon_cache", iconNodes = {}, iconData = {};
  try { iconData = JSON.parse(localStorage.getItem(ICON_CACHE) || "{}") || {}; } catch (e) { iconData = {}; }
  function iconKey(app) { return app.appId || app.id; }
  function iconCandidates(app) {
    if (!app.iconPaths) return [];
    var d = iconData[iconKey(app)];
    return (d && app.iconPaths.indexOf(d) < 0 ? [d] : []).concat(app.iconPaths);
  }
  function tileIcon(app) { return iconCandidates(app).filter(function (p) { return !brokenIcons[p]; })[0]; }
  function appTileClass(app) { return "app-tile" + (app.iconPaths && !tileIcon(app) ? " icon-failed" : ""); }
  function storeIconData(key, img) {   // a small PNG copy of a loaded icon, by app id; false if the TV refuses the pixels
    try {
      var c = document.createElement("canvas"), w = Math.min(160, img.naturalWidth || 0), h = Math.round(w * (img.naturalHeight || 1) / (img.naturalWidth || 1));
      if (!w || !c.getContext) return false;
      c.width = w; c.height = h; c.getContext("2d").drawImage(img, 0, 0, w, h);
      var url = c.toDataURL("image/png");
      if (url.length > 150000) return false;
      iconData[key] = url;
      return true;
    } catch (e) { return false; }
  }
  function saveIconCache() { try { localStorage.setItem(ICON_CACHE, JSON.stringify(iconData)); } catch (e) {} }
  // LG: copy every launch point's icon while the SSAP session that listed them is still open (afterwards the
  // /resources/ URLs answer 404 and the next session hands out new ones), then let the session close.
  function cacheLaunchPointIcons(lps, release) {
    var left = 0, done = false;
    function end() { if (done) return; done = true; saveIconCache(); iconNodes = {}; release(); }
    (lps || []).forEach(function (lp) {
      var urls = [lp.largeIcon, lp.mediumLargeIcon, lp.icon].filter(function (u) { return /^https?:/.test(u || ""); });
      if (!lp.id || !urls.length || typeof Image === "undefined") return;
      left++;
      var im = new Image(), i = 0;
      im.onload = function () { if (storeIconData(lp.id, im) || ++i >= urls.length) { if (!--left) end(); } else im.src = urls[i]; };
      im.onerror = function () { if (++i < urls.length) im.src = urls[i]; else if (!--left) end(); };
      im.src = urls[0];
    });
    if (!left) end(); else setTimeout(end, 8000);
  }
  function saveIconData(app, img) {
    if (/^data:/.test(img.src) || !(app.lgIcon || app.tvApp)) return;
    if (storeIconData(iconKey(app), img)) saveIconCache();
  }
  function wireTileIcon(tile, app) {
    var src = tileIcon(app), img = src && tile.querySelector("img.tile-icon");
    if (!img) return;
    var kept = iconNodes[src];
    if (kept && kept.complete && kept.naturalWidth) {   // reuse the already-decoded image: no new request
      // the same app can sit in the dock and in a workspace grid: a node already on screen is copied, not moved
      var node = kept.parentNode && document.body && document.body.contains(kept) ? kept.cloneNode(false) : kept;
      if (node.parentNode) node.parentNode.removeChild(node);
      img.parentNode.replaceChild(node, img);
      return;
    }
    img.onload = function () { iconNodes[img.src] = img; iconNodes[src] = img; saveIconData(app, img); };
    img.onerror = function () {
      brokenIcons[src] = true;
      if (iconData[iconKey(app)] === src) { delete iconData[iconKey(app)]; try { localStorage.setItem(ICON_CACHE, JSON.stringify(iconData)); } catch (e) {} }
      src = tileIcon(app);
      if (src) img.src = src; else tile.className += " icon-failed";
    };
  }

  function buildTileHTML(app) {
    if (app.brand === "tv") {
      return '<div class="tile-banner" style="background:var(--t-bg-alt);">' +
        '<svg width="54" height="54" viewBox="0 0 24 24" fill="none" stroke="var(--t-accent)" stroke-width="2">' +
        '<rect x="2" y="3" width="20" height="14"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>' +
        '<div style="font-size:calc(28px * var(--ts, 1));font-weight:700;color:var(--t-fg-bright);margin-top:6px;">' + app.name + '</div></div>';
    }
    // Tizen: the app's own installed icon wins over text tiles; if every candidate breaks, a generic app icon + name.
    if (app.iconPaths) {
      var icon = tileIcon(app);
      // Built-in LG tiles that got the TV's real icon fall back to their own styled tile, not the generic one.
      var fallback = app.lgIcon ? '<div class="tile-fallback" style="background:' + (app.bg || "var(--t-bg-alt)") + ';">' +
        '<div style="font-size:calc(30px * var(--ts, 1));font-weight:700;letter-spacing:1.2px;color:' + (app.fg || "var(--t-fg-bright)") + ';">' + app.sub + '</div></div>' :
        '<div class="tile-fallback"><svg width="54" height="54" viewBox="0 0 24 24" fill="none" stroke="var(--t-accent)" stroke-width="2">' +
        '<rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/></svg>' +
        '<div class="tile-text-only">' + app.name + '</div></div>';
      return '<div class="tile-banner tile-art" style="background:' + (app.bg || "var(--t-bg-alt)") + ';">' +
        (icon ? '<img class="tile-icon" src="' + escHtml(icon) + '" alt="">' : "") + fallback + '</div>';
    }
    if (cfg.textTiles) {
      return '<div class="tile-banner" style="background:var(--t-bg-alt);"><div class="tile-text-only">' + app.name + '</div></div>';
    }
    return '<div class="tile-banner" style="background:' + (app.bg || "var(--t-bg-alt)") + ';">' +
      '<div style="font-size:calc(30px * var(--ts, 1));font-weight:700;letter-spacing:1.2px;color:' + (app.fg || "var(--t-fg-bright)") + ';">' + (app.sub || app.name) + '</div>' +
      '</div>';
  }

  function renderDock() {
    var apps = visibleApps();
    if (state.dockIdx >= apps.length) state.dockIdx = Math.max(0, apps.length - 1);
    if (state.dockIdx < state.dockScroll) state.dockScroll = state.dockIdx;
    if (state.dockIdx >= state.dockScroll + 6) state.dockScroll = state.dockIdx - 5;

    var strip = document.getElementById("dock-strip");
    strip.innerHTML = "";
    var slice = apps.slice(state.dockScroll, state.dockScroll + 6);
    slice.forEach(function (app, i) {
      var realIdx = state.dockScroll + i;
      var d = document.createElement("div");
      d.className = appTileClass(app) + (state.ws === 0 && state.zone === "dock" && state.dockIdx === realIdx ? " focused" : "") + (app.id === state.movingId ? " moving" : "");
      d.innerHTML = buildTileHTML(app);
      wireTileIcon(d, app);
      d.onclick = function () { state.zone = "dock"; state.dockIdx = realIdx; renderAll(); launchApp(app); };
      // renderAll() rebuilds the strip, so only re-render when focus actually moves; otherwise the
      // tile under the pointer is replaced on every hover and a click never lands.
      d.onmouseenter = function () {
        if (state.modalOpen || state.ws !== 0 || (state.zone === "dock" && state.dockIdx === realIdx)) return;
        state.zone = "dock"; state.dockIdx = realIdx; renderAll();
      };
      strip.appendChild(d);
    });

    var cur = apps[state.dockIdx] || apps[0] || { name: "OmarchyTV", id: "tv" };
    document.getElementById("dock-app-name").textContent = cur.name;
    document.getElementById("dock-hint").textContent = cur.id === "tv" ? tr("hint_tv") : tr("hint_app");
    var banner = document.getElementById("arrange-banner");
    banner.textContent = tr("move_hint");
    banner.style.display = state.movingId ? "block" : "none";
  }

  function renderWorkspaces() {
    document.getElementById("ws1-layer").style.display = state.ws === 0 ? "block" : "none";
    document.getElementById("ws2-panel").className = "box has-legend ws-panel" + (state.ws === 1 ? " active" : "");
    document.getElementById("ws3-panel").className = "box has-legend ws-panel" + (state.ws === 2 ? " active" : "");
    document.getElementById("ws4-panel").className = "box has-legend ws-panel" + (state.ws === 3 ? " active" : "");
    document.getElementById("ws5-panel").className = "box has-legend ws-panel" + (state.ws === 4 ? " active" : "");

    document.getElementById("legend-ws2").textContent = tr("box_tv");
    document.getElementById("legend-ws3").textContent = tr("box_app");
    document.getElementById("legend-ws4").textContent = tr("box_ws_pc");
    document.getElementById("legend-ws5").textContent = tr("box_system");

    renderExtrasFocus();

    // Waybar buttons
    var btns = document.querySelectorAll("[data-bar-idx]");
    for (var i = 0; i < btns.length; i++) {
      var b = btns[i];
      var idx = parseInt(b.getAttribute("data-bar-idx"), 10);
      var wsAttr = b.getAttribute("data-ws");
      var cls = b.classList.contains("bar-icon-btn") ? "bar-icon-btn" : ("bar-btn" + (idx === 0 ? " logo-btn" : " ws-btn"));
      if (wsAttr !== null && parseInt(wsAttr, 10) === state.ws) cls += " ws-active";
      if (state.zone === "bar" && state.barIdx === idx) cls += " focused";
      b.className = cls;
    }

    var tip = document.getElementById("bar-tip");
    if (state.zone === "bar" && state.barIdx >= 6) {
      var tipKeys = ["tip_cinemarchy", "", "tip_wifi", "tip_bt", "tip_vol", "tip_inputs", "tip_saver"];
      tip.textContent = state.barIdx === 7 ? "OmarchyTV Arcade" : tr(tipKeys[state.barIdx - 6] || "tip_saver");   // a product name, the same in every language
      tip.style.display = "block";
    } else {
      tip.style.display = "none";
    }

    // Widget focus borders on WS1
    var wBoxes = [document.getElementById("term-box"), document.getElementById("wx-box"), document.getElementById("pc-box"), document.getElementById("ai-box")];
    wBoxes.forEach(function (el, idx) {
      if (!el) return;
      el.classList.toggle("focused", state.ws === 0 && state.zone === "widgets" && state.widgetIdx === idx);
    });

    // WS2: TV & HDMI Inputs
    if (state.ws === 1) {
      var g2 = document.getElementById("ws2-grid");
      g2.innerHTML = "";
      TV_INPUTS.forEach(function (inp, i) {
        var d = document.createElement("div");
        d.className = "app-tile" + (state.zone === "ws2" && state.ws2Idx === i ? " focused" : "");
        d.innerHTML = buildTileHTML({ name: inp.name, brand: "tv" });
        d.onclick = function () { state.zone = "ws2"; state.ws2Idx = i; renderAll(); launchApp(inp); };
        g2.appendChild(d);
      });
      var curInp = TV_INPUTS[state.ws2Idx] || TV_INPUTS[0];
      document.getElementById("ws2-title").textContent = curInp ? curInp.sub : "No inputs available";
      document.getElementById("ws2-hint").textContent = curInp ? tr("hint_ws2") : "Return to Home to refresh inputs";
    }

    // WS3: All Apps Grid
    if (state.ws === 2) {
      var g3 = document.getElementById("ws3-grid");
      g3.innerHTML = "";
      var all = visibleApps();
      if (state.ws3Idx >= all.length) state.ws3Idx = Math.max(0, all.length - 1);
      all.forEach(function (app, i) {
        var d = document.createElement("div");
        d.className = appTileClass(app) + (state.zone === "ws3" && state.ws3Idx === i ? " focused" : "") + (app.id === state.movingId ? " moving" : "");
        d.innerHTML = buildTileHTML(app);
        wireTileIcon(d, app);
        d.onclick = function () { state.zone = "ws3"; state.ws3Idx = i; renderAll(); launchApp(app); };
        g3.appendChild(d);
      });
      var curApp = all[state.ws3Idx] || all[0];
      document.getElementById("ws3-title").textContent = curApp ? curApp.name : "Apps";
      document.getElementById("ws3-hint").textContent = tr("hint_ws3");
    }

    // WS4: Omarchy PC
    if (state.ws === 3) {
      document.getElementById("ws4-heading").textContent = tr("ws4_heading");
      document.getElementById("ws4-desc").textContent = tr("ws4_desc");
      var b0 = document.getElementById("ws4-pair-btn");
      var b1 = document.getElementById("ws4-demo-btn");
      b0.className = "ws4-btn" + (state.zone === "ws4" && state.ws4Idx === 0 ? " focused" : "");
      b1.className = "ws4-btn" + (state.zone === "ws4" && state.ws4Idx === 1 ? " focused" : "");
      b1.textContent = cfg.pcSimulated ? "[ disconnect simulated PC ]" : "[ simulate connected PC ]";
    }

    // WS5: System Settings
    if (state.ws === 4) {
      var rows = getSysRows();
      var list5 = document.getElementById("ws5-list");
      list5.innerHTML = "";
      rows.forEach(function (r, i) {
        var row = document.createElement("div");
        row.className = "sys-row" + (state.zone === "ws5" && state.ws5Idx === i ? " focused" : "");
        row.innerHTML = '<span class="sys-lbl">' + r.label + '</span><span class="sys-val">' + r.val + '</span>';
        row.onclick = function () { state.zone = "ws5"; state.ws5Idx = i; renderAll(); r.act(); };
        list5.appendChild(row);
      });
    }
  }

  function getSysRows() {
    var transPct = Math.round((1 - cfg.opacity) * 100) + "%";
    var idleStr = cfg.saverIdleMin === 0 ? "never" : (cfg.saverIdleMin + " min");
    return [
      { label: "🎬 Cinemarchy (Media, Streaming, Live TV & Snippets)", val: "C ›", act: function () { openCinemarchyModal(0); } },
      { label: "🕹 OmarchyTV Arcade", val: "›", act: openArcade },
      { label: "🧩 " + tr("sys_plugins"), val: ((cfg.pluginSpacexTv ? 1 : 0) + (cfg.pluginGrokbot ? 1 : 0)) + " enabled ›", act: openPluginsModal },
      { label: tr("sys_webos"), val: "›", act: function () { launchApp({ name: "LG Settings", appId: "com.palm.app.settings" }); } },
      { label: tr("sys_themes"), val: cfg.theme, act: openThemesModal },
      { label: tr("sys_trans"), val: transPct, act: function () {
        var steps = [0.95, 0.85, 0.75, 0.65, 0.50, 0.40];
        var idx = (steps.indexOf(cfg.opacity) + 1) % steps.length;
        cfg.opacity = steps[idx];
        saveCfg(); applyTheme(); renderAll();
      } },
      { label: tr("sys_widgets"), val: "›", act: openWidgetsModal },
      { label: tr("sys_arrange"), val: "›", act: function () { showToast("Arrange panels: use D-pad on Home"); switchWorkspace(0); } },
      { label: tr("sys_saver"), val: "›", act: openSaverModal },
      { label: "Ambilight & Lights", val: (cfg.lightsHub || "off") + " ›", act: openLightsModal },
      { label: tr("sys_saver_idle"), val: idleStr, act: function () {
        var opts = [1, 2.5, 5, 10, 0];
        cfg.saverIdleMin = opts[(opts.indexOf(cfg.saverIdleMin) + 1) % opts.length];
        saveCfg(); renderAll();
      } },
      { label: tr("sys_text_size"), val: textScaleLabel(), act: function () {
        var i = 0; while (i < TEXT_SCALES.length && TEXT_SCALES[i][0] !== cfg.textScale) i++;
        cfg.textScale = TEXT_SCALES[(i + 1) % TEXT_SCALES.length][0];
        applyTextScale(); saveCfg(); renderAll();
      } },
      { label: tr("sys_logo_anim"), val: cfg.logoAnim ? "on" : "off", act: function () { cfg.logoAnim = !cfg.logoAnim; saveCfg(); renderAll(); animateLogo(); } },
      { label: tr("sys_text_tiles"), val: cfg.textTiles ? "on" : "off", act: function () { cfg.textTiles = !cfg.textTiles; saveCfg(); renderAll(); } },
      { label: tr("sys_all"), val: "›", act: openAllSettingsModal },
      { label: tr("sys_about"), val: APP_VERSION + " ›", act: openAboutModal }
    ];
  }

  function openModal(title, sub, items, withInput, onInput, opts) {
    opts = opts || {};
    state.modalOpen = true;
    state.modalItems = items || [];
    state.modalIdx = opts.focusIdx > 0 && opts.focusIdx < state.modalItems.length ? opts.focusIdx : 0;
    state.modalDigit = null;
    document.getElementById("modal-title").textContent = title;
    // Keep ordinary menus narrow; input and prose-heavy dialogs retain their reading width.
    document.getElementById("modal-box").className = "box" + (withInput || (sub && sub.length > 160) ? " wide" : "");
    document.getElementById("modal-hint").textContent = tr("menu_hint");
    var subEl = document.getElementById("modal-sub");
    subEl.textContent = sub || "";
    subEl.style.display = sub ? "block" : "none";

    var iw = document.getElementById("modal-input-wrap");
    var inp = document.getElementById("modal-input");
    if (withInput) {
      iw.style.display = "block";
      inp.value = "";
      inp.placeholder = withInput;
      inp.oninput = function () { if (onInput) onInput(inp.value); };
      // opts.noAutoFocus: the field is not focused on open, so the TV keyboard only appears once the
      // user moves onto the field (▲ from the first item) and selects it.
      if (!opts.noAutoFocus) setTimeout(function () { inp.focus(); }, 30);
    } else {
      iw.style.display = "none";
      inp.oninput = null;
    }
    document.getElementById("modal-backdrop").className = "open";
    renderModalList();
  }

  // Text field in a menu (Walker, city, PC address). Backspace edits the text instead of closing the
  // menu, and ▼ OK leave the field first: while it keeps focus, webOS sends OK to the on-screen
  // keyboard instead of the list. ▲ on the first list item goes back into the field.
  var modalInput = document.getElementById("modal-input");
  modalInput.addEventListener("keydown", function (e) {
    var k = e.key || { 8: "Backspace", 13: "Enter", 38: "ArrowUp", 40: "ArrowDown" }[e.keyCode];
    if (k === "Backspace" || k === "ArrowUp") e.stopPropagation();   // edit text / stay in the field
    else if (k === "ArrowDown") { modalInput.blur(); e.stopPropagation(); }   // land on the first item
    else if (k === "Enter") modalInput.blur();                                  // pick the highlighted item
  });
  document.addEventListener("keydown", function (e) {
    var up = e.key === "ArrowUp" || e.keyCode === 38;
    if (!up || !state.modalOpen || state.modalIdx !== 0 || document.activeElement === modalInput) return;
    if (document.getElementById("modal-input-wrap").style.display !== "block") return;
    modalInput.focus();
    e.preventDefault();
    e.stopPropagation();
  }, true);

  function closeModal() {
    if (state.cineTab === 0 && dhhState.videos && window.OmarchyDhh) window.OmarchyDhh.markSeen(window.localStorage, dhhState.videos);
    state.cineTab = -1;
    state.modalOpen = false;
    state.gbChat = false;
    state.tuiOpen = false;
    document.getElementById("modal-backdrop").className = "";
    renderAll();
  }

  function menuIcon(icon) {
    // Inline paths avoid missing Nerd Font glyphs on older TV firmware.
    var paths = {
      "󰕼": "M3 5h18v14H3z M7 5v14 M17 5v14 M3 10h4 M17 10h4 M3 14h4 M17 14h4",
      "": "M8 3h8v5h5v8h-5v5H8v-5H3V8h5z",
      "󰀻": "M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z",
      "󰸌": "M12 3a9 9 0 1 0 0 18h2a2 2 0 0 0 0-4h-1a2 2 0 0 1 0-4h3a5 5 0 0 0 0-10z M7 9h1 M11 6h1 M16 7h1 M6 14h1",
      "": "M3 4h18v16H3z M3 17l6-6 4 4 3-3 5 5 M15 8h1",
      "󰖐": "M7 18a5 5 0 1 1 1-10 6 6 0 0 1 11 3 4 4 0 0 1-1 7z",
      "󱄄": "M3 4h18v13H3z M8 21h8 M12 17v4 M8 8l8 5 M16 8l-8 5",
      "": "M4 6h16 M4 12h16 M4 18h16 M8 3v6 M16 9v6 M10 15v6",
      "": "M3 4h18v16H3z M7 9l3 3-3 3 M13 15h4",
      "󰚩": "M5 7h14v13H5z M12 3v4 M2 11v5 M22 11v5 M8 11h1 M15 11h1 M8 16h8",
      "󰖟": "M3 12h18 M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z M12 3c-5 5-5 13 0 18 M12 3c5 5 5 13 0 18",
      "󰾆": "M4 19V9 M9 19V5 M14 19v-7 M19 19V3"
    };
    return paths[icon] ? '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="' + paths[icon] + '"></path></svg>' : escHtml(icon);
  }

  function renderModalList() {
    var list = document.getElementById("modal-list");
    list.innerHTML = "";
    state.modalItems.forEach(function (it, idx) {
      var d = document.createElement("div");
      d.className = "modal-item" + (idx === state.modalIdx ? " focused" : "");
      d.setAttribute("role", "option");
      d.setAttribute("aria-selected", idx === state.modalIdx ? "true" : "false");
      d.innerHTML = (it.icon ? '<span class="mi-icon" aria-hidden="true">' + menuIcon(it.icon) + '</span>' : '') +
        '<span class="mi-label">' + it.label + '</span><span class="mi-right">' + (it.right || "") + '</span>';
      d.onclick = function () { state.modalIdx = idx; if (it.act) it.act(); };
      list.appendChild(d);
      if (idx === state.modalIdx && d.scrollIntoView) d.scrollIntoView({ block: "nearest" });
    });
  }

  var ANIMATED_INTROS = [
    { theme: "last-horizon", idx: 0, wp: "wallpapers/last-horizon/01-eyes-wide.webp" },
    { theme: "cumulus", idx: 0, wp: "wallpapers/cumulus/01-cloud-town-path.webp" },
    { theme: "cumulus", idx: 1, wp: "wallpapers/cumulus/02-cottage.webp" },
    { theme: "cumulus", idx: 2, wp: "wallpapers/cumulus/03-airship.webp" },
    { theme: "cumulus", idx: 3, wp: "wallpapers/cumulus/04-lighthouse.webp" },
    // v2.14: OmaFire (Wes Grimes) in the random pool; with a random theme too, any of the intros' themes
    { fire: "omafire:campfire" },
    { fire: "omafire:embers" }
  ];

  function rotateRandomIfEnabled() {
    if (!cfg.randomTheme && !cfg.randomWallpaper) return false;
    var pick = ANIMATED_INTROS[Math.floor(Math.random() * ANIMATED_INTROS.length)];
    if (pick.fire && cfg.randomWallpaper) {
      if (pick.fire === cfg.wallpaper) pick = { fire: pick.fire === "omafire:campfire" ? "omafire:embers" : "omafire:campfire" };
      if (cfg.randomTheme) cfg.theme = "campfire";   // the fire comes with its own theme
      cfg.wallpaper = pick.fire;
      cfg.wallpaperIdx = 0;
    } else if (pick.fire) {   // random theme only: the fires are wallpapers, so just the theme changes
      cfg.theme = cfg.theme === "last-horizon" ? "cumulus" : "last-horizon";
    } else if (cfg.randomTheme && cfg.randomWallpaper) {
      cfg.theme = pick.theme;
      cfg.wallpaper = "";
      cfg.wallpaperIdx = pick.idx;
    } else if (cfg.randomTheme) {
      cfg.theme = cfg.theme === "last-horizon" ? "cumulus" : "last-horizon";
    } else if (cfg.randomWallpaper) {
      cfg.wallpaper = pick.wp;
      cfg.wallpaperIdx = pick.idx;
    }
    saveCfg();
    applyTheme();
    renderAll();
    return true;
  }

  document.addEventListener("visibilitychange", function () {
    if (!document.hidden && !state.modalOpen) rotateRandomIfEnabled();
  });

  function openThemesModal(keepFocus) {
    var focus = state.modalIdx;
    var names = Object.keys(DATA.themes);
    var items = [
      { label: tr("random_theme"), right: cfg.randomTheme ? "on" : "off", act: function () {
        cfg.randomTheme = !cfg.randomTheme; saveCfg(); openThemesModal(true);
      } },
      { label: tr("random_wallpaper"), right: cfg.randomWallpaper ? "on" : "off", act: function () {
        cfg.randomWallpaper = !cfg.randomWallpaper; saveCfg(); openThemesModal(true);
      } },
      { label: tr("theme_to_pc"), right: cfg.themeToPc ? "on" : "off", act: function () {
        cfg.themeToPc = !cfg.themeToPc; saveCfg(); openThemesModal(true);
      } }
    ].concat(names.map(function (n) {
      return {
        label: n,
        right: !cfg.randomTheme && n === cfg.theme ? "✓" : (n === cfg.theme ? "●" : ""),
        act: function () {
          cfg.theme = n;
          cfg.randomTheme = false;
          cfg.wallpaperIdx = 0;
          saveCfg(); applyTheme(); renderAll(); closeModal();
          themeToPc(n);
        }
      };
    }));
    var curIdx = names.indexOf(cfg.theme);
    openModal(tr("sys_themes") + " (" + names.length + ")", "Select a theme (MIT omarchy.webp + Liftoff Sunset)", items);
    if (keepFocus) { state.modalIdx = Math.min(focus, items.length - 1); renderModalList(); }
    else if (curIdx >= 0) { state.modalIdx = curIdx + 3; renderModalList(); }
  }

  // v2.14 reverse theme sync: with "Also change the PC theme" on and a real PC paired, the theme just picked here
  // goes to the PC bridge (omarchy-tv webos-serve), which runs omarchy-theme-set when it has that theme. The PC's
  // status then reports the same theme, so nothing loops. A theme the PC doesn't have changes only the TV.
  // ---- v2.14 PC requests with the ask code (update via PC, power) ----
  function pcPost(path, body, keepalive) {
    var o = { method: "POST", headers: { "X-Omarchy-Token": cfg.grokbotCode || "", "Content-Type": "text/plain" }, body: JSON.stringify(body) };
    if (keepalive) o.keepalive = true;
    return fetch(pcBase() + path, o).then(function (r) {
      return r.json().then(function (j) { return { status: r.status, j: j || {} }; }, function () { return { status: r.status, j: {} }; });
    });
  }
  function pcReady() { return !!(cfg.pcHost && !cfg.pcSimulated && cfg.grokbotCode); }
  function updateFailed(r) {
    showToast(tr("update_failed").replace("%s", (r && r.j && (r.j.message || r.j.error)) || (r && r.status ? "HTTP " + r.status : "offline")));
  }

  // "Update via PC": the PC downloads the newest official release for this TV, checks it and installs it
  // (LG: ares-install; Samsung: signed with the user's own certificate profile). The countdown comes from /v1/status.
  function updateViaPc() {
    if (!pcReady()) { showToast(tr("update_need_pc")); return; }
    var plat = window.OMARCHY_PLATFORM === "tizen" ? "tizen" : "webos";
    showToast(tr("update_checking"));
    pcPost("/v1/update/tv", { action: "check", platform: plat, version: APP_VERSION }).then(function (r) {
      if (r.status !== 200) { updateFailed(r); return; }
      if (!r.j.latest) { showToast(tr("update_none").replace("%s", APP_VERSION)); return; }
      var v = String(r.j.latest);
      openModal(tr("update_via_pc"), tr("update_confirm").replace("%s", v), [
        { label: tr("update_install"), right: v, act: function () {
          closeModal();
          pcPost("/v1/update/tv", { action: "install", platform: plat, version: APP_VERSION, want: v }).then(function (r2) {
            if (r2.status !== 200 && r2.status !== 409) { updateFailed(r2); return; }
            if (r2.j.result === "uptodate") showToast(tr("update_none").replace("%s", APP_VERSION));
            pollPcStatus();
          }, function () { updateFailed(null); });
        } },
        { label: tr("tile_cancel"), right: "", act: closeModal }
      ]);
    }, function () { updateFailed(null); });
  }

  // ---- v2.14 PC sleeps with the TV (LG only: a web app can't send Wake-on-LAN, so waking needs Android TV) ----
  var powerSent = "";
  function pcPowerSend(st) {
    if (!cfg.pcPower || !pcReady() || powerSent === st) return;
    powerSent = st;
    try { pcPost("/v1/power", { state: st }, true).then(null, function () {}); } catch (e) {}
  }
  document.addEventListener("visibilitychange", function () {
    if (!cfg.pcPower || !isWebos()) return;
    if (!document.hidden) { if (powerSent === "standby") pcPowerSend("on"); return; }
    if (!tvControlApproved()) return;
    // Hidden also means "another app is in front": only a TV that is really going off counts.
    ssapRequest("ssap://com.webos.service.tvpower/power/getPowerState", function (ok, info, data) {
      var st = ok && data && data.state;
      if (st && st !== "Active") pcPowerSend("standby");
    });
  });

  // ---- v2.14 Liftoff: a build or an update on the big screen (liftoff.js) ----
  var LIFT = window.OmarchyLiftoff || null;
  var lift = { s: null, at: 0, endedAt: 0, el: null, dismissed: "", endShown: "", seen: "", alarmed: "", timer: null };
  function liftoffState(s) {
    if (!LIFT || !s || ["running", "ok", "failed"].indexOf(s.state) < 0) return;
    var id = String(s.id || ""), st = s.state, news = st !== "running";
    if (st === "failed" && id !== lift.alarmed) { lift.alarmed = id; LIFT.alarm(); }
    if (id === lift.dismissed && !news) return;
    if (news && id + st === lift.endShown) return;
    if (!lift.s && st === "ok" && id !== lift.seen) return;
    lift.seen = id;
    if (news) { lift.endShown = id + st; lift.endedAt = Date.now(); }
    lift.s = s; lift.at = Date.now();
    liftoffShow();
  }
  function liftoffEl() {
    if (lift.el) return lift.el;
    var d = document.createElement("div");
    d.id = "liftoff";
    d.innerHTML = '<div class="box"><div class="title"></div><pre class="rocket"></pre><div class="right"><div class="clock"></div>' +
      '<div class="stage"></div><div class="bar"></div><div class="plus"></div></div><pre class="tail"></pre><div class="hint"></div></div>';
    document.body.appendChild(d);
    lift.el = d;
    return d;
  }
  function liftoffShow() {
    var d = liftoffEl();
    d.style.display = "block";
    liftoffRender();
    if (!lift.timer) lift.timer = setInterval(liftoffRender, 200);
  }
  function liftoffHide() {
    if (lift.timer) { clearInterval(lift.timer); lift.timer = null; }
    if (lift.el) { lift.el.style.display = "none"; lift.el.className = ""; }
    lift.s = null;
  }
  function liftoffRender() {
    var s = lift.s, d = lift.el;
    if (!s || !d) return;
    var now = Date.now(), since = Math.floor((now - lift.at) / 1000), st = s.state;
    if (st === "ok" && now - lift.endedAt > 5000) { liftoffHide(); return; }
    if (st === "failed" && now - lift.endedAt > 120000) { liftoffHide(); return; }
    var blink = Math.floor(now / 400) % 2 === 0;
    d.className = st + (st === "failed" && blink ? " blink" : "");
    function q(c) { return d.querySelector("." + c); }
    q("title").textContent = tr("liftoff_title") + " \u00b7 " + tr(s.kind === "update" ? "liftoff_update" : "liftoff_build") + " \u00b7 " + (s.name || "");
    var flame = st === "failed" ? [] : st === "ok" ? [LIFT.FLAME[1][Math.floor(now / 120) % 2], LIFT.FLAME[1][(Math.floor(now / 120) + 1) % 2]]
      : [LIFT.FLAME[0][Math.floor(now / 120) % 2]];
    var r = q("rocket");
    r.textContent = LIFT.ROCKET.join("\n") + "\n";
    var fl = document.createElement("span"); fl.className = "flame"; fl.textContent = flame.join("\n");
    r.appendChild(fl);
    var clock = q("clock");
    clock.textContent = st === "ok" ? tr("liftoff_ok") : st === "failed" ? tr("liftoff_abort") : LIFT.clock(s, since);
    clock.className = "clock" + (LIFT.finalCount(s, since) && !blink ? " dim" : "");
    var stage = s.stage || "", sk = "liftoff_stage_" + stage;   // the update's stages in the TV's language
    if (STRINGS.en[sk]) stage = tr(sk);
    if (st === "failed" && typeof s.code === "number") stage = (stage ? stage + " \u00b7 " : "") + tr("liftoff_exit").replace("%s", s.code);
    q("stage").textContent = stage;
    q("bar").textContent = st === "failed" ? "" : LIFT.bar(LIFT.progress(s, since), 24);
    q("plus").textContent = st === "running" ? "T+" + LIFT.mmss(LIFT.elapsed(s, since)) : "";
    q("tail").textContent = st === "failed" && s.tail ? s.tail.join("\n") : "";
    q("hint").textContent = tr("liftoff_hint");
  }
  // Any key hides it (and never reaches Home); a new state shows it again.
  window.addEventListener("keydown", function (e) {
    if (!lift.el || lift.el.style.display !== "block") return;
    if (lift.s) lift.dismissed = String(lift.s.id || "");
    liftoffHide();
    e.preventDefault(); e.stopPropagation();
    if (e.stopImmediatePropagation) e.stopImmediatePropagation();
  }, true);
  // ---- v2.14 OmarchyTV Arcade (games/*.js): inside #stage, so with Retro CRT on it plays inside the set ----
  var arcadeUi = null;
  function openArcade() {
    if (arcadeUi || !window.OmarchyArcade) return;
    if (state.modalOpen) closeModal();
    var host = document.getElementById("stage") || document.body;
    arcadeUi = window.OmarchyArcade.open(host, {
      lang: getLang(), theme: DATA.themes[cfg.theme], themeName: cfg.theme, themes: DATA.themes,
      sound: cfg.arcadeSound !== false, pcTheme: !!cfg.arcadePcTheme && pcReady(),
      onPcTheme: function (name) { arcadePcTheme(name || cfg.theme); },
      onExit: function () { arcadeUi = null; renderAll(); }
    });
  }
  function arcadePcTheme(name) {   // like themeToPc, but only for the game (its own setting); the PC's theme comes back on exit
    if (!pcReady()) return;
    fetch(pcBase() + "/v1/theme/set", { method: "POST", headers: { "X-Omarchy-Token": cfg.grokbotCode || "", "Content-Type": "text/plain" },
      body: JSON.stringify({ name: name }) }).then(function () {}, function () {});
  }
  window.OMARCHY_OPEN_ARCADE = openArcade;
  // While the arcade is open every key goes to the game: arrows, OK and Back only, so nothing leaves the launcher.
  window.addEventListener("keydown", function (e) {
    if (!arcadeUi) return;
    if (lift.el && lift.el.style.display === "block") return;   // a failed build's alarm still shows on top
    var n = window.OmarchyArcade.keyName(e);
    if (n) arcadeUi.key(n);
    e.preventDefault(); e.stopPropagation();
    if (e.stopImmediatePropagation) e.stopImmediatePropagation();
  }, true);

  // While a liftoff runs, ask the PC every second instead of every 5 s.
  setInterval(function () {
    if (!lift.s || lift.s.state !== "running" || document.hidden || pcPollPending) return;
    pcPollPending = true;
    pollPcStatus().then(function () { pcPollPending = false; }, function () { pcPollPending = false; });
  }, 1000);

  function themeToPc(name) {
    if (!cfg.themeToPc || !cfg.pcHost || cfg.pcSimulated) return;
    fetch(pcBase() + "/v1/theme/set", { method: "POST", headers: { "X-Omarchy-Token": cfg.grokbotCode || "", "Content-Type": "text/plain" },
      body: JSON.stringify({ name: name }) }).then(function (r) {
      return r.json().then(function (j) { if (j && j.result === "unknown") showToast(tr("theme_not_on_pc")); });
    }, function () {});
  }

  function openWeatherModal() {
    var items = [
      { label: tr("wx_auto"), right: "", act: function () {
        // #5: really locate the TV from its public IP (ipapi.co, then ipwho.is), as on Android; keep the city if both fail
        closeModal();
        var G = window.OmarchyGeo;
        if (!G) return;
        G.locate(function (u) { return fetch(u).then(function (r) { return r.json(); }); }).then(function (p) {
          if (!p) { showToast(tr("wx_auto_fail")); return; }
          cfg.weatherCity = p.city || (p.lat + ", " + p.lon); cfg.weatherLat = p.lat; cfg.weatherLon = p.lon;
          saveCfg(); fetchWeather(); renderAll();
        });
      } },
      { label: tr("wx_search"), right: cfg.weatherCity, act: openCitySearch },
      { label: tr("wx_unit"), right: "°" + cfg.tempUnit, act: function () {
        cfg.tempUnit = cfg.tempUnit === "C" ? "F" : "C";
        saveCfg(); renderAll(); openWeatherModal();
      } }
    ];
    openModal(tr("wx_title"), "", items);
  }

  // City search (issue #88): typed text goes to Open-Meteo geocoding like Android; curated presets stay as fallback.
  var DEFAULT_CITIES = [
    { name: "Milano", label: "Milano, IT", lat: 45.464, lon: 9.190 }, { name: "Alessandria", label: "Alessandria, IT", lat: 44.913, lon: 8.615 },
    { name: "Roma", label: "Roma, IT", lat: 41.893, lon: 12.483 }, { name: "Berlin", label: "Berlin, DE", lat: 52.520, lon: 13.405 },
    { name: "San Francisco", label: "San Francisco, US", lat: 37.775, lon: -122.419 }
  ];
  function openCitySearch() {
    var G = window.OmarchyGeo, presets = (window.OMARCHY_DATA && OMARCHY_DATA.weatherCities) || DEFAULT_CITIES;
    var timer = null, seq = 0;
    function cityItem(c) { return { label: c.label, right: c.lat.toFixed(2) + ", " + c.lon.toFixed(2), act: function () { setCity(c.name, c.lat, c.lon); } }; }
    function show(found, q) {
      var list = found.map(cityItem), pre = G ? G.filterPresets(presets, q) : presets;
      if (!pre.length && !found.length) pre = presets;
      state.modalItems = list.concat(pre.map(cityItem));
      if (state.modalIdx >= state.modalItems.length) state.modalIdx = 0;
      renderModalList();
    }
    function search(q) {
      q = (q || "").trim();
      clearTimeout(timer);
      show([], q);
      if (q.length < 2 || !G || !G.searchUrl) return;
      var my = ++seq;
      timer = setTimeout(function () {
        fetch(G.searchUrl(q, getLang())).then(function (r) { return r.json(); }).then(function (j) {
          if (my === seq && state.modalOpen) show(G.parseSearch(j), q);
        }).catch(function () {});   // offline / blocked: presets stay
      }, 600);
    }
    openModal(tr("wx_search"), "Type a city name (e.g. Milan, Lisbon, Tokyo):", presets.map(cityItem), "City name…", search);
  }

  function setCity(name, lat, lon) {
    cfg.weatherCity = name; cfg.weatherLat = lat; cfg.weatherLon = lon;
    state.weather.city = name;
    saveCfg(); fetchWeather(); renderAll(); closeModal();
  }

  function openWidgetsModal() {
    function tog(k) { cfg.widgets[k] = !cfg.widgets[k]; saveCfg(); renderAll(); openWidgetsModal(); }
    openModal(tr("sys_widgets"), "", [
      { label: "terminal · logo", right: cfg.widgets.term ? "on" : "off", act: function () { tog("term"); } },
      { label: "weather", right: cfg.widgets.weather ? "on" : "off", act: function () { tog("weather"); } },
      { label: "omarchy · pc", right: cfg.widgets.pc ? "on" : "off", act: function () { tog("pc"); } },
      { label: "ai credits · pc", right: cfg.widgets.ai ? "on" : "off", act: function () { tog("ai"); } },
      { label: "deploys · pc", right: cfg.widgets.deploy ? "on" : "off", act: function () { tog("deploy"); } }
    ]);
  }

  function openSaverModal(keepFocus) {
    var focus = state.modalIdx;
    openModal(tr("sys_saver"), "37 tte.js TerminalTextEffects or multi-layer Omarchy Cyber-TUI Aquarium", [
      { label: "Preview screensaver now", right: "▶", act: function () { closeModal(); startScreensaver(); } },
      { label: "Screensaver mode / art", right: cfg.saverMode === "aquarium" ? (cfg.saverArt === "aquarium-classic" ? "Aquarium Classic" : "Omarchy Aquarium (homage)") : (cfg.saverArt === "cinemarchy" ? "CINEMARCHY" : "OMARCHY TV"), act: function () {
        // cycle: Aquarium (homage, default) -> Aquarium Classic -> OMARCHY TV -> CINEMARCHY -> Aquarium
        if (cfg.saverMode === "aquarium" && cfg.saverArt !== "aquarium-classic") { cfg.saverArt = "aquarium-classic"; cfg.saverMode = "aquarium"; }
        else if (cfg.saverMode === "aquarium") { cfg.saverArt = "omarchy-tv"; cfg.saverMode = "logo"; }
        else if (cfg.saverArt !== "cinemarchy") { cfg.saverArt = "cinemarchy"; cfg.saverMode = "logo"; }
        else { cfg.saverArt = "aquarium"; cfg.saverMode = "aquarium"; }
        saveCfg(); openSaverModal(true);
      } },
      { label: "tte.js speed", right: cfg.saverSpeed || "normal", act: function () {
        var sp = ["normal", "slow", "fast"]; cfg.saverSpeed = sp[(sp.indexOf(cfg.saverSpeed || "normal") + 1) % 3]; saveCfg(); openSaverModal(true);
      } },
      { label: tr("sys_saver_idle"), right: cfg.saverIdleMin === 0 ? "never" : (cfg.saverIdleMin + " min"), act: function () {
        var opts = [1, 2.5, 5, 10, 0];
        cfg.saverIdleMin = opts[(opts.indexOf(cfg.saverIdleMin) + 1) % opts.length];
        saveCfg(); openSaverModal(true);
      } },
      { label: "Clock under logo", right: cfg.saverShowClock ? "on" : "off", act: function () {
        cfg.saverShowClock = !cfg.saverShowClock; saveCfg(); openSaverModal(true);
      } },
      { label: "Aquarium ST3 Synth Audio", right: cfg.saverSound !== false ? "on" : "off", act: function () {
        cfg.saverSound = cfg.saverSound === false; saveCfg(); openSaverModal(true);
      } },
      { label: "Turn off after idle", right: cfg.offIdleMin ? cfg.offIdleMin + " min" : "never", act: function () {
        var opts = [0, 5, 15, 30, 60, 120];
        cfg.offIdleMin = opts[(opts.indexOf(cfg.offIdleMin) + 1) % opts.length];
        saveCfg(); openSaverModal(true);
      } },
      { label: tr("living"), right: cfg.living ? "on" : "off", act: function () {
        cfg.living = !cfg.living; saveCfg(); openSaverModal(true);
      } },
      { label: "  " + tr("living_notes"), right: cfg.livingNotes ? "on" : "off", act: function () {
        cfg.livingNotes = !cfg.livingNotes; saveCfg(); openSaverModal(true);
        if (cfg.livingNotes) showToast(tr("living_notes_note"));
      } },
      { label: "  " + tr("living_x"), right: tr(cfg.livingX === "api" ? "living_x_api" : cfg.livingX === "notify" ? "living_x_notify" : "off"), act: function () {
        var m = ["off", "notify", "api"];
        cfg.livingX = m[(m.indexOf(cfg.livingX) + 1) % m.length]; saveCfg(); openSaverModal(true);
        if (cfg.livingX === "api") showToast(tr("living_x_api_note"));
      } },
      { label: "  GitHub", right: cfg.livingGithub !== false ? "on" : "off", act: function () {
        cfg.livingGithub = cfg.livingGithub === false; saveCfg(); openSaverModal(true);
      } },
      { label: "  Omastorm radar", right: cfg.livingRadar ? "on" : "off", act: function () {
        cfg.livingRadar = !cfg.livingRadar; saveCfg(); openSaverModal(true);
      } },
      { label: "Turn off", right: cfg.offMode === "tv" ? "TV" : "screen only", act: function () {
        cfg.offMode = cfg.offMode === "tv" ? "screen" : "tv";
        saveCfg(); openSaverModal(true);
      } },
      { label: "TV control", right: tvControlApproved() ? "approved" : "needs approval", act: function () {
        showToast("Checking TV control… accept the prompt on screen if it appears");
        ssapRequest("ssap://com.webos.service.tvpower/power/getPowerState", function (ok, info) {
          showToast(ok ? "TV control approved" : "TV control not available (" + info + ")");
          if (state.modalOpen) openSaverModal(true);
        });
      } }
    ]);
    // Toggling an item re-opens the menu; keep the cursor on it instead of jumping to the top.
    if (keepFocus) { state.modalIdx = Math.min(focus, state.modalItems.length - 1); renderModalList(); }
  }

  // ---- Rooted TVs: open at start-up and take over the Home button -------------------------------
  // Homebrew Channel's exec service runs shell commands as root. Toggling a setting creates or removes
  // a flag file in ROOT_FLAGS and installs ROOT_HOOK, which Homebrew Channel runs at boot to start
  // rooted/omarchytv-home.sh from this app's folder (see that script). Unrooted TVs have no such
  // service: the settings say so, and homewatch/ covers them from another machine.
  var ROOT_FLAGS = "/var/lib/omarchytv";
  var ROOT_HOOK = "/var/lib/webosbrew/init.d/omarchytv";   // run-parts skips names with a dot
  var ROOT_PID = "/tmp/omarchytv-home.pid";                // written by rooted/omarchytv-home.sh
  var rootCalls = [];                                       // keep bridges alive until they answer

  function rootExec(cmd, cb) {
    if (!window.PalmServiceBridge) { cb(false, "not running on webOS"); return; }
    var b = new PalmServiceBridge();
    rootCalls.push(b);
    b.onservicecallback = function (msg) {
      rootCalls.splice(rootCalls.indexOf(b), 1);
      var r = {};
      try { r = JSON.parse(msg); } catch (e) {}
      cb(!!r.returnValue, r.returnValue ? (r.stdoutString || "") : (r.errorText || r.stderrString || "error"));
    };
    b.call("luna://org.webosbrew.hbchannel.service/exec", JSON.stringify({ command: cmd }));
  }

  function rootRefresh(done) {
    rootExec("ls " + ROOT_FLAGS + " 2>/dev/null; true", function (ok, out) {
      state.root = ok ? { boot: /(^|\s)boot(\s|$)/.test(out), home: /(^|\s)home(\s|$)/.test(out) } : false;
      if (done) done();
    });
  }

  function rootSet(flag, on, done) {
    var dir = decodeURIComponent(location.pathname.replace(/\/[^\/]*$/, ""));
    var script = dir + "/rooted/omarchytv-home.sh";
    var cmd = on
      ? "mkdir -p " + ROOT_FLAGS + " && touch " + ROOT_FLAGS + "/" + flag +
        " && printf '#!/bin/sh\\n# OmarchyTV start-up and Home-button helper (rooted TVs)\\nS=\"%s\"\\n[ -f \"$S\" ] && setsid sh \"$S\" </dev/null >/dev/null 2>&1 &\\n' '" + script + "' > " + ROOT_HOOK +
        " && chmod 755 " + ROOT_HOOK +
        " && (setsid sh '" + script + "' --no-boot </dev/null >/dev/null 2>&1 &)"
      : "rm -f " + ROOT_FLAGS + "/" + flag +
        // no Home redirect wanted: stop the helper's process group (it waits on a luna subscription)
        "; [ -f " + ROOT_FLAGS + "/home ] || { p=$(cat " + ROOT_PID + " 2>/dev/null) && kill -- -$p; rm -f " + ROOT_PID + "; }" +
        "; [ -f " + ROOT_FLAGS + "/boot ] || [ -f " + ROOT_FLAGS + "/home ] || rm -f " + ROOT_HOOK + "; true";
    rootExec(cmd, function (ok, out) {
      if (!ok) showToast(tr("root_failed") + out);
      rootRefresh(done);
    });
  }

  function rootRow(flag, label) {
    var st = state.root;
    return { label: label, right: st === null ? "…" : st === false ? tr("root_needed") : (st[flag] ? "on" : "off"), act: function () {
      if (!state.root) { showToast(tr("root_missing")); return; }
      rootSet(flag, !state.root[flag], function () { if (state.modalOpen) openAllSettingsModal(true); });
    } };
  }

  // ---- Rooted TVs: keep OmarchyTV loaded when another app opens ---------------------------------
  // When memory runs short (most of the time on 1-1.5 GB TVs), webOS closes web apps in the background,
  // so every return to OmarchyTV is a cold start. LG's memory manager (memchute) skips apps on its
  // "KeepAlive" list, and it reads /home/root/memorymanager-conf.json, a writable override of
  // /etc/palm/memorymanager-conf.json, when that file exists. The toggle adds or removes this app in
  // the override (keeping any other edits in it) and restarts memchute. TVs whose memchute doesn't
  // know the override path show "not on this TV".
  var MEM_CONF = "/etc/palm/memorymanager-conf.json";
  var MEM_OVERRIDE = "/home/root/memorymanager-conf.json";
  var APP_ID = "com.omarchytv.home";

  function keepRead(cb) {
    rootExec("if grep -q " + MEM_OVERRIDE + " /usr/sbin/memchute 2>/dev/null && [ -f " + MEM_CONF + " ]; then cat " + MEM_CONF +
      "; echo; echo @@OVERRIDE@@; cat " + MEM_OVERRIDE + " 2>/dev/null; else echo UNSUPPORTED; fi", function (ok, out) {
      if (!ok) { cb(null); return; }
      var parts = out.split("@@OVERRIDE@@");
      var base, over = null;
      try { base = JSON.parse(parts[0]); } catch (e) { cb({ supported: false }); return; }
      try { if (parts[1] && parts[1].trim()) over = JSON.parse(parts[1]); } catch (e) { over = null; }
      cb({ supported: true, base: base, conf: over || JSON.parse(parts[0]) });
    });
  }

  function keepRefresh(done) {
    keepRead(function (r) {
      // "on" / "off", "na" (memchute without the override), "noroot" (no Homebrew Channel exec)
      state.keepLoaded = !r ? "noroot" : !r.supported ? "na" : (r.conf.KeepAlive || []).indexOf(APP_ID) >= 0 ? "on" : "off";
      if (done) done();
    });
  }

  function keepSet(on, done) {
    keepRead(function (r) {
      if (!r || !r.supported) { keepRefresh(done); return; }
      var list = (r.conf.KeepAlive || []).filter(function (id) { return id !== APP_ID; });
      if (on) list.push(APP_ID);
      r.conf.KeepAlive = list;
      var cmd;
      if (JSON.stringify(r.conf) === JSON.stringify(r.base)) {
        cmd = "rm -f " + MEM_OVERRIDE;   // nothing else customised: back to LG's file
      } else {
        var b64 = btoa(unescape(encodeURIComponent(JSON.stringify(r.conf, null, 1))));
        cmd = "echo " + b64 + " | base64 -d > " + MEM_OVERRIDE + ".tmp && mv -f " + MEM_OVERRIDE + ".tmp " + MEM_OVERRIDE;
      }
      rootExec(cmd + " && systemctl restart memchute", function (ok, out) {
        if (!ok) showToast(tr("root_failed") + out);
        keepRefresh(done);
      });
    });
  }

  function keepRow() {
    var st = state.keepLoaded;
    var right = !st ? "…" : st === "noroot" ? tr("root_needed") : st === "na" ? tr("root_keep_na") : st;
    return { label: tr("root_keep"), right: right, act: function () {
      if (state.keepLoaded === "noroot") { showToast(tr("root_missing")); return; }
      if (state.keepLoaded !== "on" && state.keepLoaded !== "off") return;
      keepSet(state.keepLoaded === "off", function () { if (state.modalOpen) openAllSettingsModal(true); });
    } };
  }

  function openAllSettingsModal(keepFocus) {
    var focus = state.modalIdx;
    var wlist = DATA.wallpapers[cfg.theme] || [];
    if (state.root === null) rootRefresh(function () { if (state.modalOpen) openAllSettingsModal(true); });
    if (state.keepLoaded === undefined) {
      state.keepLoaded = null;   // checking
      keepRefresh(function () { if (state.modalOpen) openAllSettingsModal(true); });
    }
    openModal(tr("sys_all"), "OmarchyTV " + APP_VERSION, [
      { label: "Language", right: cfg.lang ? (LANG ? LANG.name(cfg.lang, I18N) : cfg.lang) : "auto", act: function () {
        if (LANG) cfg.lang = LANG.next(cfg.lang);
        else { var l = ["", "en", "it", "de"]; cfg.lang = l[(l.indexOf(cfg.lang) + 1) % l.length]; }
        saveCfg(); updateClock(); renderAll(); openAllSettingsModal();
      } },
      { label: tr("sys_clock"), right: cfg.clockFormat === "12" || cfg.clockFormat === "24" ? cfg.clockFormat + " h" : "auto (" + (use24h() ? "24" : "12") + " h)", act: function () {
        var f = ["auto", "12", "24"], at = state.modalIdx;
        cfg.clockFormat = f[(f.indexOf(cfg.clockFormat) + 1) % f.length];
        saveCfg(); updateClock(); openAllSettingsModal();
        state.modalIdx = at; renderModalList();   // stay on this row so OK keeps cycling
      } },
      { label: "Next background", right: wlist.length ? ((cfg.wallpaperIdx % wlist.length) + 1) + "/" + wlist.length : "procedural", act: function () {
        cfg.wallpaperIdx++; cfg.wallpaper = ""; saveCfg(); applyTheme(); openAllSettingsModal();
      } },
      { label: "Always-animated wallpaper", right: cfg.wallpaperLoop ? "on" : "off", act: function () {
        cfg.wallpaperLoop = !cfg.wallpaperLoop; saveCfg(); applyTheme(); openAllSettingsModal();
      } },
      { label: "Wallpaper dimming", right: Math.round((cfg.wallpaperDim || 0) * 100) + "%", act: function () {
        var d = [0, 0.25, 0.5];
        cfg.wallpaperDim = d[(d.indexOf(cfg.wallpaperDim) + 1) % d.length];
        saveCfg(); applyTheme(); openAllSettingsModal();
      } },
      { label: tr("wp_title"), right: "›", act: function () { openWallpaperModal(); } },
      { label: "Show TV tile on dock", right: cfg.tvEnabled ? "on" : "off", act: function () {
        cfg.tvEnabled = !cfg.tvEnabled; saveCfg(); renderAll(); openAllSettingsModal();
      } },
      { label: tr("hidden_apps"), right: String(cfg.hiddenApps.length) + " ›", act: openHiddenAppsModal },
    ].concat(isWebos() ? [
      { label: "Read installed TV apps", right: cfg.tvApps ? "on" : "off", act: function () {
        setTvApps(!cfg.tvApps); openAllSettingsModal(true);
      } },
      { label: "Re-pair with TV", right: "›", act: function () { repairTv(); closeModal(); } }
    ] : []).concat([
      { label: tr("reset_order"), right: "", act: function () {
        cfg.appOrder = []; saveCfg(); renderAll(); showToast(tr("order_reset"));
      } },
      { label: "Temperature unit", right: "°" + cfg.tempUnit, act: function () {
        cfg.tempUnit = cfg.tempUnit === "C" ? "F" : "C"; saveCfg(); renderAll(); openAllSettingsModal(true);
      } },
      { label: tr("boot_screen"), right: tr(cfg.bootScreen === "wake" ? "boot_mode_wake" : cfg.bootScreen === "off" ? "off" : "boot_mode_boot"), act: function () {
        var m = ["boot", "wake", "off"];
        cfg.bootScreen = m[(m.indexOf(cfg.bootScreen || "boot") + 1) % m.length]; saveCfg(); openAllSettingsModal(true);
      } },
      { label: "  " + tr("boot_sound"), right: cfg.bootSound !== false ? "on" : "off", act: function () {
        cfg.bootSound = cfg.bootSound === false; saveCfg(); openAllSettingsModal(true);
      } },
      { label: tr("theme_to_pc"), right: cfg.themeToPc ? "on" : "off", act: function () {
        cfg.themeToPc = !cfg.themeToPc; saveCfg(); openAllSettingsModal(true);
      } },
      { label: tr("share_qr"), right: cfg.shareQr === "boot" ? "boot + Live" : cfg.shareQr === "off" ? "off" : "Live", act: function () {
        var m = ["live", "boot", "off"];
        cfg.shareQr = m[(m.indexOf(cfg.shareQr || "live") + 1) % m.length]; saveCfg(); openAllSettingsModal(true);
      } },
      { label: "🕹 " + tr("arcade_sound"), right: cfg.arcadeSound !== false ? "on" : "off", act: function () {
        cfg.arcadeSound = cfg.arcadeSound === false; saveCfg(); openAllSettingsModal(true);
      } },
      { label: "🕹 " + tr("arcade_pc_theme"), right: cfg.arcadePcTheme ? "on" : "off", act: function () {
        cfg.arcadePcTheme = !cfg.arcadePcTheme; saveCfg(); openAllSettingsModal(true);
      } }
    ]).concat(isWebos() ? [
      { label: tr("pc_power"), right: cfg.pcPower ? "on" : "off", act: function () {
        cfg.pcPower = !cfg.pcPower; saveCfg(); openAllSettingsModal(true);
        if (cfg.pcPower) {
          showToast(tr("pc_power_note"));
          ssapRequest("ssap://com.webos.service.tvpower/power/getPowerState", function () {});   // asks for TV control once
        }
      } }
    ] : []).concat([
      { label: tr("update_via_pc"), right: APP_VERSION + " ›", act: updateViaPc },
      rootRow("boot", tr("root_boot")),
      rootRow("home", tr("root_home")),
      keepRow()
    ]));
    if (keepFocus) { state.modalIdx = Math.min(focus, state.modalItems.length - 1); renderModalList(); }
  }

  // Long OK on an app tile (dock or All apps): Move / Hide. The TV tile opens Walker instead.
  function openTileMenu(app) {
    if (!app) return;
    if (app.id === "tv") { openWalkerModal(); return; }
    openModal(app.name, app.appId, [
      { label: tr("tile_move"), right: "◀▶", act: function () { closeModal(); startMove(app.id); } },
      { label: tr("tile_hide"), right: "", act: function () {
        if (cfg.hiddenApps.indexOf(app.id) < 0) cfg.hiddenApps.push(app.id);
        saveCfg(); closeModal(); showToast(tr("app_hidden"));
      } },
      { label: tr("tile_cancel"), right: "", act: closeModal }
    ]);
  }

  function openHiddenAppsModal() {
    // Drop ids of apps that no longer exist (e.g. removed from DEFAULT_APPS) so the count stays honest.
    // Not on Tizen: the installed-app list can still be loading or briefly miss an app being updated.
    var known = DEFAULT_APPS.map(function (a) { return a.id; });
    var pruned = cfg.hiddenApps.filter(function (id) { return known.indexOf(id) >= 0; });
    if (window.OMARCHY_PLATFORM !== "tizen" && !(isWebos() && cfg.tvApps) && pruned.length !== cfg.hiddenApps.length) { cfg.hiddenApps = pruned; saveCfg(); }
    var items = DEFAULT_APPS.filter(function (a) { return cfg.hiddenApps.indexOf(a.id) >= 0; }).map(function (a) {
      return { label: a.name, right: tr("hidden_show"), act: function () {
        cfg.hiddenApps = cfg.hiddenApps.filter(function (id) { return id !== a.id; });
        saveCfg(); renderAll();
        if (cfg.hiddenApps.length) openHiddenAppsModal(); else closeModal();
      } };
    });
    if (items.length > 1) items.push({ label: tr("hidden_show_all"), right: String(items.length), act: function () {
      cfg.hiddenApps = []; saveCfg(); renderAll(); closeModal(); showToast(tr("hidden_all_shown"));
    } });
    if (!items.length) items = [{ label: tr("hidden_none"), right: "", act: closeModal }];
    openModal(tr("hidden_apps"), tr("hidden_sub"), items);
  }

  function startMove(id) {
    state.movingId = id;
    state.moveSnapshot = (cfg.appOrder || []).slice();
    state.moveFocus = state.ws === 2 ? state.ws3Idx : state.dockIdx;
    // Pin the current order so every app has an explicit position while it moves.
    cfg.appOrder = orderedApps().map(function (a) { return a.id; });
    renderAll();
  }

  // Move the app being moved by `delta` places among the visible apps (hidden ones keep their slots).
  function moveBy(delta) {
    var vis = visibleApps();
    var from = -1;
    for (var i = 0; i < vis.length; i++) if (vis[i].id === state.movingId) from = i;
    var first = vis[0] && vis[0].id === "tv" ? 1 : 0;   // the TV tile stays first
    var to = Math.max(first, Math.min(vis.length - 1, from + delta));
    if (from < 0 || to === from) return;
    var order = cfg.appOrder.slice();
    order.splice(order.indexOf(state.movingId), 1);
    var anchor = order.indexOf(vis[to].id);
    order.splice(to > from ? anchor + 1 : anchor, 0, state.movingId);
    cfg.appOrder = order;
    if (state.ws === 2) state.ws3Idx = to; else state.dockIdx = to;
    renderAll();
  }

  function finishMove(save) {
    if (!save) {
      cfg.appOrder = state.moveSnapshot || [];
      if (state.ws === 2) state.ws3Idx = state.moveFocus; else state.dockIdx = state.moveFocus;
    }
    state.movingId = null;
    state.moveSnapshot = null;
    saveCfg();
    renderAll();
  }

  function openAboutModal() {
    var sub = "OmarchyTV " + APP_VERSION + " for LG webOS and Samsung Tizen (community testing — NOT a beta)\n" +
      "Based on OmarchyTV v2.13.8 for Android TV by Marco Porro (MIT License).\n" +
      "Includes " + Object.keys(DATA.themes).length + " Omarchy themes, " + [].concat.apply([], Object.keys(DATA.wallpapers).map(function (t) { return DATA.wallpapers[t]; })).length +
      " wallpapers (MIT, and 'Liftoff Sunset' by Tom Ballard), and 37 tte.js effects.";
    openModal(tr("sys_about"), sub, [
      { label: "Version", right: APP_VERSION, act: closeModal },
      { label: "Android base release", right: "2.13.8", act: closeModal },
      { label: "Wallpaper credit (tokyo-night)", right: "Liftoff Sunset — Tom Ballard", act: closeModal },
      { label: "Close", right: "ESC / Back", act: closeModal }
    ]);
  }

  function openWalkerModal() {
    function buildList(q) {
      var f = (q || "").toLowerCase();
      var apps = visibleApps().filter(function (a) { return !f || a.name.toLowerCase().indexOf(f) >= 0; });
      if (!apps.length) return [{ label: tr("walker_none"), right: "" }];
      return apps.map(function (a) {
        return { label: a.name, right: a.appId, act: function () { closeModal(); launchApp(a); } };
      });
    }
    openModal("Walker · > search", "Filter and launch installed webOS apps", buildList(""), "> search apps…", function (val) {
      state.modalItems = buildList(val);
      state.modalIdx = 0;
      renderModalList();
    });
  }

  // Wallpaper picker: follow the theme (default), plain theme colours, or any theme's background.
  // Picking applies at once and keeps the menu open on that row, so you can compare a few.
  // ---- v2.14 Retro CRT plugin: the home shrinks into a CRT set (bezel, theme-coloured rim, LED, scanlines) ----
  function crtMode() { return cfg.pluginRetroCrt === "light" || cfg.pluginRetroCrt === "strong" ? cfg.pluginRetroCrt : "off"; }
  function applyCrt() {
    var el = document.getElementById("crt-overlay"), badge = document.getElementById("crt-badge");
    if (!el) { el = document.createElement("div"); el.id = "crt-overlay"; document.body.appendChild(el); }
    if (!badge) { badge = document.createElement("div"); badge.id = "crt-badge"; badge.innerHTML = "OMARCHY TV<i></i>"; document.body.appendChild(badge); }
    var m = crtMode();
    el.className = m === "off" ? "" : "on" + (m === "strong" ? " strong" : "");
    badge.className = m === "off" ? "" : "on";
    document.body.className = document.body.className.replace(/\s*crt-strong/g, "") + (m === "strong" ? " crt-strong" : "");
    fitStage();
  }

  // ---- v2.14 OmaFire animated wallpapers (vendor/omafire: Wes Grimes' campsite, MIT) ----
  var omafireUi = null, omafireMode = "";
  /** The OmaFire mode to show: an OmaFire wallpaper, or the campfire theme with the wallpaper following the theme. */
  function omafireModeNow() {
    var m = /^omafire:(campfire|embers)$/.exec(cfg.wallpaper || "");
    if (m) return m[1];
    return cfg.theme === "campfire" && !cfg.wallpaper && !cfg.proceduralBg ? "campfire" : "";
  }
  function omafireWall(layer) {
    var mode = omafireModeNow(), m = mode ? [mode, mode] : null;
    if (!m || !window.OmaFireWall) {
      if (omafireUi) { omafireUi.destroy(); omafireUi = null; omafireMode = ""; }
      return;
    }
    if (omafireUi && omafireMode === m[1]) { omafireUi.setSound(!!cfg.omafireSound); return; }
    if (omafireUi) omafireUi.destroy();
    layer.style.position = layer.style.position || "absolute";
    omafireUi = window.OmaFireWall.mount(layer, { mode: m[1], sound: !!cfg.omafireSound });
    omafireMode = m[1];
  }

  function openWallpaperModal(keepFocus) {
    var focus = state.modalIdx;
    function pick(v) { cfg.wallpaper = v; cfg.randomWallpaper = false; saveCfg(); applyTheme(); openWallpaperModal(true); }
    var items = [
      { label: tr("random_wallpaper"), right: cfg.randomWallpaper ? "on" : "off", act: function () {
        cfg.randomWallpaper = !cfg.randomWallpaper; saveCfg(); openWallpaperModal(true);
      } },
      { label: tr("wp_follow"), right: !cfg.randomWallpaper && !cfg.wallpaper ? "✓" : (!cfg.wallpaper ? "●" : ""), act: function () { pick(""); } },
      { label: tr("wp_plain"), right: !cfg.randomWallpaper && cfg.wallpaper === "plain" ? "✓" : "", act: function () { pick("plain"); } },
      { label: "OmaFire \u00b7 " + tr("omafire_campfire"), right: cfg.wallpaper === "omafire:campfire" ? "✓" : "", act: function () { pick("omafire:campfire"); } },
      { label: "OmaFire \u00b7 " + tr("omafire_embers"), right: cfg.wallpaper === "omafire:embers" ? "✓" : "", act: function () { pick("omafire:embers"); } },
      { label: "  " + tr("omafire_sound"), right: cfg.omafireSound ? "on" : "off", act: function () {
        cfg.omafireSound = !cfg.omafireSound; saveCfg(); applyTheme(); openWallpaperModal(true);
      } }
    ];
    allWallpapers().forEach(function (p) {
      var parts = p.split("/");   // wallpapers/<theme>/<NN-name>.webp
      var name = parts[parts.length - 1].replace(/^\d+-/, "").replace(/\.\w+$/, "");
      items.push({ label: parts[parts.length - 2] + " · " + name, right: cfg.wallpaper === p ? "✓" : "", act: function () { pick(p); } });
    });
    openModal(tr("wp_title"), tr("wp_sub"), items);
    var cur = 0;
    items.forEach(function (it, i) { if (it.right === "✓") cur = i; });
    state.modalIdx = keepFocus ? Math.min(focus, items.length - 1) : cur;
    renderModalList();
  }


  // ---- v2.13: Ambilight & Lights (Philips Hue Bridge + Home Assistant) -------------------------
  function hexRgb(hex) {
    var h = (hex || "#7aa2f7").replace("#", "");
    return [parseInt(h.slice(0,2),16)||0, parseInt(h.slice(2,4),16)||0, parseInt(h.slice(4,6),16)||0];
  }
  function rgbToXy(r, g, b) {
    function lin(v) { v /= 255; return v > 0.04045 ? Math.pow((v + 0.055) / 1.055, 2.4) : v / 12.92; }
    var R = lin(r), G = lin(g), B = lin(b);
    var X = R * 0.664511 + G * 0.154324 + B * 0.162028;
    var Y = R * 0.283881 + G * 0.668433 + B * 0.047685;
    var Z = R * 0.000088 + G * 0.072310 + B * 0.986039;
    var s = X + Y + Z;
    return s < 1e-6 ? [0.3127, 0.3290] : [+(X / s).toFixed(4), +(Y / s).toFixed(4)];
  }
  function pushLightsTheme(t, dim) {
    if (!cfg.lightsHub || cfg.lightsHub === "off" || document.hidden) return;
    var L = window.OmarchyLights;
    // "Restore previous": read the lights once before the first paint of a visit.
    if (L && L.takeSnapshot(cfg.lightsLeave, !!getSnap()) && lightsReady()) {
      lightsSnapshot().then(function () { pushLightsNow(t, dim); });
      return;
    }
    pushLightsNow(t, dim);
  }
  function pushLightsNow(t, dim) {
    var rgb = hexRgb(dim ? (t.accent2 || t.accent) : t.accent);
    var pct = dim ? Math.min(12, cfg.lightsBri || 70) : (cfg.lightsBri || 70);
    if (cfg.lightsHub === "hue" && cfg.hueIp && cfg.hueUser && cfg.hueGroup) {
      fetch("http://" + cfg.hueIp + "/api/" + encodeURIComponent(cfg.hueUser) + "/groups/" + encodeURIComponent(cfg.hueGroup) + "/action", {
        method: "PUT",
        body: JSON.stringify({ on: true, xy: rgbToXy(rgb[0], rgb[1], rgb[2]), bri: Math.max(1, Math.round(pct * 2.54)), transitiontime: dim ? 20 : 8 })
      }).catch(function () {});
    } else if (cfg.lightsHub === "ha" && cfg.haUrl && cfg.haToken && cfg.haEntity) {
      fetch(cfg.haUrl.replace(/\/+$/, "") + "/api/services/light/turn_on", {
        method: "POST",
        headers: { "Authorization": "Bearer " + cfg.haToken, "Content-Type": "application/json" },
        body: JSON.stringify({ entity_id: cfg.haEntity.split(/[,\s]+/).filter(Boolean), rgb_color: rgb, brightness_pct: pct, transition: dim ? 2 : 1 })
      }).catch(function () {});
    }
  }
  // v2.14: the hub follows OmarchyTV being on screen (lights.js). Hidden page = another app or standby.
  var LIGHTS = window.OmarchyLights, lightsAway = false;
  function lightsReady() {
    if (cfg.lightsHub === "hue") return !!(cfg.hueIp && cfg.hueUser && cfg.hueGroup);
    if (cfg.lightsHub === "ha") return !!(cfg.haUrl && cfg.haToken && cfg.haEntity);
    return false;
  }
  function hueBase() { return "http://" + cfg.hueIp + "/api/" + encodeURIComponent(cfg.hueUser); }
  function haBase() { return cfg.haUrl.replace(/\/+$/, ""); }
  function haHeaders() { return { "Authorization": "Bearer " + cfg.haToken, "Content-Type": "application/json" }; }
  function haIds() { return cfg.haEntity.split(/[,\s]+/).filter(function (e) { return /^light\.[a-z0-9_]+$/.test(e); }); }
  function getSnap() { try { return localStorage.getItem("omarchy_lights_snapshot") || ""; } catch (e) { return ""; } }
  function setSnap(v) { var k = "omarchy_lights_snapshot"; try { if (v) localStorage.setItem(k, v); else localStorage.removeItem(k); } catch (e) {} }
  /** Before painting: remember how the lights were ("Restore previous" only). Resolves either way. */
  function lightsSnapshot() {
    var LIGHTS = window.OmarchyLights;
    if (!LIGHTS || !lightsReady() || !LIGHTS.takeSnapshot(cfg.lightsLeave, !!getSnap())) return Promise.resolve();
    var j = function (r) { return r.json(); };
    var p = cfg.lightsHub === "hue"
      ? Promise.all([fetch(hueBase() + "/groups/" + encodeURIComponent(cfg.hueGroup)).then(j), fetch(hueBase() + "/lights").then(j)])
          .then(function (a) { return LIGHTS.hueSnapshot(a[0], a[1]); })
      : Promise.all(haIds().map(function (e) { return fetch(haBase() + "/api/states/" + e, { headers: haHeaders() }).then(j); }))
          .then(function (a) { var o = {}; haIds().forEach(function (e, i) { o[e] = LIGHTS.haState(a[i]); }); return o; });
    return p.then(function (o) { if (o && Object.keys(o).length) setSnap(JSON.stringify(o)); }).catch(function () {});
  }
  function lightsLeave() {
    if (lightsAway) return;
    lightsAway = true;
    if (!LIGHTS || !lightsReady()) return;
    var snap = getSnap(), act = LIGHTS.action(cfg.lightsLeave, !!snap);
    if (act === "none") return;
    if (act === "restore") {
      setSnap("");
      var o = {}; try { o = JSON.parse(snap); } catch (e) {}
      Object.keys(o).forEach(function (id) {
        if (cfg.lightsHub === "hue") {
          if (/^[0-9A-Za-z_-]+$/.test(id)) fetch(hueBase() + "/lights/" + id + "/state", { method: "PUT", body: JSON.stringify(LIGHTS.hueRestoreBody(o[id])), keepalive: true }).catch(function () {});
        } else if (/^light\.[a-z0-9_]+$/.test(id)) {
          var c = LIGHTS.haRestoreCall(id, o[id]);
          fetch(haBase() + "/api/services/light/" + c.service, { method: "POST", headers: haHeaders(), body: JSON.stringify(c.body), keepalive: true }).catch(function () {});
        }
      });
      return;
    }
    if (cfg.lightsHub === "hue") fetch(hueBase() + "/groups/" + encodeURIComponent(cfg.hueGroup) + "/action", { method: "PUT", body: JSON.stringify({ on: false, transitiontime: 8 }), keepalive: true }).catch(function () {});
    else fetch(haBase() + "/api/services/light/turn_off", { method: "POST", headers: haHeaders(), body: JSON.stringify({ entity_id: haIds(), transition: 1 }), keepalive: true }).catch(function () {});
  }
  function lightsBack() {
    if (!lightsAway) return;
    lightsAway = false;
    if (!lightsReady()) return;
    pushLightsTheme(currentThemeObj(), false);   // takes the snapshot first when "Restore previous" needs one
  }
  document.addEventListener("visibilitychange", function () { if (document.hidden) lightsLeave(); else lightsBack(); });
  window.addEventListener("pagehide", lightsLeave);
  document.addEventListener("webOSRelaunch", lightsBack);

  // v2.14 Always-animated wallpaper: pause the loop behind Cinemarchy, the screensaver or another app; resume on Home.
  function wallpaperLoopTick() {
    var wv = document.getElementById("wallpaper-video"), L = window.OmarchyWpLoop;
    if (!wv || !L || !cfg.wallpaperLoop || wv.style.display !== "block") return;
    var play = L.shouldPlay({ loop: true, hidden: document.hidden,
      saver: document.getElementById("saver-overlay").className === "active",
      cinemarchy: !!cineUi });
    try { if (play && wv.paused) wv.play().catch(function () {}); else if (!play && !wv.paused) wv.pause(); } catch (e) {}
  }
  setInterval(wallpaperLoopTick, 1000);
  document.addEventListener("visibilitychange", wallpaperLoopTick);

  function openLightsModal() {
    var hub = cfg.lightsHub || "off";
    var bri = cfg.lightsBri || 70;
    var items = [
      { label: "Lights hub", right: hub === "hue" ? "Philips Hue" : (hub === "ha" ? "Home Assistant" : "off"), act: function () {
        var order = ["off", "hue", "ha"];
        cfg.lightsHub = order[(order.indexOf(hub) + 1) % order.length];
        saveCfg(); openLightsModal();
      } },
      { label: "Brightness", right: bri + "%", act: function () {
        var steps = [10, 25, 40, 55, 70, 85, 100];
        cfg.lightsBri = steps[(steps.indexOf(bri) + 1) % steps.length];
        saveCfg(); applyTheme(); openLightsModal();
      } }
    ];
    if (hub === "hue") {
      items.push({ label: "Find Hue Bridge (meethue.com)", right: cfg.hueIp || "discover ›", act: function () {
        fetch("https://discovery.meethue.com").then(function (r) { return r.json(); }).then(function (a) {
          if (a && a[0] && a[0].internalipaddress) { cfg.hueIp = a[0].internalipaddress; saveCfg(); showToast("Hue Bridge: " + cfg.hueIp); openLightsModal(); }
          else showToast("No Hue Bridge found on LAN");
        }).catch(function () { showToast("Discovery failed — enter IP manually"); });
      } });
      items.push({ label: "Hue Bridge IP (use input above)", right: cfg.hueIp || "—", act: function () {
        var v = (document.getElementById("modal-input").value || "").trim();
        if (v) { cfg.hueIp = v; saveCfg(); openLightsModal(); }
      } });
      items.push({ label: "Pair (press bridge button first)", right: cfg.hueUser ? "paired" : "pair ›", act: function () {
        if (!cfg.hueIp) { showToast("Set Bridge IP first"); return; }
        fetch("http://" + cfg.hueIp + "/api", { method: "POST", body: JSON.stringify({ devicetype: "omarchytv#webos" }) })
          .then(function (r) { return r.json(); }).then(function (a) {
            if (a && a[0] && a[0].success && a[0].success.username) { cfg.hueUser = a[0].success.username; cfg.hueGroup = cfg.hueGroup || "0"; saveCfg(); showToast("Hue paired"); openLightsModal(); }
            else showToast((a && a[0] && a[0].error && a[0].error.description) || "Press link button on bridge, then retry");
          }).catch(function () { showToast("Cannot reach Hue Bridge"); });
      } });
      items.push({ label: "Entertainment area / Room ID", right: cfg.hueGroup || "0 (all)", act: function () {
        var v = (document.getElementById("modal-input").value || "").trim();
        if (v) { cfg.hueGroup = v; saveCfg(); openLightsModal(); }
      } });
    } else if (hub === "ha") {
      items.push({ label: "Home Assistant URL (use input above)", right: cfg.haUrl || "—", act: function () {
        var v = (document.getElementById("modal-input").value || "").trim();
        if (v) { cfg.haUrl = v; saveCfg(); openLightsModal(); }
      } });
      items.push({ label: "Long-lived token (use input above)", right: cfg.haToken ? "••••••" : "—", act: function () {
        var v = (document.getElementById("modal-input").value || "").trim();
        if (v) { cfg.haToken = v; saveCfg(); openLightsModal(); }
      } });
      items.push({ label: "Light entity_id(s) (use input above)", right: cfg.haEntity || "—", act: function () {
        var v = (document.getElementById("modal-input").value || "").trim();
        if (v) { cfg.haEntity = v; saveCfg(); openLightsModal(); }
      } });
    }
    if (hub !== "off") {
      items.push({ label: "When leaving the launcher", right: { off: "Turn off", restore: "Restore previous", keep: "Keep on" }[LIGHTS.policy(cfg.lightsLeave)], act: function () {
        cfg.lightsLeave = LIGHTS.next(cfg.lightsLeave); setSnap(""); saveCfg(); openLightsModal();
      } });
      items.push({ label: "Apply theme colours now", right: "▶", act: function () { pushLightsTheme(currentThemeObj(), false); showToast("Pushed theme to lights"); } });
      items.push({ label: "Forget hub credentials (confirm with OK)", right: "forget", act: function () {
        openModal("Forget hub?", "Remove saved Hue / Home Assistant credentials from this TV?", [
          { label: "Cancel", right: "", act: openLightsModal },
          { label: "Forget hub", right: "confirm", act: function () {
            cfg.lightsHub = "off"; cfg.hueUser = ""; cfg.haToken = ""; saveCfg(); openLightsModal();
          } }
        ]);
      } });
    }
    openModal("Ambilight & Lights", "Sync Philips Hue or Home Assistant lights with Omarchy themes & Cinemarchy", items, "IP / URL / entity_id / token…");
  }

  // v2.13 helpers: HTML-escape for modal labels built from feed data (renderModalList uses innerHTML).
  function escHtml(s) {
    return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
    });
  }

  var dhhState = { videos: null, at: 0, error: "", loading: false, timer: 0 };
  function dhhFetchText(url) {
    return fetch(url).then(function (r) { if (!r.ok) throw new Error("HTTP " + r.status); return r.text(); });
  }
  function loadDhh(force, then) {
    var D = window.OmarchyDhh;
    if (!D || dhhState.loading) return;
    dhhState.loading = true;
    D.load(window.localStorage, dhhFetchText, force, Date.now()).then(function (res) {
      dhhState.loading = false;
      dhhState.videos = res.videos; dhhState.at = res.at; dhhState.error = res.error || "";
      if (then) then();
    });
    clearTimeout(dhhState.timer);
    dhhState.timer = setTimeout(function () { loadDhh(true); }, D.TTL_MS);   // every 6 h while the app runs
  }
  function agoText(t) {
    if (!t) return "never";
    var m = Math.round((Date.now() - t) / 60000);
    return m < 1 ? "just now" : m < 60 ? m + " min ago" : Math.round(m / 60) + " h ago";
  }

  // Launch a webOS app. webOSTV.js isn't bundled, so window.webOS.service is usually missing:
  // fall back to PalmServiceBridge, which every webOS web app has.
  var lunaCalls = [];
  function lunaLaunch(id, params) {
    var parameters = params ? { id: id, params: params } : { id: id };
    if (window.webOS && window.webOS.service && window.webOS.service.request) {
      window.webOS.service.request("luna://com.webos.applicationManager", { method: "launch", parameters: parameters });
      return true;
    }
    if (window.PalmServiceBridge) {
      var b = new PalmServiceBridge();
      lunaCalls.push(b);
      b.onservicecallback = function () { lunaCalls.splice(lunaCalls.indexOf(b), 1); };
      b.call("luna://com.webos.applicationManager/launch", JSON.stringify(parameters));
      return true;
    }
    return false;
  }

  // YouTube deep links. Not verified on a real TV: webOS takes contentTarget, Tizen a v=ID payload.
  // No YouTube app took it: the in-app embed (unless the embed itself is falling back), then the TV browser.
  function openYoutubeApp(id, fromEmbed) {
    var url = "https://www.youtube.com/watch?v=" + id;
    try {
      if (lunaLaunch("youtube.leanback.v4", { contentTarget: "https://www.youtube.com/tv?v=" + id })) return;
      if (window.tizen && window.tizen.application && window.tizen.ApplicationControl) {
        var ac = new window.tizen.ApplicationControl("http://tizen.org/appcontrol/operation/view", null, null, null,
          [new window.tizen.ApplicationControlData("PAYLOAD", ['{"values":"v=' + id + '"}'])]);
        window.tizen.application.launchAppControl(ac, "111299001912", null, function () { window.tizen.application.launch("111299001912"); });
        return;
      }
    } catch (e) {}
    if (!fromEmbed) { openEmbed(id); return; }
    try { if (window.open(url, "_blank")) return; } catch (e2) {}
    showToast("Open on YouTube: " + url);
  }

  // Optional official IFrame embed ("Play inside Cinemarchy", default off). Commands go over the documented
  // postMessage channel of the embed (no stream extraction). Errors 2/5/100/101/150/153 fall back to the app.
  var embed = { id: "", dur: 0, t: 0, playing: true, idx: 0, timer: 0 };
  var EMBED_ROWS = ["Start", "25%", "50%", "75%", "−30 s", "+30 s", "Play / pause", "Open in YouTube app"];
  function embedCmd(func, args) {
    var f = document.getElementById("yt-embed-frame");
    if (f && f.contentWindow) f.contentWindow.postMessage(JSON.stringify({ event: "command", func: func, args: args || [] }), "*");
  }
  function renderEmbedRail() {
    var rail = document.getElementById("yt-embed-rail");
    if (!rail) return;
    rail.innerHTML = EMBED_ROWS.map(function (r, i) {
      return '<span class="yt-chip' + (i === embed.idx ? " focused" : "") + '">' + (i < 4 ? "[" + (i + 1) + "] " : "") + escHtml(r) + "</span>";
    }).join("");
  }
  function embedAct(i) {
    if (i < 4) { if (embed.dur) embedCmd("seekTo", [embed.dur * i / 4, true]); }
    else if (i === 4) embedCmd("seekTo", [Math.max(0, embed.t - 30), true]);
    else if (i === 5) embedCmd("seekTo", [embed.t + 30, true]);
    else if (i === 6) { embedCmd(embed.playing ? "pauseVideo" : "playVideo"); embed.playing = !embed.playing; }
    else { var id = embed.id; closeEmbed(); openYoutubeApp(id, true); }
  }
  function openEmbed(id) {
    closeModal();
    var ov = document.getElementById("yt-embed");
    if (!ov) {
      ov = document.createElement("div");
      ov.id = "yt-embed";
      document.body.appendChild(ov);
    }
    embed = { id: id, dur: 0, t: 0, playing: true, idx: 0, timer: 0 };
    ov.innerHTML = '<iframe id="yt-embed-frame" allow="autoplay; encrypted-media" src="https://www.youtube-nocookie.com/embed/' + id +
      '?enablejsapi=1&autoplay=1&rel=0&playsinline=1"></iframe><div id="yt-embed-rail"></div>' +
      '<div class="yt-note">Official YouTube embed · Back closes · ←/→ + OK on the chapter rail</div>';
    ov.className = "open";
    state.embedOpen = true;
    renderEmbedRail();
    var f = document.getElementById("yt-embed-frame");
    f.onload = function () { f.contentWindow.postMessage(JSON.stringify({ event: "listening", id: 1 }), "*"); };
    embed.timer = setTimeout(function () { if (!embed.dur) { showToast("Embed did not start · opening the YouTube app"); var i2 = embed.id; closeEmbed(); openYoutubeApp(i2, true); } }, 20000);
  }
  function closeEmbed() {
    var ov = document.getElementById("yt-embed");
    clearTimeout(embed.timer);
    if (ov) { ov.className = ""; ov.innerHTML = ""; }
    state.embedOpen = false;
    renderAll();
  }
  window.addEventListener("message", function (ev) {
    if (!state.embedOpen || !/youtube(-nocookie)?\.com$/.test(String(ev.origin).replace(/^https?:\/\/(www\.)?/, ""))) return;
    var m; try { m = JSON.parse(ev.data); } catch (e) { return; }
    if (m.event === "onError") {
      var id = embed.id;
      showToast("YouTube does not allow this video here (error " + m.info + ") · opening the YouTube app");
      closeEmbed(); openYoutubeApp(id, true);
    } else if (m.event === "infoDelivery" && m.info) {
      if (m.info.duration) embed.dur = m.info.duration;
      if (m.info.currentTime != null) embed.t = m.info.currentTime;
    }
  });
  function embedKey(k, isBack) {
    if (isBack) closeEmbed();
    else if (k === "ArrowLeft") embed.idx = (embed.idx + EMBED_ROWS.length - 1) % EMBED_ROWS.length;
    else if (k === "ArrowRight") embed.idx = (embed.idx + 1) % EMBED_ROWS.length;
    else if (k === "Enter") embedAct(embed.idx);
    else if (k >= "1" && k <= "4") embedAct(parseInt(k, 10) - 1);
    renderEmbedRail();
  }
  function playDhh(v) {
    if (cfg.embedPlayer) openEmbed(v.id);
    else { closeModal(); openYoutubeApp(v.id); }
  }

  // Cinemarchy (cinemarchy.js): a full-screen hub in #stage (inside the Retro CRT set when that is on). The old
  // four-tab menu is gone; every way in (C key, menus, the dock) lands here.
  var cineUi = null;
  function cineMix(a, b, k) {
    function c(h, i) { return parseInt(h.substr(1 + i * 2, 2), 16) || 0; }
    if (!/^#[0-9a-f]{6}$/i.test(a) || !/^#[0-9a-f]{6}$/i.test(b)) return a;
    var o = "#"; for (var i = 0; i < 3; i++) { var v = Math.round(c(a, i) * (1 - k) + c(b, i) * k); o += (v < 16 ? "0" : "") + v.toString(16); }
    return o;
  }
  function openCinemarchy() {
    if (cineUi || !window.OmarchyCinemarchy) return;
    if (state.modalOpen) closeModal();
    pushLightsTheme(currentThemeObj(), true);
    var th = currentThemeObj(), D = window.OmarchyDhh;
    if (D && !dhhState.videos && !dhhState.loading) loadDhh(false, function () { if (cineUi) cineUi.refresh(); });
    var streamIds = ["jellyfin", "plex", "netflix", "prime", "disney", "youtube", "raiplay", "stremio"];
    cineUi = window.OmarchyCinemarchy.open(document.getElementById("stage") || document.body, {
      tr: tr, version: CINEMARCHY_VERSION, logoHtml: DATA.cinemarchyLogo ? blockArt(DATA.cinemarchyLogo, 1, 1, "width:auto;height:6.5vh") : "", cfg: cfg, save: saveCfg, store: window.localStorage,
      theme: { bg: th.bg, fg: th.fg, bright: th.fgBright, dim: cineMix(th.fg, th.bg, 0.5), accent: th.accent, green: th.green, cyan: th.cyan },
      apps: function () {   // read each time: the TV's app list and inputs can change while Cinemarchy is open
        return streamIds.map(function (id) { return DEFAULT_APPS.filter(function (x) { return x.id === id; })[0]; }).filter(Boolean).map(function (a) {
          return { id: a.appId || a.id, name: a.name, launch: function () { closeCinemarchy(); launchApp(a); } };
        }).concat([{ id: "x.com", name: "X", sub: "x.com · videos", launch: function () { closeCinemarchy(); openUrlOnTv("https://x.com/home"); } }]);
      },
      inputs: function () {
        return TV_INPUTS.map(function (inp) {
          var label = inp.tizenInput && inp.sub.indexOf(inp.name) === 0 ? inp.sub : inp.name;   // Tizen sub: "HDMI 1 · Connected"
          return { name: label, sub: label === inp.sub ? "" : inp.sub, launch: function () { closeCinemarchy(); launchApp(inp); } };
        });
      },
      dhh: {
        list: function () { return D ? dhhState.videos || D.curated() : []; },
        isNew: function (v) { return D ? D.isNew(v, D.lastSeen(window.localStorage), Date.now()) : false; },
        status: function () { return dhhState.loading ? "…" : dhhState.error ? "offline" : agoText(dhhState.at); },
        load: function (force, then) { loadDhh(force, then); },
        play: function (v) { if (D && dhhState.videos) D.markSeen(window.localStorage, dhhState.videos); closeCinemarchy(); playDhh(v); }
      },
      embed: { get: function () { return !!cfg.embedPlayer; }, set: function (on) { cfg.embedPlayer = !!on; saveCfg(); } },
      saverPreview: function () { closeCinemarchy(); state.pauseTitle = "Preview · OK to resume"; startScreensaver("‖ PAUSED · " + state.pauseTitle); },
      fetchText: function (url) { return fetch(url).then(function (r) { if (!r.ok) throw new Error("HTTP " + r.status); return r.text(); }); },
      pc: {   // the Omarchy PC's media folders, through its bridge (omarchy-tv webos; read-only, the ask code)
        available: function () { return pcReady(); },
        base: function () { return pcBase(); },
        list: function (path) {
          return fetch(pcBase() + "/v1/media/list?path=" + encodeURIComponent(path || ""), { headers: { "X-Omarchy-Token": cfg.grokbotCode || "" } })
            .then(function (r) { return r.json().then(function (j) { if (!r.ok) throw new Error(j.message || j.error || ("HTTP " + r.status)); return j; }); });
        }
      },
      onPlay: function (on) { cinePlaying = !!on; },
      onExit: function () { cineUi = null; cinePlaying = false; renderAll(); }
    });
  }
  var cinePlaying = false;
  function closeCinemarchy() { if (cineUi) { var u = cineUi; cineUi = null; u.destroy(); cinePlaying = false; renderAll(); } }
  function openCinemarchyModal() { openCinemarchy(); }
  window.OMARCHY_OPEN_CINEMARCHY = openCinemarchy;
  // While Cinemarchy is open its keys are its own (the TV keyboard still types into its URL field).
  window.addEventListener("keydown", function (e) {
    if (!cineUi) return;
    if (lift.el && lift.el.style.display === "block") return;
    var n = window.OmarchyCinemarchy.keyName(e);
    if (n && cineUi.key(n, e)) {
      e.preventDefault(); e.stopPropagation();
      if (e.stopImmediatePropagation) e.stopImmediatePropagation();
    } else if (!(e.target && /INPUT/.test(e.target.tagName))) { e.stopPropagation(); if (e.stopImmediatePropagation) e.stopImmediatePropagation(); }
  }, true);


  function openOmarchyMenu() {
    openModal("Omarchy", "", [
      { icon: "󰕼", label: "Cinemarchy", right: "C ›", act: function () { openCinemarchyModal(0); } },
      { icon: "🎮", label: "OmarchyTV Arcade", right: "V-HOLD · RICE WARS · FORK BOMB ›", act: openArcade },
      { icon: "", label: tr("menu_plugins"), right: "›", act: openPluginsModal },
      { icon: "󰀻", label: tr("menu_apps"), right: "›", act: openWalkerModal },
      { icon: "󰸌", label: tr("sys_themes"), right: "›", act: openThemesModal },
      { icon: "", label: tr("wp_title"), right: "›", act: function () { openWallpaperModal(); } },
      { icon: "󰖐", label: tr("wx_title"), right: "›", act: openWeatherModal },
      { icon: "󱄄", label: tr("sys_saver"), right: "›", act: openSaverModal },
      { icon: "", label: tr("menu_setup"), right: "›", act: openAllSettingsModal },
      { icon: "", label: tr("menu_terminal"), right: "›", act: openGrokTui },
      { icon: "󰚩", label: "Grok Bot & AI", right: "›", act: openGrokBotHub },
      { icon: "󰖟", label: "Web apps", right: "›", act: openWebAppsModal },
      { icon: "󰾆", label: "AI meter", right: cfg.aiCredits === "dollars" ? "dollars" : "percent", act: function () {
        cfg.aiCredits = cfg.aiCredits === "dollars" ? "percent" : "dollars";
        saveCfg();
        renderWidgets();
        var row = state.modalItems[state.modalIdx];
        if (row) row.right = cfg.aiCredits === "dollars" ? "dollars" : "percent";
        renderModalList();
      } },
      { label: tr("sys_about"), right: APP_VERSION, act: openAboutModal }
    ]);
  }

  var saverStatusOverride = "";
  window.OmarchySaver = {
    text: function () { return cfg.saverArt === "cinemarchy" && DATA.cinemarchyLogo ? DATA.cinemarchyLogo : DATA.logo; },
    saverMode: function () { return cfg.saverMode || "logo"; },
    speed: function () { return cfg.saverSpeed === "slow" || cfg.saverSpeed === "fast" ? cfg.saverSpeed : "normal"; },
    saverVariant: function () { return cfg.saverArt === "aquarium-classic" ? "classic" : "homage"; },
    palette: function () { return currentThemeObj(); },
    statusOverride: function () { return saverStatusOverride; },
    color: function () { return currentThemeObj().accent; },
    clockColor: function () { return currentThemeObj().fg; },
    showClock: function () { return !!cfg.saverShowClock; },
    clockText: function (d) { return clockText(d); },
    widthFraction: function () { return cfg.saverLogoScale || 0.65; },
    exclude: function () { return ""; },
    forceEffect: function () { return ""; },
    forceMode: function () { return "bundle"; },
    dismiss: function () { stopScreensaver(); },
    log: function (m) { console.log("[OmarchySaver]", m); }
  };

  function startScreensaver(statusText) {
    saverStatusOverride = typeof statusText === "string" ? statusText : "";
    var ov = document.getElementById("saver-overlay");
    var fr = document.getElementById("saver-frame");
    // v2.14 Live screensaver: a screen of its own (living.js fullscreen), no art under it.
    fr.src = cfg.living && window.OmarchyLiving ? "about:blank" : "saver/index.html";
    ov.className = "active";
    livingStart(ov);
  }

  // ---- v2.14 Living screensaver (living.js): weather here, the rest from the PC bridge every 3 s ----
  var livingUi = null, livingTimer = null, livingData = {};
  function livingWeather() {
    var wx = state.weather;
    if (!wx || typeof wx.tempC !== "number") return null;
    var c = Number(wx.code) || 0, keys = [95, 80, 71, 61, 51, 45, 3, 2, 1, 0], k = 0;
    for (var i = 0; i < keys.length; i++) if (c >= keys[i]) { k = keys[i]; break; }
    var f = cfg.tempUnit === "F", t = function (v) { return Math.round(f ? v * 9 / 5 + 32 : v); };
    return { t: t(wx.tempC), unit: f ? "F" : "", code: c, desc: (tr("wmo") || {})[k] || "", place: wx.city || cfg.weatherCity,
      min: typeof wx.minC === "number" ? t(wx.minC) : undefined, max: typeof wx.maxC === "number" ? t(wx.maxC) : undefined };
  }
  function livingLabels() {
    return { weather: tr("living_weather"), notes: tr("living_notes_title"), x: tr("living_x_title"), github: "github",
      waiting: tr("living_waiting"), x_not_set_up: tr("living_x_not_set_up"), x_budget: tr("living_x_budget"), x_error: tr("living_x_error"), x_user: tr("living_x_error") };
  }
  function livingStart(host) {
    livingStop();
    if (!cfg.living || !window.OmarchyLiving) return;
    var t = (DATA.themes[cfg.theme] || {});
    livingUi = window.OmarchyLiving.fullscreen(host, { qr: cfg.shareQr !== "off" && window.OmarchyQR ? window.OmarchyQR.PROJECT_URL : "", labels: livingLabels(), color: t.foreground || "#c0caf5", accent: t.accent || "#7aa2f7",
      accent2: t.magenta || t.cyan, bg: t.background || "#000", user: cfg.promptUser || "user", host: cfg.promptHost || "omarchytv",
      dateText: function (d) { try { return d.toLocaleDateString(getLang(), { weekday: "short", day: "numeric", month: "short" }); } catch (e) { return d.toDateString(); } } });
    livingData = { weather: livingWeather() };
    livingUi.update(livingData);
    var tick = function () {
      if (!livingUi) return;
      livingData.weather = livingWeather();
      if (!pcReady()) { livingUi.update(livingData); return; }
      pcPost("/v1/living", { notes: !!cfg.livingNotes, github: cfg.livingGithub !== false, x: cfg.livingX || "off", radar: !!cfg.livingRadar }).then(function (r) {
        if (!livingUi || r.status !== 200) return;
        var j = r.j;
        var ps = state.pcStatus;
        livingData = { weather: livingWeather(), notes: j.notes, github: j.github, x: j.x, xMode: j.xMode, xError: j.xError, radar: j.radar,
          pc: ps && typeof ps.cpu === "number" ? { cpu: ps.cpu, ram: ps.ram, host: ps.host } : null };
        livingUi.update(livingData);
      }, function () {});
    };
    tick();
    livingTimer = setInterval(tick, 3000);
  }
  function livingStop() {
    if (livingTimer) { clearInterval(livingTimer); livingTimer = null; }
    if (livingUi) { livingUi.destroy(); livingUi = null; }
  }

  function stopScreensaver() {
    var ov = document.getElementById("saver-overlay");
    var fr = document.getElementById("saver-frame");
    livingStop();
    if (ov.className === "active") {
      ov.className = "";
      fr.src = "about:blank";
      animateLogo();
    }
    // Keys pressed inside the saver iframe never reach this document, so restart the idle clock here.
    markInput();
  }

  // Idle timer: start the screensaver after cfg.saverIdleMin minutes without input (0 = never),
  // only while OmarchyTV is on screen and no menu is open.
  var lastInput = Date.now();
  function markInput() { lastInput = Date.now(); }
  ["keydown", "mousemove", "mousedown", "wheel", "touchstart"].forEach(function (t) {
    document.addEventListener(t, markInput, true);
  });
  document.addEventListener("visibilitychange", markInput);
  setInterval(function () {
    if (!cfg.saverIdleMin || document.hidden || state.modalOpen || arcadeUi || cineUi) return;
    if (document.getElementById("saver-overlay").className === "active") return;
    if (Date.now() - lastInput >= cfg.saverIdleMin * 60000) startScreensaver();
  }, 5000);

  // Keep LG's own screensaver from covering OmarchyTV's. The tvpower service asks registered clients
  // before it starts its screensaver and asks again every few minutes: ack:false vetoes, ack:true lets it
  // run. Veto only while we are on screen and our screensaver is enabled; in the background (HDMI, Live
  // TV, other apps) or with ours set to "never", LG's burn-in protection still runs. Same handshake as
  // Kodi on webOS (xbmc/windowing/wayland/OSScreenSaverWebOS.cpp). window.webOS is not loaded here,
  // so this uses PalmServiceBridge directly.
  if (window.PalmServiceBridge) {
    var lgSaverSub = new PalmServiceBridge(); // kept in scope so the subscription stays alive
    lgSaverSub.onservicecallback = function (msg) {
      var req;
      try { req = JSON.parse(msg); } catch (e) { return; }
      if (req.state !== "Active") return;
      var allow = document.hidden || !cfg.saverIdleMin;
      new PalmServiceBridge().call("luna://com.webos.service.tvpower/power/responseScreenSaverRequest",
        JSON.stringify({ clientName: "com.omarchytv.home", ack: allow, timestamp: req.timestamp }));
    };
    lgSaverSub.call("luna://com.webos.service.tvpower/power/registerScreenSaverRequest",
      JSON.stringify({ subscribe: true, clientName: "com.omarchytv.home" }));
  }

  function showToast(msg) {
    var t = document.getElementById("toast");
    t.textContent = msg;
    t.style.display = "block";
    clearTimeout(t._timer);
    t._timer = setTimeout(function () { t.style.display = "none"; }, 2600);
  }

  // Turn off after idle. Apps may not call tvpower's turnOffScreen directly ("Denied method call"), but
  // the TV's own remote-control API (SSAP on ws://127.0.0.1:3000, used by LG's phone app and Home
  // Assistant) allows it once the user approves this app in an on-screen prompt. The client key from
  // that approval is kept in localStorage.
  var SSAP_KEY = "omarchytv_ssap_key";
  function tvControlApproved() {
    try { return !!localStorage.getItem(SSAP_KEY); } catch (e) { return false; }
  }
  // Newer webOS (e.g. 7.x) only answers on wss://...:3001, older ones on ws://...:3000: try both, in that order.
  var SSAP_URLS = ["ws://127.0.0.1:3000", "wss://127.0.0.1:3001"];
  // hold(payload, release): optional, runs while the session is still open; the socket closes on release().
  function ssapRequest(uri, done, reqPayload, urlIdx, hold) {
    var ws, finished = false, opened = false, timer;
    urlIdx = urlIdx || 0;
    function finish(ok, info, data) {
      if (finished) return;
      finished = true; clearTimeout(timer);
      try { ws.close(); } catch (e) {}
      if (!ok && !opened && info === "unavailable" && urlIdx + 1 < SSAP_URLS.length) return ssapRequest(uri, done, reqPayload, urlIdx + 1, hold);
      if (done) done(ok, info, data);
    }
    try { ws = new WebSocket(SSAP_URLS[urlIdx]); } catch (e) { return finish(false, "unavailable"); }
    timer = setTimeout(function () { finish(false, "no answer"); }, 90000); // time to accept a prompt
    // One pairing covers everything the launcher asks the TV for (power, installed apps, launching them).
    var payload = { forcePairing: false, pairingType: "PROMPT", manifest: { appVersion: "1.0", manifestVersion: 1,
      permissions: ["CONTROL_POWER", "CONTROL_TV_SCREEN", "READ_POWER_STATE", "READ_INSTALLED_APPS", "LAUNCH"] } };
    try { var key = localStorage.getItem(SSAP_KEY); if (key) payload["client-key"] = key; } catch (e) {}
    ws.onerror = function () { finish(false, "unavailable"); };
    ws.onopen = function () { opened = true; ws.send(JSON.stringify({ type: "register", id: "reg", payload: payload })); };
    ws.onmessage = function (m) {
      var msg;
      try { msg = JSON.parse(m.data); } catch (e) { return; }
      if (msg.id === "reg" && msg.type === "registered") {
        try { localStorage.setItem(SSAP_KEY, msg.payload["client-key"]); } catch (e) {}
        ws.send(JSON.stringify({ type: "request", id: "req", uri: uri, payload: reqPayload || {} }));
      } else if (msg.id === "reg" && msg.type === "error") {
        try { localStorage.removeItem(SSAP_KEY); } catch (e) {}
        finish(false, "not approved");
      } else if (msg.id === "req") {
        var ok = msg.type === "response" && !(msg.payload && msg.payload.returnValue === false);
        if (ok && hold) { clearTimeout(timer); timer = setTimeout(function () { finish(true, msg.type, msg.payload); }, 15000);
          try { return hold(msg.payload, function () { finish(true, msg.type, msg.payload); }); } catch (e) {} }
        finish(msg.type === "response" && !(msg.payload && msg.payload.returnValue === false), msg.type, msg.payload);
      }
    };
  }

  // LG webOS: the apps installed on the TV (NOW, MUBI, ...) with their own icons. Dev-mode apps get an empty list
  // from applicationManager over luna, so this goes through SSAP, which needs the one-time "allow" prompt above.
  // The last list is cached (titles, ids, icon URLs only) so the dock is right at once on the next start.
  var BUILTIN_APPS = DEFAULT_APPS;
  var TVAPPS_CACHE = "omarchytv_tv_apps", TVAPPS_DENIED = "omarchytv_tv_apps_denied";
  var TVA = window.OmarchyTvApps || null;
  function isWebos() {
    return window.OMARCHY_PLATFORM !== "tizen" && !!TVA &&
      !!(window.PalmServiceBridge || window.webOS || /Web0S|webOS/i.test((navigator && navigator.userAgent) || ""));
  }
  function useTvApps(lps) {
    var apps = TVA.merge(BUILTIN_APPS, lps);
    if (apps) applyAppList(apps);
    return !!apps;
  }
  function refreshTvApps(manual) {
    if (!isWebos() || !cfg.tvApps) return;
    try { if (!manual && localStorage.getItem(TVAPPS_DENIED)) return; } catch (e) {}
    if (manual) showToast("Accept OmarchyTV on the TV prompt if it appears");
    ssapRequest("ssap://com.webos.applicationManager/listLaunchPoints", function (ok, info, data) {
      var lps = ok && data && data.launchPoints;
      if (lps) brokenIcons = {};   // a fresh SSAP session: its icon URLs load again
      if (lps && useTvApps(lps)) {
        try {
          localStorage.removeItem(TVAPPS_DENIED);
          localStorage.setItem(TVAPPS_CACHE, JSON.stringify(lps.map(function (l) {
            return { id: l.id, title: l.title, launchPointId: l.launchPointId, lptype: l.lptype, icon: l.icon, largeIcon: l.largeIcon,
              extraLargeIcon: l.extraLargeIcon, mediumLargeIcon: l.mediumLargeIcon, iconColor: l.iconColor, params: l.params, visible: l.visible };
          })));
        } catch (e) {}
        if (manual) showToast("TV apps updated");
      } else {
        if (info === "not approved") { try { localStorage.setItem(TVAPPS_DENIED, "1"); } catch (e) {} }
        if (manual) showToast("Could not read the TV apps (" + (info || "no list") + "), the built-in list stays");
      }
    }, null, 0, function (data, release) { cacheLaunchPointIcons(data && data.launchPoints, release); });
  }
  function setTvApps(on) {
    cfg.tvApps = on; saveCfg();
    if (on) refreshTvApps(true); else applyAppList(BUILTIN_APPS);
  }
  function repairTv() {
    try { localStorage.removeItem(SSAP_KEY); localStorage.removeItem(TVAPPS_DENIED); } catch (e) {}
    if (!cfg.tvApps) { cfg.tvApps = true; saveCfg(); }
    refreshTvApps(true);
  }
  if (isWebos() && cfg.tvApps) setTimeout(function () {   // after the first render, so state and the DOM exist
    try { var cachedLps = JSON.parse(localStorage.getItem(TVAPPS_CACHE) || "null"); if (cachedLps) useTvApps(cachedLps); } catch (e) {}
    setTimeout(function () { refreshTvApps(false); }, 1500);
  }, 0);
  // Fire once per idle stretch, only while OmarchyTV is on screen and TV control is approved (an
  // unattended night-time approval prompt would help nobody). Any input re-arms it.
  var offSent = false;
  setInterval(function () {
    if (!cfg.offIdleMin || document.hidden || Date.now() - lastInput < cfg.offIdleMin * 60000) { offSent = false; return; }
    if (offSent || !tvControlApproved()) return;
    offSent = true;
    ssapRequest(cfg.offMode === "tv" ? "ssap://system/turnOff" : "ssap://com.webos.service.tvpower/power/turnOffScreen");
  }, 5000);

  function launchApp(app) {
    if (!app) return;
    if (typeof window.OMARCHY_LAUNCH === "function") {   // another TV browser's own launcher (#66)
      try { if (window.OMARCHY_LAUNCH(app) !== false) return; } catch (e) { showToast("Could not open " + app.name); return; }
    }
    if (app.tizenInput) {   // Samsung source switch through the Tizen adapter, see tizen-shim.js
      var fail = function (err) { showToast("Could not switch to " + app.name + ": " + ((err && (err.message || err.name)) || "input switch failed")); };
      if (typeof window.OMARCHY_TIZEN_SWITCH_INPUT !== "function") { showToast(app.name + " is not available on this TV"); return; }
      try { window.OMARCHY_TIZEN_SWITCH_INPUT(app.id, function () {}, fail); } catch (e) { fail(e); }
      return;
    }
    try {
      if (app.appId && lunaLaunch(app.appId, app.params)) return;
      if (window.tizen && window.tizen.application) {   // Samsung Tizen build (tizen/), see tizen-shim.js
        var tzId = app.tizenId || (window.OMARCHY_TIZEN_IDS || {})[app.id];
        if (!tzId) { showToast(app.name + " is not available on this TV"); return; }
        try {
          window.tizen.application.launch(tzId, null, function (err) {
            showToast("Could not open " + app.name + ": " + ((err && (err.message || err.name)) || "launch failed"));
          });
        } catch (e) {
          showToast("Could not open " + app.name + ": " + ((e && e.message) || "launch failed"));
        }
        return;
      }
      if (window.PalmServiceBridge) {
        var b = new window.PalmServiceBridge();
        b.call("luna://com.webos.applicationManager/launch", JSON.stringify({ id: app.appId }));
        return;
      }
    } catch (e) {}
    showToast("Launching " + app.name + " (" + app.appId + ")");
  }

  function switchWorkspace(ws) {
    state.ws = ws;
    if (ws === 0) { state.zone = "dock"; animateLogo(); }
    else if (ws === 1) state.zone = "ws2";
    else if (ws === 2) state.zone = "ws3";
    else if (ws === 3) state.zone = "ws4";
    else if (ws === 4) state.zone = "ws5";
    renderAll();
  }


  // Grok Bot companion avatar, webOS/Tizen: Tom Ballard's shapes, palette and eyes (MIT, vendored from
  // his Grok Bot Desktop Companion v0.1.0, see vendor/grokbot/) drawn by companion.js. The motion follows
  // his Main.qml: an idle bob, a blink every 2.8-5.2 s (a third of them double), a look around now and
  // then, and sleep after two idle minutes (any key wakes it). "Animation" off keeps it still.
  var GBC = window.OmarchyCompanion || null;
  var gbFace = { open: 1, gx: 0, gy: 0, pose: "idle", lastAct: Date.now(), busy: false, bubble: "", bubbleUntil: 0 };
  var gbPc = null;   // GET /v1/grokbot from the PC bridge: {available, bot, roster, dispatch}
  function gbAnim() { return cfg.companionAnim !== false; }
  function gbBot() {
    var r = (gbPc && gbPc.roster) || [], id = cfg.companionBot;
    for (var i = 0; i < r.length; i++) if (r[i].id === id || r[i].name === id) return r[i];
    return (gbPc && gbPc.bot) || null;
  }
  function gbBotKey() { var b = gbBot(); return (b && (b.id || b.name)) || "default"; }
  function gbName() { var b = gbBot(); return (cfg.grokbotName || (b && (b.shortName || b.name)) || "Grok Bot"); }
  function gbShape() { var b = gbBot(); return cfg.companionShape || (b && b.avatarShape) || "blob"; }
  function gbColor() { var b = gbBot(); return cfg.companionColor || (b && (b.color || b.fill)) || cfg.grokbotColor || "orange"; }
  function gbAvatar(px) {
    if (!GBC) return "";
    return GBC.avatarSvg({ shape: gbShape(), color: gbColor(), px: px, open: gbFace.open, gx: gbFace.gx, gy: gbFace.gy,
      pose: gbFace.busy ? "work" : gbFace.pose });
  }
  function gbWake() {
    gbFace.lastAct = Date.now();
    if (gbFace.pose === "sleep") { gbFace.pose = "idle"; renderPluginsUI(); }
  }
  function gbBlink(twice) {
    if (!cfg.pluginGrokbot || document.hidden || !gbAnim() || gbFace.pose !== "idle") return;
    gbFace.open = 0; renderPluginsUI();
    setTimeout(function () {
      gbFace.open = 1; renderPluginsUI();
      if (twice) setTimeout(function () { gbBlink(false); }, 160);
    }, 150);
  }
  function gbBlinkLoop() {
    gbBlink(Math.random() < 0.35);
    setTimeout(gbBlinkLoop, 2800 + Math.random() * 2400);
  }
  setTimeout(gbBlinkLoop, 3000);
  setInterval(function () {
    if (!cfg.pluginGrokbot || document.hidden) return;
    if (gbAnim() && !gbFace.busy && gbFace.pose !== "sleep" && Date.now() - gbFace.lastAct > 120000) gbFace.pose = "sleep";
    else if (gbAnim() && gbFace.pose === "idle" && Math.random() < 0.35) {   // a glance, then back to you
      gbFace.gx = Math.round((Math.random() * 2 - 1) * 10) / 10; gbFace.gy = Math.round((Math.random() * 1.4 - 0.8) * 10) / 10;
      setTimeout(function () { gbFace.gx = gbFace.gy = 0; renderPluginsUI(); }, 1600);
    }
    if (gbFace.bubble && Date.now() > gbFace.bubbleUntil) gbFace.bubble = "";
    renderPluginsUI();
  }, 7000);

  function renderPluginsUI() {
    var pill = document.getElementById("spacex-pill");
    if (pill) {
      pill.style.display = cfg.pluginSpacexTv ? "inline-flex" : "none";
      pill.textContent = "🚀 SpaceX TV · launches";
    }
    var gb = document.getElementById("grokbot-companion");
    if (gb) {
      gb.style.display = (cfg.pluginGrokbot && !cfg.companionHidden && state.ws === 0) ? "block" : "none";
      gb.className = "box" + (gbAnim() && gbFace.pose !== "sleep" ? " gb-live" : "") + (gbFace.pose === "sleep" ? " gb-sleep" : "") +
        (gb.classList && gb.classList.contains("extra-focused") ? " extra-focused focused" : "");
      var eyes = document.getElementById("grokbot-eyes");
      var sub = document.getElementById("grokbot-sub");
      var px = Math.round(56 * ((GBC && GBC.SIZES[cfg.companionSize]) || 1));
      var dot = GBC && GBC.unread(window.localStorage) > 0 ? '<span class="gb-unread"></span>' : "";
      if (eyes) eyes.innerHTML = '<span class="gb-mark">' + gbAvatar(px) + dot + "</span>" +
        (cfg.companionBadge !== false ? '<span class="gb-name">' + escHtml(gbName()) + "</span>" : "");
      if (sub) {
        var line = gbFace.bubble || (gbFace.busy ? "Thinking…" : cfg.grokbotMessage || "");   // 2.13.8 refresh: idle shows just the avatar
        sub.textContent = line.length > 120 ? line.slice(0, 117) + "…" : line;
        sub.className = gbFace.bubble ? "gb-bubble" : "";
      }
      placeCompanion(gb);
    }
  }

  function openPluginsModal() {
    openModal(tr("sys_plugins"), "Sandboxed declarative JSON plugins (v1 schema · zero native code execution)", [
      { label: "🚀 SpaceX TV (@sighmon · MIT)", right: cfg.pluginSpacexTv ? "on" : "off", act: function () {
        cfg.pluginSpacexTv = !cfg.pluginSpacexTv; saveCfg(); renderPluginsUI(); openPluginsModal();
      } },
      { label: "🚀 Open SpaceX TV Launches & Live Streams", right: "4 streams ›", act: openSpacexModal },
      { label: "🤖 Omarchy Grok Bot Desktop Companion (@tcballard · MIT)", right: cfg.pluginGrokbot ? "on" : "off", act: function () {
        cfg.pluginGrokbot = !cfg.pluginGrokbot; saveCfg(); renderPluginsUI(); openPluginsModal();
      } },
      { label: "🤖 Open Grok Bot companion settings", right: "›", act: function () { openGrokbotCompanionModal(0); } },
      { label: "⌨ Grok Bot (Omarchy TUI)", right: "›", act: openGrokTui },
      { label: "📺 " + tr("plugin_retro_crt"), right: tr(cfg.pluginRetroCrt === "strong" ? "crt_strong" : cfg.pluginRetroCrt === "light" ? "crt_light" : "off"), act: function () {
        var m = ["off", "light", "strong"];
        cfg.pluginRetroCrt = m[(m.indexOf(cfg.pluginRetroCrt || "off") + 1) % m.length]; saveCfg(); applyCrt(); openPluginsModal();
      } }
    ]);
  }

  // Plugin extras reachable with the D-pad (Down from the dock): SpaceX pill, Grok Bot companion.
  function pluginExtras() {
    var out = [];
    if (cfg.pluginGrokbot && document.getElementById("grokbot-companion")) out.push({ id: "grokbot-companion", open: function () { openCompanionChat(false); } });
    if (cfg.pluginSpacexTv && document.getElementById("spacex-pill")) out.push({ id: "spacex-pill", open: openSpacexModal });
    return out;
  }
  function renderExtrasFocus() {
    ["spacex-pill", "grokbot-companion"].forEach(function (id) { var el = document.getElementById(id); if (el) el.classList.remove("extra-focused", "focused"); });
    if (state.ws !== 0 || state.zone !== "extras") return;
    var ex = pluginExtras(), it = ex[Math.min(state.extraIdx || 0, ex.length - 1)];
    var el = it && document.getElementById(it.id);
    if (el) el.classList.add("extra-focused", "focused");
  }

  function openSpacexModal() {
    var streams = [
      { title: "🚀 SpaceX launches · next-launch countdown", sub: "spacex.com/launches" },
      { title: "🛰️ SpaceX broadcasts on X", sub: "x.com/SpaceX" },
      { title: "🎬 Starship (spacex.com)", sub: "spacex.com/vehicles/starship" }
    ];
    var items = streams.map(function (s) {
      return { label: s.title, right: s.sub, act: function () {
        closeModal();
        if (!lunaLaunch("com.webos.app.browser", { target: "https://" + s.sub })) showToast("Open on your TV browser or phone: " + s.sub);
      } };
    });
    items.push({ label: "Top-bar countdown pill", right: cfg.pluginSpacexTv ? "on" : "off", act: function () {
      cfg.pluginSpacexTv = !cfg.pluginSpacexTv; saveCfg(); renderPluginsUI(); openSpacexModal();
    } });
    openModal("SpaceX TV · com.sighmon.spacex-tv", "Based on sighmon's Omarchy SpaceX TV plugin (github.com/sighmon/omarchy-spacex-tv · MIT)", items);
  }

  // Grok Bot companion chat, webOS/Tizen. The chosen agent answers:
  //  - "grokbot": @tcballard's Grok Bot Desktop Companion on the PC, through the omarchy-tv bridge
  //    (POST /v1/grokbot/ask with the ask code the bridge prints; it runs omarchy-link's grokbot ask);
  //  - "grok-web": grok.com in the TV browser (also the fallback when no PC is paired).
  // "open Netflix" / "apri YouTube" opens a matching app on the TV first, without asking anyone.
  // Voice: the Web Speech API when the TV's engine has it; Tizen voice/search keys when the TV lets apps
  // register them; hold Blue everywhere. The webOS Magic Remote mic always belongs to the system.
  var COMPANION_CORNERS = ["bottom-right", "bottom-left", "top-right", "top-left"];
  var GB_AGENTS = ["grokbot", "grok-web"];
  var GB_AGENT_NAMES = { "grokbot": "Grok Bot on my PC (omarchy-link)", "grok-web": "Grok on grok.com (TV browser)" };
  function placeCompanion(el) {
    var c = COMPANION_CORNERS.indexOf(cfg.companionCorner) >= 0 ? cfg.companionCorner : "bottom-right";
    var d = GBC && gbAnim() ? GBC.drift(Date.now()) : { x: 0, y: 0 };   // burn-in safe: never parked on one pixel
    var ox = 48 + (cfg.companionOffsetX || 0) + d.x, oy = (cfg.companionOffsetY || 0) + d.y;
    el.style.left = el.style.right = el.style.top = el.style.bottom = "";
    if (c.indexOf("right") >= 0) el.style.right = ox + "px"; else el.style.left = ox + "px";
    if (c.indexOf("bottom") >= 0) el.style.bottom = (340 + oy) + "px"; else el.style.top = (120 + oy) + "px";
  }
  function speechCtor() { return window.SpeechRecognition || window.webkitSpeechRecognition || null; }
  function askGrokWeb(q) {
    var url = "https://grok.com/?q=" + encodeURIComponent(q);
    try {
      if (lunaLaunch("com.webos.app.browser", { target: url })) return;
      if (window.tizen && window.tizen.ApplicationControl) {
        window.tizen.application.launchAppControl(new window.tizen.ApplicationControl("http://tizen.org/appcontrol/operation/view", url), null, null, null);
        return;
      }
    } catch (e) {}
    showToast("Ask Grok: " + url);
  }
  function openUrlOnTv(url) {
    try {
      if (lunaLaunch("com.webos.app.browser", { target: url })) return;
      if (window.tizen && window.tizen.ApplicationControl) {
        window.tizen.application.launchAppControl(new window.tizen.ApplicationControl("http://tizen.org/appcontrol/operation/view", url), null, null, null);
        return;
      }
    } catch (e) {}
    showToast("Open on your phone: " + url);
  }
  function pcBase() {
    if (!cfg.pcHost) return "";
    var base = cfg.pcHost.indexOf("http") === 0 ? cfg.pcHost : ("http://" + cfg.pcHost + (cfg.pcHost.indexOf(":") > 0 ? "" : ":8765"));
    return base.replace(/\/+$/, "");
  }
  function gbAgent() { return GB_AGENTS.indexOf(cfg.companionAgent) >= 0 ? cfg.companionAgent : "grokbot"; }
  // The PC's bot, roster, shape and colour (Tom's grokbot-bots.json via omarchy-link). No secrets in it.
  function syncGrokbotFromPc(force) {
    var base = pcBase();
    if (!base || typeof fetch !== "function") return Promise.resolve(null);
    var go = force && cfg.grokbotCode
      ? fetch(base + "/v1/grokbot/sync", { method: "POST", headers: { "X-Omarchy-Token": cfg.grokbotCode, "Content-Type": "text/plain" }, body: "{}" })
      : fetch(base + "/v1/grokbot");
    return go.then(function (r) { return r.json(); }).then(function (j) {
      gbPc = j && j.available ? j : null;
      renderPluginsUI();
      return gbPc;
    }).catch(function () { return null; });
  }
  function gbSay(text, ms) {
    gbFace.bubble = String(text || "");
    gbFace.bubbleUntil = Date.now() + (ms || 15000);
    renderPluginsUI();
  }
  // Ask the chosen agent; the answer lands in this bot's history, the bubble and (if the chat is shut) an unread dot.
  function askCompanion(q, reopen) {
    q = String(q || "").trim();
    if (!q || gbFace.busy) return;
    gbWake();
    var key = gbBotKey(), store = window.localStorage;
    if (GBC) {
      var lq = GBC.launchQuery(q), app = lq && GBC.matchApp(lq, visibleApps());
      if (app) {
        GBC.push(store, key, "user", q);
        GBC.push(store, key, "bot", "Opening " + app.name + "…", { mode: "launch" });
        closeModal(); gbSay("Opening " + app.name + "…", 4000); launchApp(app);
        return;
      }
    }
    var base = pcBase();
    // v2.13.8 "Grok Bot diretto": no PC companion paired, a webhook routine set → the TV asks Grok Bot itself.
    if (gbAgent() === "grokbot" && (!base || !cfg.grokbotCode) && GBD && GBD.enabled(GBD.load(store))) { askDirect(q, reopen); return; }
    if (gbAgent() !== "grokbot" || !base || !cfg.grokbotCode || typeof fetch !== "function") {
      if (gbAgent() === "grokbot") showToast(!base ? "Pair the PC (Workspace 4) to ask Grok Bot · opening grok.com" : "Enter the PC ask code in the companion settings · opening grok.com");
      if (GBC) GBC.push(store, key, "user", q);
      closeModal(); askGrokWeb(q);
      return;
    }
    if (GBC) GBC.push(store, key, "user", q);
    gbFace.busy = true; gbSay("Thinking…", 200000);
    if (reopen) openCompanionChat(false);
    var bot = gbBot();
    fetch(base + "/v1/grokbot/ask", { method: "POST", headers: { "X-Omarchy-Token": cfg.grokbotCode, "Content-Type": "text/plain" },
      body: JSON.stringify({ text: q, bot: bot ? (bot.id || bot.name) : undefined }) })
      .then(function (r) { return r.json().then(function (j) { return { ok: r.ok, j: j || {} }; }); })
      .then(function (res) {
        gbFace.busy = false;
        var j = res.j, open = state.modalOpen && state.gbChat;
        if (!res.ok) {
          var msg = res.j.error === "unauthorized" ? "Wrong ask code: check it in the companion settings" : (j.message || "The PC could not answer");
          if (GBC) GBC.push(store, key, "bot", msg, { mode: "error" });
          gbSay(msg, 8000);
        } else if (j.mode === "sent") {
          var sent = "Sent ✓ The answer appears on the PC companion.";
          if (GBC) GBC.push(store, key, "bot", sent, { mode: "sent", unread: !open });
          gbSay(sent, 10000);
        } else {
          var links = GBC ? (j.links && j.links.length ? GBC.cleanLinks(j.links) : GBC.links(j.text)) : [];
          if (GBC) GBC.push(store, key, "bot", j.text || "(no answer)", { links: links, unread: !open });
          gbFace.pose = "happy"; setTimeout(function () { if (gbFace.pose === "happy") gbFace.pose = "idle"; renderPluginsUI(); }, 2500);
          gbSay((j.bot ? j.bot + ": " : "") + (j.text || ""), 30000);
        }
        if (open) openCompanionChat(false);
      }).catch(function () {
        gbFace.busy = false;
        if (GBD && GBD.enabled(GBD.load(store))) { askDirect(q, false, true); return; }   // PC off: Grok Bot diretto
        var m = "The PC did not answer · is omarchy-tv webos serve running?";
        if (GBC) GBC.push(store, key, "bot", m, { mode: "error" });
        gbSay(m, 8000);
        if (state.modalOpen && state.gbChat) openCompanionChat(false);
      });
  }
  // ---- v2.13.8 Grok Bot diretto (senza PC): grokdirect.js, replies through ntfy.sh on a random topic ----
  var GBD = window.OmarchyGrokDirect || null;
  var gbdXaiNoted = false, gbdAudio = null;
  function gbdSpeak(text, lang) {
    var st = GBD.load(window.localStorage);
    if (st.speak && text && GBD.xaiOn(st) && window.URL && typeof window.Audio === "function") {   // optional Grok voice (xAI)
      var fallback = function () {
        if (!gbdXaiNoted) { gbdXaiNoted = true; if (GBC) GBC.push(window.localStorage, gbBotKey(), "bot", "Grok voice unavailable, using the TV voice.", { mode: "error" }); }
        gbdSystemSpeak(st, text, lang);
      };
      GBD.xaiAudio(st, text, lang).then(function (blob) {
        var url = window.URL.createObjectURL(blob);
        if (gbdAudio) { try { gbdAudio.pause(); } catch (e) {} }
        gbdAudio = new window.Audio(url);
        gbdAudio.onended = function () { window.URL.revokeObjectURL(url); };
        gbdAudio.onerror = function () { window.URL.revokeObjectURL(url); fallback(); };
        var p = gbdAudio.play();
        if (p && p.catch) p.catch(fallback);
      }, fallback);
      return;
    }
    gbdSystemSpeak(st, text, lang);
  }
  function gbdSystemSpeak(st, text, lang) {
    if (!st.speak || !text || !window.speechSynthesis || typeof window.SpeechSynthesisUtterance !== "function") return;
    try {
      var u = new window.SpeechSynthesisUtterance(text);
      u.lang = lang || speechLang();   // the reply's own lang when valid
      window.speechSynthesis.cancel(); window.speechSynthesis.speak(u);
    } catch (e) {}
  }
  function gbdRun(a) {
    if (!a) return;
    if (a.type === "play_video") {
      if (a.youtube && a.videoId) { if (cfg.embedPlayer) openEmbed(a.videoId); else { closeModal(); openYoutubeApp(a.videoId); } }
      else { closeModal(); openUrlOnTv(a.url); }
    } else if (a.type === "open_app") {
      var app = GBC && GBC.matchApp(a.name, visibleApps());
      if (app) { closeModal(); launchApp(app); } else showToast("No app matches “" + a.name + "”");
    } else if (a.type === "search_youtube") { closeModal(); openUrlOnTv(GBD.youtubeSearchUrl(a.query)); }
  }
  function askDirect(q, reopen, pushed) {
    var store = window.localStorage, key = gbBotKey(), st = GBD.load(store);
    if (GBC && !pushed) GBC.push(store, key, "user", q);
    gbFace.busy = true; gbSay("Thinking…", 200000);
    if (reopen) openCompanionChat(false);
    var apps = visibleApps().map(function (a) { return a.name; });
    GBD.ask(st, q, askLang(), apps,
      window.OMARCHY_PLATFORM === "tizen" ? "OmarchyTV on Samsung Tizen" : "OmarchyTV on LG webOS", function (err, rep) {
        gbFace.busy = false;
        var open = state.modalOpen && state.gbChat;
        if (err) {
          if (GBC) GBC.push(store, key, "bot", err, { mode: "error" });
          gbSay(err, 8000);
        } else {
          if (GBC && rep.text) GBC.push(store, key, "bot", rep.text, { unread: !open });
          gbFace.pose = "happy"; setTimeout(function () { if (gbFace.pose === "happy") gbFace.pose = "idle"; renderPluginsUI(); }, 2500);
          if (rep.text) { gbSay(rep.text, 30000); gbdSpeak(rep.text, rep.lang); }
        }
        if (open && !(rep && rep.action)) openCompanionChat(false);
        if (rep && rep.dropped) { if (GBC) GBC.push(store, key, "bot", "Grok Bot sent an action I can't run (ignored).", { mode: "error" }); showToast("Grok Bot sent an action I can't run"); }
        if (rep && rep.action) gbdRun(rep.action);
      });
  }
  // ---- "Grok Bot · Omarchy TUI" (port of Android's AskActivity): local TV commands, then the agent ----
  // Parsing lives in tui.js (node-tested). Local commands: themes, wallpaper, screensaver, apps, status, web apps.
  // Questions go to the PC bridge (POST /v1/grokbot/ask with the ask code); no PC or PC off → Grok Bot diretto
  // when it's on; otherwise grok.com in the TV browser. Replies type out in the box. Back closes the window.
  var TUI = window.OmarchyTui || null;
  // TUI agents, like Android's Ask: Grok Bot (companion bridge), every AI agent signed in on the PC
  // (omarchy-link's assistant: Claude, Codex, Grok, Grok Bot, Hermes, Gemini, ollama, APIs) via
  // POST /v1/ai/ask, and Grok Web in the TV browser. The PC list is read from POST /v1/ai/providers.
  var TUI_PC_DEFAULT = [["claude-code", "Claude"], ["codex", "ChatGPT (Codex)"], ["grok-cli", "Grok"], ["grok-bot", "Grok Bot (PC)"],
    ["hermes-cli", "Hermes"], ["gemini-cli", "Gemini"], ["ollama", "Local (ollama)"]];
  var tuiSession = null;
  function tuiAgents() {
    var pc = (cfg.tuiProviders && cfg.tuiProviders.length ? cfg.tuiProviders : TUI_PC_DEFAULT.map(function (p) { return { id: p[0], name: p[1] }; }));
    return [{ id: "grokbot", name: "Grok Bot (companion)" }].concat(pc.map(function (p) { return { id: "pc:" + p.id, name: p.name + " · PC" }; }))
      .concat([{ id: "grok-web", name: "Grok Web (grok.com)" }]);
  }
  function tuiAgent() {
    var l = tuiAgents(), id = cfg.tuiAgent || "grokbot";
    for (var i = 0; i < l.length; i++) if (l[i].id === id) return l[i];
    return l[0];
  }
  function tuiSetAgent(a) { cfg.tuiAgent = a.id; tuiSession = null; saveCfg(); tuiAdd("ok", "agent → " + a.name); }
  function tuiFindAgent(q) {
    var n = String(q).toLowerCase().replace(/[^a-z0-9]/g, ""), l = tuiAgents(), i;
    for (i = 0; i < l.length; i++) if (l[i].id.replace(/^pc:/, "").replace(/[^a-z0-9]/g, "") === n) return l[i];
    for (i = 0; i < l.length; i++) if (l[i].name.toLowerCase().replace(/[^a-z0-9]/g, "").indexOf(n) === 0 || l[i].id.replace(/^pc:/, "").indexOf(n) === 0) return l[i];
    return null;
  }
  function tuiRefreshAgents(verbose) {
    var base = pcBase();
    if (!base || !cfg.grokbotCode || typeof fetch !== "function") { if (verbose) tuiAdd("dim", "pair the PC and set the ask code to read its agents"); return; }
    fetch(base + "/v1/ai/providers", { method: "POST", headers: { "X-Omarchy-Token": cfg.grokbotCode, "Content-Type": "text/plain" }, body: "{}" })
      .then(function (r) { return r.json().then(function (j) { return { ok: r.ok, j: j || {} }; }); })
      .then(function (res) {
        var l = res.ok && res.j.providers;
        if (!l || !l.length) { if (verbose) tuiAdd("err", res.j.message || "no AI agent is signed in on the PC"); return; }
        cfg.tuiProviders = l.slice(0, 16).map(function (p) { return { id: String(p.id), name: String(p.name || p.id).slice(0, 32) }; });
        saveCfg();
        if (verbose) tuiAdd("dim", "PC agents: " + cfg.tuiProviders.map(function (p) { return p.name; }).join(" · "));
      }, function () { if (verbose) tuiAdd("err", "the PC did not answer · is omarchy-tv webos serve running (with omarchy-link)?"); });
  }
  function tuiAskPc(q, prov) {
    var base = pcBase(), a = tuiAgent();
    tuiBusy = true; tuiAdd("dim", "asking " + a.name + "…");
    fetch(base + "/v1/ai/ask", { method: "POST", headers: { "X-Omarchy-Token": cfg.grokbotCode, "Content-Type": "text/plain" },
      body: JSON.stringify({ text: q, provider: prov, session: tuiSession || undefined, lang: speechLang() }) })
      .then(function (r) { return r.json().then(function (j) { return { ok: r.ok, j: j || {} }; }); })
      .then(function (res) {
        tuiBusy = false;
        var j = res.j;
        if (!res.ok) { tuiAdd("err", j.error === "unauthorized" ? "wrong ask code: check it in the companion settings" :
          j.error === "not found" ? "this PC bridge has no agents yet: update the omarchy-tv plugin on the PC and restart omarchy-tv webos serve" : (j.message || "the PC could not answer")); return; }
        tuiSession = j.session || tuiSession;
        tuiReply(a.name.replace(/ · PC$/, ""), j.text);
      }, function () { tuiBusy = false; tuiAdd("err", "the PC did not answer · is omarchy-tv webos serve running?"); });
  }
  var tuiLog = [], tuiBusy = false, tuiTimer = null;
  function tuiAdd(cls, text, typed) {
    tuiLog.push({ cls: cls, text: String(text || ""), t0: typed ? Date.now() : 0 });
    if (tuiLog.length > 60) tuiLog.splice(0, tuiLog.length - 60);
    tuiRender();
  }
  function tuiThemeName() { return cfg.theme || "omarchy"; }
  function tuiRender() {
    var box = document.getElementById("tui-log");
    if (!box || !state.modalOpen || !state.tuiOpen) return;
    var now = Date.now(), typing = false;
    box.innerHTML = tuiLog.slice(-14).map(function (l) {
      var txt = l.text;
      if (l.t0 && TUI) { var n = TUI.typed(txt, now - l.t0); if (n < txt.length) { typing = true; txt = txt.slice(0, n) + "▌"; } }
      return '<div class="tui-line tui-' + l.cls + '">' + escHtml(txt) + "</div>";
    }).join("") || '<div class="tui-line tui-dim">type “help” for the commands</div>';
    box.scrollTop = box.scrollHeight;
    var leg = document.getElementById("tui-legend");
    if (leg) leg.textContent = "grok bot · omarchy tui  [" + tuiThemeName() + "]";
    clearTimeout(tuiTimer);
    if (typing) tuiTimer = setTimeout(tuiRender, 40);
  }
  function tuiOpenUrl(url, name) { tuiAdd("ok", "opening " + name + " in the TV browser…"); openUrlOnTv(url); }
  function tuiRun(q) {
    var themes = Object.keys(DATA.themes || {}), c = TUI ? TUI.parse(q, themes) : { kind: "ask", text: q };
    if (c.kind === "empty") return;
    if (c.kind !== "clear") tuiAdd("me", "❯ " + q);
    if (c.kind === "clear") { tuiLog = []; tuiRender(); }
    else if (c.kind === "help") TUI.HELP.forEach(function (h) { tuiAdd("dim", h); });
    else if (c.kind === "themes") tuiAdd("dim", themes.join(" · "));
    else if (c.kind === "next-theme" || c.kind === "theme") {
      var n = c.kind === "theme" ? c.name : themes[(themes.indexOf(cfg.theme) + 1) % themes.length];
      cfg.theme = n; cfg.randomTheme = false; cfg.wallpaperIdx = 0; saveCfg(); applyTheme(); renderAll(); tuiAdd("ok", "theme → " + n);
    } else if (c.kind === "theme-unknown") tuiAdd("err", "no single theme matches “" + c.query + "” · try “themes”");
    else if (c.kind === "wallpaper") { cfg.wallpaperIdx = (cfg.wallpaperIdx || 0) + 1; cfg.wallpaper = ""; saveCfg(); applyTheme(); tuiAdd("ok", "next wallpaper"); }
    else if (c.kind === "saver") {
      if (c.style === "aquarium") { cfg.saverMode = "aquarium"; cfg.saverArt = "aquarium"; }
      else if (c.style === "classic") { cfg.saverMode = "aquarium"; cfg.saverArt = "aquarium-classic"; }
      else if (c.style === "logo") { cfg.saverMode = "logo"; cfg.saverArt = "omarchy-tv"; }
      else if (c.style === "cinemarchy") { cfg.saverMode = "logo"; cfg.saverArt = "cinemarchy"; }
      if (c.speed) cfg.saverSpeed = c.speed;
      saveCfg(); tuiAdd("ok", "screensaver" + (c.style ? " → " + c.style : "") + (c.speed ? " · speed " + c.speed : "") + " · any key wakes the TV");
      setTimeout(function () { closeModal(); startScreensaver(); }, 700);
    } else if (c.kind === "speed") { cfg.saverSpeed = c.speed; saveCfg(); tuiAdd("ok", "screensaver speed → " + c.speed);
    } else if (c.kind === "agents") {
      tuiAdd("dim", "agent: " + tuiAgent().name + " · " + tuiAgents().map(function (x) { return x.name; }).join(" · "));
      tuiRefreshAgents(true);
    } else if (c.kind === "agent") {
      var ag = tuiFindAgent(c.query);
      if (ag) tuiSetAgent(ag); else tuiAdd("err", "no agent matches “" + c.query + "” · try “agents”");
    } else if (c.kind === "status") {
      var base = pcBase(), st = GBD ? GBD.load(window.localStorage) : null;
      tuiAdd("dim", "OmarchyTV " + APP_VERSION + " · " + (window.OMARCHY_PLATFORM === "tizen" ? "Samsung Tizen" : "LG webOS"));
      tuiAdd("dim", "theme " + tuiThemeName() + " · " + visibleApps().length + " apps on the dock");
      tuiAdd("dim", "PC bridge " + (base ? base + (cfg.grokbotCode ? " (ask code set)" : " (no ask code)") : "not paired") +
        " · Grok Bot diretto " + (st && GBD.enabled(st) ? "on" : "off") + " · agent " + tuiAgent().name);
    } else if (c.kind === "apps") tuiAdd("dim", visibleApps().map(function (a) { return a.name; }).join(" · "));
    else if (c.kind === "web") tuiOpenUrl(c.url, c.url.replace(/^https?:\/\//, "").replace(/\/$/, ""));
    else if (c.kind === "open") {
      var t = c.target.toLowerCase(), app = GBC && GBC.matchApp(c.target, visibleApps());
      if (!app) app = visibleApps().filter(function (a) { return a.name.toLowerCase().indexOf(t) >= 0; })[0];
      if (app) { tuiAdd("ok", "opening " + app.name + "…"); closeModal(); launchApp(app); }
      else tuiAdd("err", "no app matches “" + c.target + "” · try “apps”");
    } else tuiAsk(c.text);
  }
  function tuiReply(who, text) { tuiAdd("bot", (who ? who + ": " : "") + (text || "(no answer)"), true); }
  function tuiDirect(q) {
    var st = GBD.load(window.localStorage);
    tuiBusy = true; tuiAdd("dim", "asking Grok Bot diretto…");
    GBD.ask(st, q, askLang(), visibleApps().map(function (a) { return a.name; }),
      window.OMARCHY_PLATFORM === "tizen" ? "OmarchyTV on Samsung Tizen" : "OmarchyTV on LG webOS", function (err, rep) {
        tuiBusy = false;
        if (err) { tuiAdd("err", err); return; }
        if (rep.text) { tuiReply("Grok Bot", rep.text); gbdSpeak(rep.text, rep.lang); }
        if (rep.dropped) tuiAdd("err", "Grok Bot sent an action I can't run (ignored)");
        if (rep.action) gbdRun(rep.action);
      });
  }
  function tuiAsk(q) {
    if (tuiBusy) { tuiAdd("err", "still waiting for the last answer…"); return; }
    var base = pcBase(), direct = GBD && GBD.enabled(GBD.load(window.localStorage));
    var ta = tuiAgent();
    if (ta.id === "grok-web") { tuiOpenUrl("https://grok.com/?q=" + encodeURIComponent(q), "grok.com"); return; }
    if (ta.id.indexOf("pc:") === 0 && base && cfg.grokbotCode && typeof fetch === "function") { tuiAskPc(q, ta.id.slice(3)); return; }
    if (!base || !cfg.grokbotCode || typeof fetch !== "function") {
      if (direct) { tuiDirect(q); return; }
      tuiAdd("err", !base ? "no PC paired and Grok Bot diretto is off · opening grok.com" : "no PC ask code (companion settings) · opening grok.com");
      askGrokWeb(q); return;
    }
    var bot = gbBot();
    tuiBusy = true; tuiAdd("dim", "asking " + gbName() + " on the PC…");
    fetch(base + "/v1/grokbot/ask", { method: "POST", headers: { "X-Omarchy-Token": cfg.grokbotCode, "Content-Type": "text/plain" },
      body: JSON.stringify({ text: q, bot: bot ? (bot.id || bot.name) : undefined }) })
      .then(function (r) { return r.json().then(function (j) { return { ok: r.ok, j: j || {} }; }); })
      .then(function (res) {
        tuiBusy = false;
        var j = res.j;
        if (!res.ok) tuiAdd("err", j.error === "unauthorized" ? "wrong ask code: check it in the companion settings" : (j.message || "the PC could not answer"));
        else if (j.mode === "sent") tuiAdd("ok", "sent ✓ the answer appears on the PC companion");
        else tuiReply(j.bot || gbName(), j.text);
      }).catch(function () {
        tuiBusy = false;
        if (direct) { tuiAdd("dim", "the PC did not answer · trying Grok Bot diretto"); tuiDirect(q); return; }
        tuiAdd("err", "the PC did not answer · is omarchy-tv webos serve running?");
      });
  }
  window.OMARCHY_OPEN_TUI = function () { openGrokTui(); };   // other entry points (and tests) can open the TUI
  window.OMARCHY_OPEN_AI_HUB = function () { openGrokBotHub(); };
  window.OMARCHY_OPEN_WEBAPPS = function () { openWebAppsModal(); };
  // Android parity: Menu › Grok Bot & AI and Menu › Web apps.
  var AI_WEBAPPS = [["Grok Web", "https://grok.com/"], ["Grok (X)", "https://x.com/i/grok"], ["ChatGPT", "https://chatgpt.com/"],
    ["Claude", "https://claude.ai/"], ["Hermes", "https://hermes.nousresearch.com"]];
  function openGrokBotHub() {
    openModal("Grok Bot & AI", "Grok Bot TUI, companion, PC agents and AI web apps", [
      { label: "⌨ Grok Bot (Omarchy TUI)", right: "›", act: openGrokTui },
      { label: "💬 Chat with the Grok Bot companion", right: "OK ›", act: function () { openCompanionChat(false); } },
      { label: "Companion settings", right: "›", act: function () { openGrokbotCompanionModal(0); } },
      { label: "TUI agent", right: tuiAgent().name + " ›", act: function () {
        var l = tuiAgents(), i = 0; for (; i < l.length; i++) if (l[i].id === tuiAgent().id) break;
        cfg.tuiAgent = l[(i + 1) % l.length].id; tuiSession = null; saveCfg(); openGrokBotHub(); state.modalIdx = 3; renderModalList();
      } },
      { label: "Read the agents signed in on the PC", right: "›", act: function () { tuiRefreshAgents(false); showToast("Asking the PC for its agents…"); } },
      { label: "🌐 Web apps (Grok, ChatGPT, Claude, Hermes…)", right: "›", act: openWebAppsModal },
      { label: "🧩 Plugins (SpaceX TV · Grok Bot)", right: "›", act: openPluginsModal }
    ]);
  }
  function webApps() {
    // v2.14 X first: the TV browser opens x.com (on LG/Samsung the remote works with the TV browser's own pointer).
    return [{ name: "X", url: "https://x.com/home", builtin: true }]
      .concat(AI_WEBAPPS.map(function (w) { return { name: w[0], url: w[1], builtin: true }; })).concat(cfg.webapps || []);
  }
  function openWebAppsModal() {
    var typed = "";
    var items = [{ label: "+ Add the web app typed above (name=https://url or https://url)", right: "+", act: function () {
      var v = typed.trim(), m = /^(?:(.+?)\s*=\s*)?(https:\/\/\S+)$/.exec(v);
      if (!m) { showToast("Type https://… (or Name=https://…) above first"); return; }
      var name = (m[1] || m[2].replace(/^https:\/\//, "").split("/")[0]).slice(0, 32);
      cfg.webapps = (cfg.webapps || []).concat([{ name: name, url: m[2].slice(0, 500) }]).slice(0, 24); saveCfg();
      showToast(name + " added"); openWebAppsModal();
    } }];
    webApps().forEach(function (w, i) {
      items.push({ label: w.name + "  " + w.url.replace(/^https?:\/\//, "").replace(/\/$/, ""), right: "open ›", act: function () { closeModal(); openUrlOnTv(w.url); } });
      if (!w.builtin) items.push({ label: "   remove " + w.name, right: "✕", act: function () {
        var u = cfg.webapps.indexOf(w); if (u >= 0) cfg.webapps.splice(u, 1); saveCfg(); showToast(w.name + " removed"); openWebAppsModal();
      } });
    });
    openModal("Web apps", "Open in the TV browser · Back returns", items, "https://… or Name=https://…", function (v) { typed = v; }, { noAutoFocus: true });
  }
  function openTuiWeb() {
    var W = [["Grok Web", "grok"], ["Grok (X)", "xgrok"], ["ChatGPT", "chatgpt"], ["Claude", "claude"], ["Hermes", "hermes"]];
    openModal("grok bot · web & ai", "Opens in the TV browser · Back returns", W.map(function (w) {
      var u = TUI.WEB_URLS[w[1]];
      return { label: w[0] + " (Web)", right: u.replace(/^https?:\/\//, "").replace(/\/$/, "") + " ›", act: function () { tuiOpenUrl(u, w[0]); } };
    }));
  }
  function openGrokTui(focus) {
    var q = "";
    var items = [
      { label: "❯ Run", right: "OK", act: function () {
        var v = q.trim(); if (!v) { showToast("Type a command or a question above (▲)"); return; }
        q = ""; var inp = document.getElementById("modal-input"); if (inp) inp.value = "";
        tuiRun(v);
      } },
      speechCtor() ? { label: "🎤 Speak", right: "OK", act: function () { listen(function (t) { tuiRun(t); }); } }
        : { label: "🎤 Voice: not available on this TV · keyboard only", right: "", act: function () {} },
      { label: "help", right: "›", act: function () { tuiRun("help"); } },
      { label: "status", right: "›", act: function () { tuiRun("status"); } },
      { label: "next theme", right: "›", act: function () { tuiRun("next theme"); } },
      { label: "Agent", right: tuiAgent().name + " ›", act: function () {
        var l = tuiAgents(), i = 0; for (; i < l.length; i++) if (l[i].id === tuiAgent().id) break;
        tuiSetAgent(l[(i + 1) % l.length]); openGrokTui(5);
      } },
      { label: "Open grok.com in the TV browser", right: "›", act: function () { tuiRun("grok.com"); } },
      { label: "🌐 web ▾  (Grok, Grok on X, ChatGPT, Claude, Hermes)", right: "›", act: openTuiWeb },
      { label: "wallpaper", right: "›", act: function () { tuiRun("wallpaper"); } },
      { label: "screensaver", right: "›", act: function () { tuiRun("screensaver"); } },
      { label: "apps", right: "›", act: function () { tuiRun("apps"); } },
      { label: "agents", right: "›", act: function () { tuiRun("agents"); } },
      { label: "clear", right: "", act: function () { tuiRun("clear"); } }
    ];
    openModal("Grok Bot · Omarchy TUI", "", items, "❯ command or question… (help, theme cumulus, open MUBI, what should I watch?)", function (v) { q = v; },
      { noAutoFocus: true });
    state.tuiOpen = true;
    var subEl = document.getElementById("modal-sub");
    if (subEl) {
      subEl.innerHTML = '<div class="tui-win"><span id="tui-legend" class="tui-legend"></span><div id="tui-log" class="tui-log"></div></div>';
      subEl.style.display = "block";
    }
    if (!tuiLog.length) { tuiAdd("dim", "OmarchyTV " + APP_VERSION + " · type “help” · Back closes"); tuiRefreshAgents(false); }
    if (typeof focus === "number") { state.modalIdx = Math.min(focus, state.modalItems.length - 1); renderModalList(); }
    tuiRender();
  }

  function playLink(l) {
    if (l.kind === "youtube" && l.videoId) { if (cfg.embedPlayer) openEmbed(l.videoId); else { closeModal(); openYoutubeApp(l.videoId); } return; }
    closeModal(); openUrlOnTv(l.url);
  }
  function listen(onText) {
    var SR = speechCtor();
    if (!SR) { showToast("Voice is not available on this TV · use the keyboard"); return false; }
    try {
      var r = new SR();
      r.lang = speechLang();
      r.interimResults = false;
      r.onresult = function (ev) { var t = ev.results && ev.results[0] && ev.results[0][0] ? ev.results[0][0].transcript : ""; if (t) onText(t); };
      r.onerror = function () { showToast("I didn't catch that · try again or type"); };
      r.start(); showToast("Listening…");
      gbFace.pose = "alert"; setTimeout(function () { if (gbFace.pose === "alert") gbFace.pose = "idle"; renderPluginsUI(); }, 4000);
      renderPluginsUI();
      return true;
    } catch (e) { showToast("Voice is not available here · use the keyboard"); return false; }
  }
  function openCompanionChat(voice) {
    gbWake();
    var q = "", key = gbBotKey(), store = window.localStorage, hist = GBC ? GBC.history(store, key) : [];
    if (GBC) GBC.markRead(store, key);
    var last = null;
    for (var h = hist.length - 1; h >= 0; h--) if (hist[h].role === "bot") { last = hist[h]; break; }
    var items = [
      { label: gbFace.busy ? "⏳ " + escHtml(gbName()) + " is thinking…" : "➤ Ask " + escHtml(gbName()), right: "OK", act: function () {
        if (q.trim()) askCompanion(q, true); else showToast("Type a question above, or use 🎤");
      } },
      speechCtor() ? { label: "🎤 Speak", right: "OK", act: function () { listen(function (t) { askCompanion(t, true); }); } }
        : { label: "🎤 Voice: not available on this TV · keyboard only", right: "", act: function () {} }
    ];
    ((last && last.links) || []).forEach(function (l) {
      var lbl = l.kind === "youtube" ? (cfg.embedPlayer ? "▶ Play in Cinemarchy" : "▶ Play on YouTube") : l.kind === "video" ? "▶ Play video in the TV browser" : "🔗 Open link in the TV browser";
      items.push({ label: lbl + " · " + escHtml(l.url.replace(/^https?:\/\//, "").slice(0, 48)), right: "OK ›", act: function () { playLink(l); } });
    });
    var roster = (gbPc && gbPc.roster) || [];
    if (roster.length > 1) items.push({ label: "Bot", right: escHtml(gbName()) + " ›", act: function () {
      var i = -1, cur = gbBot();
      for (var k = 0; k < roster.length; k++) if (cur && roster[k].id === cur.id) i = k;
      cfg.companionBot = roster[(i + 1) % roster.length].id; saveCfg(); renderPluginsUI(); openCompanionChat(false);
    } });
    items.push({ label: "Agent", right: GB_AGENT_NAMES[gbAgent()] + " ›", act: function () {
      cfg.companionAgent = GB_AGENTS[(GB_AGENTS.indexOf(gbAgent()) + 1) % GB_AGENTS.length]; saveCfg(); openCompanionChat(false);
    } });
    if (hist.length) items.push({ label: "Clear this chat", right: "", act: function () { GBC.clear(store, key); gbFace.bubble = ""; openCompanionChat(false); } });
    items.push({ label: "Hide the companion (Blue brings it back)", right: "", act: function () {
      cfg.companionHidden = true; saveCfg(); closeModal(); showToast("Companion hidden · press Blue to bring it back");
    } });
    items.push({ label: "Companion settings", right: "›", act: function () { openGrokbotCompanionModal(0); } });
    items.push({ label: "⌨ Grok Bot (Omarchy TUI)", right: "›", act: openGrokTui });
    openModal("💬 " + gbName(), "", items, "Ask anything, or say “open Netflix”… (on-screen or Bluetooth keyboard)", function (v) { q = v; },
      { noAutoFocus: true, focusIdx: voice && speechCtor() ? 1 : 0 });
    state.gbChat = true;
    var subEl = document.getElementById("modal-sub");
    if (subEl) {
      var rows = hist.slice(-6).map(function (m) {
        return '<div class="gb-msg ' + (m.role === "user" ? "gb-me" : "gb-bot") + '">' + escHtml(m.text.length > 400 ? m.text.slice(0, 397) + "…" : m.text) + "</div>";
      }).join("");
      subEl.innerHTML = '<div style="display:flex;align-items:flex-start;gap:12px;"><span class="gb-mark">' + gbAvatar(48) + "</span><div>" +
        (rows || "Hi! Ask me anything, or tell me which app to open.") + '<div class="gb-credit">Avatar from @tcballard\'s Grok Bot Desktop Companion v0.1.0 (MIT)</div></div></div>';
      subEl.style.display = "block";
    }
    if (voice && speechCtor()) listen(function (t) { askCompanion(t, true); });
  }

  function cycle(list, cur) { var i = list.indexOf(cur); return list[(i + 1) % list.length]; }
  function openGrokbotCompanionModal(focusIdx) {
    var shapes = [""].concat(GBC ? GBC.SHAPES : []), colors = [""].concat(GBC ? GBC.COLORS : []);
    var code = "";
    var items = [
      { label: "💬 Chat with " + escHtml(gbName()), right: "OK ›", act: function () { openCompanionChat(false); } },
      { label: "Show companion on Home", right: cfg.pluginGrokbot && !cfg.companionHidden ? "on" : "off", act: function () {
        if (cfg.companionHidden) cfg.companionHidden = false; else cfg.pluginGrokbot = !cfg.pluginGrokbot;
        saveCfg(); renderPluginsUI(); openGrokbotCompanionModal(1);
      } },
      { label: "Shape", right: (cfg.companionShape || "follow PC bot (" + gbShape() + ")") + " ›", act: function () {
        cfg.companionShape = cycle(shapes, cfg.companionShape || ""); saveCfg(); renderPluginsUI(); openGrokbotCompanionModal(2);
      } },
      { label: "Colour", right: (cfg.companionColor || "follow PC bot") + " ›", act: function () {
        cfg.companionColor = cycle(colors, cfg.companionColor || ""); saveCfg(); renderPluginsUI(); openGrokbotCompanionModal(3);
      } },
      { label: "Size", right: (cfg.companionSize || "medium") + " ›", act: function () {
        cfg.companionSize = cycle(["small", "medium", "large"], cfg.companionSize || "medium"); saveCfg(); renderPluginsUI(); openGrokbotCompanionModal(4);
      } },
      { label: "Corner", right: (cfg.companionCorner || "bottom-right") + " ›", act: function () {
        cfg.companionCorner = cycle(COMPANION_CORNERS, cfg.companionCorner || "bottom-right"); saveCfg(); renderPluginsUI(); openGrokbotCompanionModal(5);
      } },
      { label: "Name badge", right: cfg.companionBadge !== false ? "on" : "off", act: function () {
        cfg.companionBadge = cfg.companionBadge === false; saveCfg(); renderPluginsUI(); openGrokbotCompanionModal(6);
      } },
      { label: "Animation (bob, blink, look around, sleep, drift)", right: gbAnim() ? "on" : "off", act: function () {
        cfg.companionAnim = !gbAnim(); saveCfg(); renderPluginsUI(); openGrokbotCompanionModal(7);
      } },
      { label: "Agent", right: GB_AGENT_NAMES[gbAgent()] + " ›", act: function () {
        cfg.companionAgent = cycle(GB_AGENTS, gbAgent()); saveCfg(); openGrokbotCompanionModal(8);
      } },
      { label: "Save the PC ask code typed above", right: cfg.grokbotCode ? "set ✓" : "not set", act: function () {
        var c = code.replace(/\s+/g, "").toUpperCase();
        if (c && !/^[A-Z0-9]{8,64}$/.test(c)) { showToast("The ask code has 8 letters and digits"); return; }
        cfg.grokbotCode = c; saveCfg(); showToast(c ? "Ask code saved" : "Ask code removed"); openGrokbotCompanionModal(9);
      } },
      { label: "Sync bot, shape and colour from the PC", right: pcBase() ? "OK ›" : "pair the PC first", act: function () {
        if (!pcBase()) { showToast("Pair the PC in Workspace 4 first"); return; }
        showToast("Syncing with the PC…");
        syncGrokbotFromPc(true).then(function (j) { showToast(j ? "Synced · " + ((j.roster || []).length) + " bots" : "The PC has no Grok Bot Desktop Companion, or the bridge is off"); if (state.modalOpen) openGrokbotCompanionModal(10); });
      } },
      { label: "Clear all companion chats", right: "", act: function () { if (GBC) GBC.clear(window.localStorage); gbFace.bubble = ""; renderPluginsUI(); showToast("Chats cleared"); } }
    ];
    if (GBD) {   // v2.13.8 Grok Bot diretto (senza PC): URL and key stay in this TV's own storage, the key never shown
      var gst = GBD.load(window.localStorage), gsave = function (idx) { GBD.save(window.localStorage, gst); openGrokbotCompanionModal(idx); };
      items.push(
        { label: "Grok Bot diretto (senza PC)", right: gst.enabled ? "on" : "off", act: function () { gst.enabled = !gst.enabled; gsave(12); } },
        { label: "Save the webhook URL typed above", right: gst.url ? "set ✓" : "not set", act: function () {
          var u = code.trim();
          if (u && !GBD.validUrl(u)) { showToast("The webhook URL must start with https://"); return; }
          gst.url = u; showToast(u ? "Webhook URL saved" : "Webhook URL removed"); gsave(13);
        } },
        { label: "Save the Sender key typed above", right: GBD.mask(gst.key), act: function () {
          var k = code.trim();
          if (k && !GBD.validKey(k)) { showToast("That does not look like a Sender key"); return; }
          gst.key = k; showToast(k ? "Key saved on this TV" : "Key removed"); gsave(14);
        } },
        { label: "Leggi risposte ad alta voce", right: gst.speak ? "on" : "off", act: function () { gst.speak = !gst.speak; gsave(15); } },
        { label: "Test Grok Bot diretto", right: GBD.enabled(gst) ? "OK ›" : "set URL and key first", act: function () {
          if (!GBD.enabled(gst)) { showToast("Turn it on and save the URL and key first"); return; }
          askDirect("Hi! Reply with one short sentence.", true);
        } },
        { label: "Voce di Grok (xAI, optional, paid API key)", right: gst.xaiOn ? "on" : "off", act: function () { gst.xaiOn = !gst.xaiOn; gsave(17); } },
        { label: "Save the xAI API key typed above", right: GBD.mask(gst.xaiKey), act: function () {
          var k = code.trim();
          if (k && !GBD.validKey(k)) { showToast("That does not look like an API key"); return; }
          gst.xaiKey = k; showToast(k ? "xAI key saved on this TV" : "xAI key removed"); gsave(18);
        } },
        { label: "Grok voice", right: gst.xaiVoice + " ›", act: function () {
          var list = gst.xaiVoices && gst.xaiVoices.length ? gst.xaiVoices : ["eve"];
          gst.xaiVoice = list[(list.indexOf(gst.xaiVoice) + 1) % list.length]; gsave(19);
        } },
        { label: "Test the Grok voice (refreshes the voice list)", right: GBD.xaiOn(gst) ? "OK ›" : "turn on + key first", act: function () {
          if (!GBD.xaiOn(gst)) { showToast("Turn on the Grok voice and save the xAI key first"); return; }
          GBD.xaiVoices(gst).then(function (v) { if (v.length) { gst.xaiVoices = v; GBD.save(window.localStorage, gst); } }, function () {})
            .then(function () { gbdSpeak("Ciao! Sono Grok, con la voce " + gst.xaiVoice + ".", "it"); });
        } });
    }
    var offIdx = items.length;   // Android parity: companion offset steps 0/24/48/96/160 px, up/down half of it
    items.push({ label: "Companion offset (sideways · up/down)", right: (cfg.companionOffsetX || 0) + " · " + (cfg.companionOffsetY || 0), act: function () {
      var st = [0, 24, 48, 96, 160], nx = st[(st.indexOf(cfg.companionOffsetX || 0) + 1) % st.length];
      cfg.companionOffsetX = nx; cfg.companionOffsetY = nx / 2; saveCfg(); renderPluginsUI(); openGrokbotCompanionModal(offIdx);
    } });
    items.push({ label: "⌨ Grok Bot (Omarchy TUI)", right: "›", act: openGrokTui });   // last, so the indices above stay
    openModal("Grok Bot companion", "Avatar, eyes and palette from @tcballard's Grok Bot Desktop Companion v0.1.0 (MIT) · Blue: chat · hold Blue: voice · " +
      (window.OMARCHY_PLATFORM === "tizen" ? "the remote's voice key opens it when the TV lets apps have it" : "the Magic Remote mic stays with webOS"),
      items, "PC ask code (printed by: omarchy-tv webos serve)", function (v) { code = v; }, { noAutoFocus: true, focusIdx: focusIdx || 0 });
  }

  // Blue: chat. Hold Blue (or a Tizen voice/search key, when the TV gives it to apps): chat by voice.
  var GB_VOICE_CODES = {};
  (function registerVoiceKeys() {
    try {
      var tv = window.tizen && window.tizen.tvinputdevice;
      if (!tv || !tv.getSupportedKeys) return;
      tv.getSupportedKeys().forEach(function (k) {
        if (/^(Search|Voice|VoiceRecognition|Mic|Microphone)$/i.test(k.name)) {
          try { tv.registerKey(k.name); GB_VOICE_CODES[k.code] = true; } catch (e) {}
        }
      });
    } catch (e) {}
  })();
  var gbBlue = { timer: 0, held: false };
  document.addEventListener("keydown", function (e) { gbWake(); }, true);
  window.addEventListener("keyup", function (e) {
    if (e.keyCode !== 406 && e.key !== "ColorF3Blue") return;
    if (gbBlue.timer) { clearTimeout(gbBlue.timer); gbBlue.timer = 0; if (!gbBlue.held) openCompanionChat(false); }
    gbBlue.held = false;
  });
  function companionKey(e, code, k) {
    if (!cfg.pluginGrokbot || state.modalOpen) return false;
    if (GB_VOICE_CODES[code]) { cfg.companionHidden = false; openCompanionChat(true); return true; }
    if (code !== 406 && k !== "ColorF3Blue") return false;
    if (e.repeat || gbBlue.timer) return true;
    cfg.companionHidden = false;
    gbBlue.held = false;
    gbBlue.timer = setTimeout(function () { gbBlue.held = true; openCompanionChat(true); }, 650);
    return true;
  }
  setTimeout(function () { syncGrokbotFromPc(false); }, 1500);
  setInterval(function () { if (!document.hidden) syncGrokbotFromPc(false); }, 300000);

  function renderAll() {
    renderTerminal();
    renderWidgets();
    renderDock();
    renderWorkspaces();
    renderPluginsUI();
    if (state.modalOpen) renderModalList();
  }

  function activateBarItem(idx) {
    if (idx === 0) openOmarchyMenu();
    else if (idx >= 1 && idx <= 5) switchWorkspace(idx - 1);
    else if (idx === 6) openCinemarchyModal(0);
    else if (idx === 7) openArcade();   // v2.14: the Arcade icon next to Cinemarchy
    else if (idx === 11) switchWorkspace(1);
    else if (idx === 12) startScreensaver();
    else launchApp({ name: "LG Settings", appId: "com.palm.app.settings" });
  }

  // Home widgets that can take focus, in on-screen order (state.widgetIdx: 0 terminal, 1 weather,
  // 2 pc, 3 ai credits). Hidden ones (Home widgets menu) are skipped, so ▲ ▼ never stop on nothing.
  function focusableWidgets() {
    return [cfg.widgets.term, cfg.widgets.weather, cfg.widgets.pc, cfg.widgets.ai]
      .map(function (on, i) { return on ? i : -1; })
      .filter(function (i) { return i >= 0; });
  }

  // Moving into the widget row: land on a visible widget, or report that there is none.
  function enterWidgets() {
    var vw = focusableWidgets();
    if (!vw.length) return false;
    if (vw.indexOf(state.widgetIdx) < 0) state.widgetIdx = vw[0];
    return true;
  }

  // Keyboard / LG Remote Navigation
  var LEGACY_KEYS = { 8: "Backspace", 13: "Enter", 27: "Escape", 37: "ArrowLeft", 38: "ArrowUp", 39: "ArrowRight", 40: "ArrowDown" };
  window.addEventListener("keydown", function (e) {
    var ov = document.getElementById("saver-overlay");
    if (ov.className === "active") {
      stopScreensaver();
      e.preventDefault();
      return;
    }

    var code = e.keyCode;
    // webOS 3.x (Chrome 38) has no KeyboardEvent.key: derive it from the remote's keyCode
    var k = e.key || LEGACY_KEYS[code] || (code >= 48 && code <= 57 ? String(code - 48) : "");
    var isBack = (k === "Escape" || k === "Backspace" || code === 461 || code === 10009);   // 10009: Samsung Tizen Return

    // A long press opened a menu while OK is still held: drop its auto-repeat so it can't pick an item.
    if (state.okHeld && k === "Enter") { e.preventDefault(); return; }

    if (state.movingId) {
      var step = state.ws === 2 ? 7 : 0;
      if (k === "ArrowLeft") moveBy(-1);
      else if (k === "ArrowRight") moveBy(1);
      else if (k === "ArrowUp" && step) moveBy(-step);
      else if (k === "ArrowDown" && step) moveBy(step);
      else if (k === "Enter") finishMove(true);
      else if (isBack) finishMove(false);
      e.preventDefault();
      e.stopPropagation();
      return;
    }

    if (state.embedOpen) { embedKey(k, isBack); e.preventDefault(); return; }
    if (companionKey(e, code, k)) { e.preventDefault(); return; }

    if (state.modalOpen) {
      if (isBack) { closeModal(); e.preventDefault(); return; }
      if (state.modalDigit && k >= "1" && k <= "9" && document.activeElement !== modalInput) { state.modalDigit(parseInt(k, 10)); e.preventDefault(); return; }
      if (k === "ArrowDown") { state.modalIdx = (state.modalIdx + 1) % Math.max(1, state.modalItems.length); renderModalList(); e.preventDefault(); return; }
      if (k === "ArrowUp") { state.modalIdx = (state.modalIdx - 1 + Math.max(1, state.modalItems.length)) % Math.max(1, state.modalItems.length); renderModalList(); e.preventDefault(); return; }
      if (k === "Enter") {
        var it = state.modalItems[state.modalIdx];
        if (it && it.act) it.act();
        e.preventDefault();
        return;
      }
      return;
    }

    // Number keys 1..5 switch workspaces directly
    if (k >= "1" && k <= "5") {
      switchWorkspace(parseInt(k, 10) - 1);
      e.preventDefault();
      return;
    }

    if (isBack) {
      // webOS hides the app when Back (461) is not cancelled. The Home key still leaves.
      // Back from another workspace returns to the dock; Back on the dock stays in the app.
      if (state.ws !== 0) switchWorkspace(0);
      e.preventDefault();
      e.stopPropagation();
      return;
    }

    if ((k === "c" || k === "C" || code === 67) && !state.modalOpen) {
      openCinemarchyModal(0);
      e.preventDefault();
      return;
    }

    if (state.zone === "bar") {
      if (k === "ArrowRight") { state.barIdx = Math.min(12, state.barIdx + 1); renderAll(); e.preventDefault(); return; }
      if (k === "ArrowLeft") { state.barIdx = Math.max(0, state.barIdx - 1); renderAll(); e.preventDefault(); return; }
      if (k === "ArrowDown") {
        if (state.ws === 0) state.zone = enterWidgets() ? "widgets" : "dock";
        else state.zone = "ws" + (state.ws + 1);
        renderAll(); e.preventDefault(); return;
      }
      if (k === "Enter") { activateBarItem(state.barIdx); e.preventDefault(); return; }
      return;
    }

    if (state.ws === 0 && state.zone === "widgets") {
      if (k === "ArrowRight" || k === "ArrowLeft") {
        var vw = focusableWidgets(), at = vw.indexOf(state.widgetIdx);
        var next = vw[Math.max(0, Math.min(vw.length - 1, at + (k === "ArrowRight" ? 1 : -1)))];
        if (next !== undefined) state.widgetIdx = next;
        renderAll(); e.preventDefault(); return;
      }
      if (k === "ArrowUp") { state.zone = "bar"; renderAll(); e.preventDefault(); return; }
      if (k === "ArrowDown") { state.zone = "dock"; renderAll(); e.preventDefault(); return; }
      if (k === "Enter") {
        if (state.widgetIdx === 0) openThemesModal();
        else if (state.widgetIdx === 1) openWeatherModal();
        else switchWorkspace(3);
        e.preventDefault(); return;
      }
      return;
    }

    if (state.ws === 0 && state.zone === "dock") {
      var apps = visibleApps();
      if (k === "ArrowRight") { state.dockIdx = Math.min(apps.length - 1, state.dockIdx + 1); renderAll(); e.preventDefault(); return; }
      if (k === "ArrowLeft") { state.dockIdx = Math.max(0, state.dockIdx - 1); renderAll(); e.preventDefault(); return; }
      if (k === "ArrowUp") { state.zone = enterWidgets() ? "widgets" : "bar"; renderAll(); e.preventDefault(); return; }
      if (k === "Enter") { pressOk(apps[state.dockIdx]); e.preventDefault(); return; }
      if (k === "ArrowDown" && !state.movingId && pluginExtras().length) { state.zone = "extras"; state.extraIdx = 0; renderAll(); e.preventDefault(); return; }
    }

    if (state.ws === 0 && state.zone === "extras") {
      var ex = pluginExtras();
      if (!ex.length || k === "ArrowUp") { state.zone = "dock"; renderAll(); e.preventDefault(); return; }
      if (k === "ArrowRight") { state.extraIdx = Math.min(ex.length - 1, (state.extraIdx || 0) + 1); renderAll(); e.preventDefault(); return; }
      if (k === "ArrowLeft") { state.extraIdx = Math.max(0, (state.extraIdx || 0) - 1); renderAll(); e.preventDefault(); return; }
      if (k === "Enter") { ex[Math.min(state.extraIdx || 0, ex.length - 1)].open(); e.preventDefault(); return; }
      if (k === "ArrowDown") { e.preventDefault(); return; }
    }

    if (state.ws === 1 && state.zone === "ws2") {
      if (k === "ArrowRight") { state.ws2Idx = Math.max(0, Math.min(TV_INPUTS.length - 1, state.ws2Idx + 1)); renderAll(); e.preventDefault(); return; }
      if (k === "ArrowLeft") { state.ws2Idx = Math.max(0, state.ws2Idx - 1); renderAll(); e.preventDefault(); return; }
      // 4 tiles per row (#ws2-grid): ▼ goes to the row below, ▲ to the row above or the bar
      if (k === "ArrowDown") { if (state.ws2Idx + 4 < TV_INPUTS.length) { state.ws2Idx += 4; renderAll(); } e.preventDefault(); return; }
      if (k === "ArrowUp") {
        if (state.ws2Idx >= 4) state.ws2Idx -= 4;
        else state.zone = "bar";
        renderAll(); e.preventDefault(); return;
      }
      if (k === "Enter") { launchApp(TV_INPUTS[state.ws2Idx]); e.preventDefault(); return; }
    }

    if (state.ws === 2 && state.zone === "ws3") {
      var all = visibleApps();
      if (k === "ArrowRight") { state.ws3Idx = Math.min(all.length - 1, state.ws3Idx + 1); renderAll(); e.preventDefault(); return; }
      if (k === "ArrowLeft") { state.ws3Idx = Math.max(0, state.ws3Idx - 1); renderAll(); e.preventDefault(); return; }
      if (k === "ArrowDown") { state.ws3Idx = Math.min(all.length - 1, state.ws3Idx + 7); renderAll(); e.preventDefault(); return; }
      if (k === "ArrowUp") {
        if (state.ws3Idx >= 7) state.ws3Idx -= 7;
        else state.zone = "bar";
        renderAll(); e.preventDefault(); return;
      }
      if (k === "Enter") { pressOk(all[state.ws3Idx]); e.preventDefault(); return; }
    }

    if (state.ws === 3 && state.zone === "ws4") {
      if (k === "ArrowRight") { state.ws4Idx = 1; renderAll(); e.preventDefault(); return; }
      if (k === "ArrowLeft") { state.ws4Idx = 0; renderAll(); e.preventDefault(); return; }
      if (k === "ArrowUp") { state.zone = "bar"; renderAll(); e.preventDefault(); return; }
      if (k === "Enter") {
        if (state.ws4Idx === 1) {
          cfg.pcSimulated = !cfg.pcSimulated;
          saveCfg(); pollPcStatus(); renderAll();
        } else {
          // Typed address is used (issue #5, point 4): "Connect to …" appears as the first item once it looks valid.
          var pairTyped = "";
          var pairItems = [
            { label: "Use 192.168.1.100:8765", right: "LAN", act: function () { cfg.pcHost = "192.168.1.100"; saveCfg(); pollPcStatus(); closeModal(); } },
            { label: "Toggle simulated PC link", right: cfg.pcSimulated ? "on" : "off", act: function () { cfg.pcSimulated = !cfg.pcSimulated; saveCfg(); pollPcStatus(); closeModal(); } }
          ];
          openModal("Pair Omarchy Linux PC", "Enter your Omarchy PC LAN IP (serves http://<ip>:8765/v1/status):", pairItems.slice(), "192.168.1.x", function (v) {
            pairTyped = (v || "").trim();
            var ok = /^(https?:\/\/)?[A-Za-z0-9.-]+(:\d{1,5})?\/?$/.test(pairTyped);
            state.modalItems = (ok ? [{ label: "Connect to " + pairTyped, right: "OK", act: function () {
              cfg.pcHost = pairTyped.replace(/\/+$/, ""); cfg.pcSimulated = false; saveCfg(); pollPcStatus(); closeModal();
            } }] : []).concat(pairItems);
            state.modalIdx = 0; renderModalList();
          });
        }
        e.preventDefault(); return;
      }
    }

    if (state.ws === 4 && state.zone === "ws5") {
      var rows = getSysRows();
      if (k === "ArrowDown") { state.ws5Idx = Math.min(rows.length - 1, state.ws5Idx + 1); renderAll(); e.preventDefault(); return; }
      if (k === "ArrowUp") {
        if (state.ws5Idx > 0) state.ws5Idx--;
        else state.zone = "bar";
        renderAll(); e.preventDefault(); return;
      }
      if (k === "Enter") { rows[state.ws5Idx].act(); e.preventDefault(); return; }
    }
  });

  // OK on an app tile: a short press launches on key-up, holding it for LONG_OK_MS opens the tile menu.
  var LONG_OK_MS = 600;
  function pressOk(app) {
    if (state.okTimer) return; // auto-repeat while held
    state.okTimer = setTimeout(function () {
      state.okTimer = null;
      state.okHeld = true;
      // Walker focuses its search field, which brings up webOS's on-screen keyboard; opened while OK
      // is still held, the keyboard would take OK's auto-repeat and type letters. Open it on release.
      if (app && app.id === "tv") state.walkerOnRelease = true;
      else openTileMenu(app);
    }, LONG_OK_MS);
    state.okApp = app;
  }

  window.addEventListener("keyup", function (e) {
    if (e.keyCode !== 13 && e.key !== "Enter") return;
    if (state.okHeld) {
      state.okHeld = false;
      if (state.walkerOnRelease) { state.walkerOnRelease = false; openWalkerModal(); }
      e.preventDefault();
      return;
    }
    if (state.okTimer) {
      clearTimeout(state.okTimer);
      state.okTimer = null;
      launchApp(state.okApp);
      e.preventDefault();
    }
  });

  // Click bindings for Waybar and Widgets
  var waybarButtons = document.querySelectorAll("[data-bar-idx]");
  for (var bi = 0; bi < waybarButtons.length; bi++) {
    (function (button) {
      var idx = parseInt(button.getAttribute("data-bar-idx"), 10);
      button.onclick = function () {
        if (state.modalOpen) return;
        state.barIdx = idx;
        state.zone = "bar";
        renderAll();
        activateBarItem(idx);
      };
      button.onmouseenter = function () {
        if (state.modalOpen) return;
        state.barIdx = idx;
        state.zone = "bar";
        renderAll();
      };
    })(waybarButtons[bi]);
  }

  var spEl = document.getElementById("spacex-pill");
  if (spEl) spEl.onclick = openSpacexModal;
  var gbEl = document.getElementById("grokbot-companion");
  if (gbEl) gbEl.onclick = function () { openCompanionChat(false); };
  document.getElementById("wx-box").onclick = openWeatherModal;
  document.getElementById("term-box").onclick = openThemesModal;
  document.getElementById("pc-box").onclick = function () { switchWorkspace(3); };
  document.getElementById("ai-box").onclick = function () { switchWorkspace(3); };
  document.getElementById("ws4-pair-btn").onclick = function () { state.zone = "ws4"; state.ws4Idx = 0; renderAll(); };
  document.getElementById("ws4-demo-btn").onclick = function () { cfg.pcSimulated = !cfg.pcSimulated; saveCfg(); pollPcStatus(); renderAll(); };

  // Expose helper API for automated simulator tests
  window.OmarchyTestAPI = {
    switchWorkspace: switchWorkspace,
    openWeatherModal: openWeatherModal,
    openThemesModal: openThemesModal,
    openWalkerModal: openWalkerModal,
    openCinemarchyModal: openCinemarchyModal,
    openPluginsModal: openPluginsModal,
    openSpacexModal: openSpacexModal,
    openGrokbotCompanionModal: openGrokbotCompanionModal,
    openCompanionChat: openCompanionChat,
    openOmarchyMenu: openOmarchyMenu,
    openLightsModal: openLightsModal,
    openAllSettingsModal: openAllSettingsModal,
    openEmbed: openEmbed,
    closeEmbed: closeEmbed,
    openAboutModal: openAboutModal,
    startScreensaver: startScreensaver,
    stopScreensaver: stopScreensaver,
    setTheme: function (t) { cfg.theme = t; cfg.wallpaperIdx = 0; saveCfg(); applyTheme(); renderAll(); },
    setTempUnit: function (u) { cfg.tempUnit = u; saveCfg(); renderAll(); },
    setLang: function (l) { cfg.lang = l; saveCfg(); renderAll(); },
    setSimulatedPc: function (v) { cfg.pcSimulated = v; saveCfg(); pollPcStatus(); renderAll(); },
    connectPc: function (host) { cfg.pcSimulated = false; cfg.pcHost = host; saveCfg(); return pollPcStatus().then(function (s) { renderAll(); return s; }); }
  };

  // Boot
  fitStage();
  applyTheme();
  // Boot screen: the real network and omarchy-link state, asked while their spinners turn (boot.js clock()).
  function bootLive(key, timedOut) {
    if (key === "net") {
      if (navigator.onLine === false) return timedOut ? { ok: false, text: "Network Manager: no connection yet." } : null;
      return { ok: true, text: "Network online." };
    }
    if (key === "pc") {
      if (!cfg.pcHost && !cfg.pcSimulated) return { ok: true, text: "Started omarchy-link.service (no PC paired yet)." };
      var st = state.pcStatus;
      if (st) return { ok: true, text: "omarchy-link: connected to " + (st.host || "your PC") + "." };
      return timedOut ? { ok: false, text: "omarchy-link: " + (cfg.pcHost || "your PC") + " not reachable." } : null;
    }
    return { ok: true, text: "" };
  }
  // Boot screen "System Model": the TV's real model when the platform says it, else a generic name.
  function tvModel() {
    try {   // Samsung: only when webapis.js is loaded
      var pi = window.webapis && window.webapis.productinfo;
      var sm = pi && (pi.getRealModel ? pi.getRealModel() : pi.getModel && pi.getModel());
      if (sm) return "Samsung " + sm;
    } catch (e) {}
    try {   // LG: PalmSystem.deviceInfo is a JSON string with modelName
      var d = window.PalmSystem && window.PalmSystem.deviceInfo && JSON.parse(window.PalmSystem.deviceInfo);
      if (d && d.modelName) return "LG " + d.modelName;
    } catch (e) {}
    return window.tizen ? "Samsung Smart TV (Tizen)" : "LG Smart TV (webOS)";
  }
  // Retro CRT: once the boot screen exists, fit it into the set (poll briefly; boot.js builds it on the next frames).
  [50, 300, 800].forEach(function (ms) { setTimeout(function () { if (crtMode() !== "off") fitStage(); }, ms); });
  if (window.OmarchyBoot) {
    window.OmarchyBoot.install(function () {
      var bt = currentThemeObj();
      var mem = Number(navigator.deviceMemory) || 0;   // GB, rounded by the browser; absent on old engines
      return {
        mode: cfg.bootScreen || "boot",
        sound: cfg.bootSound !== false,
        qr: cfg.shareQr === "boot" && window.OmarchyQR ? window.OmarchyQR.PROJECT_URL : "",
        version: APP_VERSION,
        theme: cfg.theme,
        model: tvModel(),
        ramMb: mem * 1024,
        display: (function () {   // the web UI's mode: TVs often report 1920x1080 even on a 4K panel
          try { var r = window.devicePixelRatio || 1; return Math.round(screen.width * r) + "x" + Math.round(screen.height * r); } catch (e) { return ""; }
        })(),
        apps: (function () { try { return visibleApps().length; } catch (e) { return -1; } })(),
        live: bootLive,
        user: cfg.promptUser,
        host: cfg.promptHost,
        pcLinked: !!cfg.pcHost && !cfg.pcSimulated,
        fx: cfg.bootFx !== false,
        colors: { bg: bt.bg, fg: bt.fg, fgBright: bt.fgBright, fgDim: bt.fgDim, accent: bt.accent, accent2: bt.accent2, green: bt.green, red: bt.red, yellow: bt.yellow }
      };
    });
  }
  updateClock();
  setInterval(updateClock, 15000);
  animateLogo();
  renderAll();
  fetchWeather();
  pollPcStatus();

  // Keep the PC panels live: refresh /v1/status every 5 s while the app is on screen. A request that
  // gets no answer (e.g. a firewall dropping packets) can hang for a long time, so skip ticks while one
  // is still pending instead of piling them up.
  var pcPollPending = false;
  setInterval(function () {
    if (pcPollPending || document.hidden) return;
    pcPollPending = true;
    pollPcStatus().then(function () { pcPollPending = false; }, function () { pcPollPending = false; });
  }, 5000);
})();
