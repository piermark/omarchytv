#!/bin/sh
# OmarchyTV start-up and Home-button helper for ROOTED LG webOS TVs (Homebrew Channel).
#
# OmarchyTV installs this from All settings; nothing here runs on unrooted TVs.
# Homebrew Channel runs /var/lib/webosbrew/init.d/omarchytv at boot, which starts this script.
# Behaviour is driven by flag files in /var/lib/omarchytv/ (the settings toggles create/remove them):
#   boot  open OmarchyTV once the TV has started
#   home  when LG Home comes to the front, open OmarchyTV instead. Pressing Home while
#         OmarchyTV is already in front still shows LG Home, so LG's own menus stay reachable.
# With neither flag present the script exits.
#
# webOS 3.x differences handled here:
#   - the application manager answers only as com.webos.applicationManager;
#   - Home does not bring an LG Home app to the front: it overlays the launcher bar on the
#     current app. surfacemanager getCurrentState then reports "home", but its subscription
#     never pushes changes, so the state is polled.
APP=com.omarchytv.home
LG_HOME=com.webos.app.home
FLAGS=/var/lib/omarchytv
PIDFILE=/tmp/omarchytv-home.pid
LOG=/tmp/omarchytv-home.log

[ -f "$FLAGS/boot" ] || [ -f "$FLAGS/home" ] || exit 0
if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then exit 0; fi
echo $$ > "$PIDFILE"

log() { echo "$(date '+%F %T') $*" >> "$LOG"; }

# On some webOS versions luna-send prints nothing without a TTY; script(1) provides one.
# script(1) quits as soon as its stdin hits EOF, which can cut the reply off, so keep its
# stdin open: for a while for one-shot calls, forever for subscriptions (-i).
DIRECT=0
luna-send -n 1 luna://com.webos.service.tvpower/power/getPowerState '{}' </dev/null 2>/dev/null |
    grep -q returnValue && DIRECT=1
luna() {
    if [ $DIRECT = 1 ]; then
        eval "luna-send $*" </dev/null
    elif [ "$1" = "-i" ]; then
        tail -f /dev/null | script -q -c "luna-send $*" /dev/null
    else
        sleep 10 | script -q -c "luna-send $*" /dev/null
    fi
}

# Pick the application manager name this webOS answers to.
AM=""
n=0
while [ -z "$AM" ]; do
    for s in com.webos.service.applicationmanager com.webos.applicationManager; do
        if luna -n 1 "luna://$s/getForegroundAppInfo" "'{}'" | grep -q '"returnValue": *true'; then
            AM=$s; break
        fi
    done
    [ -n "$AM" ] && break
    n=$((n + 1)); [ $n -gt 120 ] && { log "no application manager answered, exiting"; rm -f "$PIDFILE"; exit 1; }
    sleep 2
done
log "started (application manager: $AM, direct luna-send: $DIRECT)"

launch() { luna -n 1 "luna://$AM/launch" "'{\"id\":\"$APP\"}'" >/dev/null; }
foreground() { luna -n 1 "luna://$AM/getForegroundAppInfo" "'{}'" | sed -n 's/.*"appId": *"\([^"]*\)".*/\1/p' | head -n 1; }
bar_state() { luna -n 1 luna://com.webos.surfacemanager/getCurrentState "'{}'" | sed -n 's/.*"currentState": *"\([^"]*\)".*/\1/p' | head -n 1; }
screen_on() {
    p=$(luna -n 1 luna://com.webos.service.tvpower/power/getPowerState "'{}'")
    echo "$p" | grep -q '"Active"' && ! echo "$p" | grep -q 'Screen Off'
}

if [ "$1" != "--no-boot" ] && [ -f "$FLAGS/boot" ]; then
    sleep 5
    screen_on && { log "boot: opening $APP"; launch; }
fi

# webOS 3.x: Home overlays the launcher bar while the current app stays in front.
watch_home_bar() {
    [ -n "$(bar_state)" ] || return 0
    log "watching the Home bar (surfacemanager getCurrentState)"
    last=""
    while [ -f "$FLAGS/home" ]; do
        st=$(bar_state)
        if [ "$st" = "home" ] && [ "$last" != "home" ]; then
            fg=$(foreground)
            if [ "$fg" != "$APP" ] && screen_on; then
                log "Home bar shown over '$fg': opening $APP"
                launch
            fi
        fi
        last=$st
        sleep 1
    done
}
watch_home_bar &

# Newer webOS: Home brings com.webos.app.home to the front.
while [ -f "$FLAGS/home" ]; do
    prev=""
    luna -i "luna://$AM/getForegroundAppInfo" "'{\"subscribe\":true}'" |
    while IFS= read -r line; do
        [ -f "$FLAGS/home" ] || exit 0
        app=$(echo "$line" | sed -n 's/.*"appId": *"\([^"]*\)".*/\1/p')
        [ -z "$app" ] && continue
        if [ "$app" = "$LG_HOME" ] && [ "$prev" != "$APP" ] && screen_on; then
            log "Home pressed in '$prev': opening $APP"
            launch
        fi
        prev=$app
    done
    sleep 3
done
log "home redirect off, exiting"
rm -f "$PIDFILE"
