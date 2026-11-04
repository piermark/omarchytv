/* OmaFire night.js by Wes Grimes, MIT (see LICENSE next to this file), from
 * https://github.com/wesleygrimes/omafire commit 7b9a627d5c28680f43fc5fad8c87c9b9b0506bfe ui/fire/night.js.
 * Unchanged except: the QML ".pragma library" line is dropped and the file is wrapped so its functions
 * stay private, exposing them as window.OmaFireNight for OmarchyTV's OmaFire sound (omafire-audio.js). */
(function (root) {
// Distant night life for the campsite. Crickets and frogs are scheduled here;
// FireAudio plays the clips. Plain JavaScript, same as fire.js, so node can
// test the pacing without Qt.
//
// A field cricket sings a short regular phrase, then rests. A second cricket
// sometimes answers one chirp of the phrase. A frog across the water calls
// much less often, and now and then doubles the call. Nothing starts the
// instant sound is turned on, and two animals don't begin on the same instant.

function rand(a) {
    a.seed = (a.seed * 1664525 + 1013904223) >>> 0;
    return a.seed / 4294967296;
}

function createAmbience(seed) {
    var a = {
        seed: seed >>> 0,
        chirpsLeft: 0,
        period: 0,
        boutGain: 0,
        cricketWait: 0,
        answerIn: -1,
        frogWait: 0,
        echoIn: -1,
        echoKind: "peep",
        echoGain: 0,
        echoYielded: false,
        // Seconds until another call may start. Keeps a chirp and a frog
        // from beginning on top of each other.
        hold: 0
    };
    // Let the fire be the first thing you hear.
    a.cricketWait = 6 + rand(a) * 3.5;
    a.frogWait = 22 + rand(a) * 12;
    return a;
}

function startBout(a) {
    // Four to seven chirps, one rate for the whole phrase (a cool night,
    // a bit under one chirp a second), one loudness so it stays one insect.
    a.chirpsLeft = 4 + ((rand(a) * 4) | 0);
    a.period = 0.86 + rand(a) * 0.24;
    a.boutGain = 0.72 + rand(a) * 0.28;
}

// dt is a slice of time while sound is on and the popup is open. Returns the
// calls that begin in this slice. A slice longer than a quarter second is
// cut, so a stalled frame can't dump a whole phrase at once.
function advanceAmbience(a, dt) {
    var ev = [], late, kind, gain, gap;
    if (!(dt > 0)) return ev;
    if (dt > 0.25) dt = 0.25;
    a.hold = Math.max(0, a.hold - dt);
    a.cricketWait -= dt;
    if (a.answerIn >= 0) a.answerIn -= dt;
    a.frogWait -= dt;
    if (a.echoIn >= 0) a.echoIn -= dt;

    // The phrase keeps its rhythm. Anything else that comes due in the same
    // moment waits until this call has room to be heard.
    if (a.cricketWait <= 0) {
        late = -a.cricketWait;
        if (a.chirpsLeft <= 0) startBout(a);
        ev.push({ kind: "cricket", gain: a.boutGain, answer: false });
        a.hold = 0.28;
        a.chirpsLeft--;
        // Another cricket, a little farther off, answers one chirp of some phrases.
        if (a.answerIn < 0 && a.period >= 1.0 && rand(a) < 0.16)
            a.answerIn = 0.30 + rand(a) * 0.08;
        if (a.chirpsLeft <= 0) {
            gap = 14 + rand(a) * 12;
            if (rand(a) < 0.18) gap += 16 + rand(a) * 20;
        } else {
            gap = a.period;
        }
        a.cricketWait = gap - late;
    }

    // A chirp that is about to start keeps the moment. Callers wait it out.
    var chirpSoon = a.cricketWait > 0 && a.cricketWait < 0.3;
    if (a.answerIn <= 0 && a.answerIn !== -1) {
        if (a.hold > 0 || chirpSoon) a.answerIn = Math.max(a.hold, chirpSoon ? a.cricketWait : 0) + 0.05;
        else {
            ev.push({ kind: "cricket", gain: 0.42 + rand(a) * 0.16, answer: true });
            a.hold = 0.28;
            a.answerIn = -1;
        }
    }

    if (a.frogWait <= 0) {
        if (a.hold > 0 || chirpSoon) {
            a.frogWait = Math.max(a.hold, chirpSoon ? a.cricketWait : 0) + 0.35;
        } else {
            kind = rand(a) < 0.65 ? "peep" : "croak";
            gain = 0.68 + rand(a) * 0.32;
            ev.push({ kind: kind, gain: gain, echo: false });
            a.hold = 0.28;
            if (rand(a) < 0.25) {
                a.echoIn = 1.1 + rand(a) * 0.35;
                a.echoKind = kind;
                a.echoGain = gain * 0.72;
                a.echoYielded = false;
            }
            gap = 28 + rand(a) * 22;
            if (rand(a) < 0.28) gap += 20 + rand(a) * 30;
            a.frogWait = gap;
        }
    }

    if (a.echoIn <= 0 && a.echoIn !== -1) {
        // Yield once, so a doubled call stays a pair instead of being walked
        // forward through a whole cricket phrase.
        if (!a.echoYielded && (a.hold > 0 || chirpSoon)) {
            a.echoIn = Math.max(a.hold, chirpSoon ? a.cricketWait : 0) + 0.12;
            a.echoYielded = true;
        } else {
            ev.push({ kind: a.echoKind, gain: a.echoGain, echo: true });
            a.hold = 0.28;
            a.echoIn = -1;
        }
    }
    return ev;
}

if (typeof module !== "undefined" && module.exports) {
    module.exports = {
        createAmbience: createAmbience, advanceAmbience: advanceAmbience
    };
}

root.OmaFireNight = { createAmbience: createAmbience, advanceAmbience: advanceAmbience };
})(typeof window !== "undefined" ? window : this);
