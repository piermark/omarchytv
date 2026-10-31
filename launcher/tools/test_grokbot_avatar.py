#!/usr/bin/env python3
"""Android/JS parity for Tom Ballard's Grok Bot avatar, without building the Android app.

1. GrokbotAvatarData.java is what gen-grokbot-avatar.py makes from the vendored MIT JS (--check).
2. Read back independently from the Java source: all 14 shape names in his order, 120-point outlines, eyes,
   stem, look table, every alias, the 11 palette colours and the poses equal his shapes.js / palette.js / face.js.
3. The face math in GrokbotAvatar.java (eyeBox, rotation, lookRoll, rotate, centroid, rotatePoints, fitAt,
   look) is transliterated mechanically to JavaScript and compared with his face.js on every shape over a
   grid of gazes, so a slip in the Java port shows up here.

Run: python3 launcher/tools/test_grokbot_avatar.py
"""
import json
import pathlib
import re
import subprocess
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
VENDOR = ROOT / "webos/app/vendor/grokbot/grokbot-avatar.js"
JAVA = ROOT / "launcher/app/src/main/java/com/omarchytv/home"
GEN = ROOT / "launcher/tools/gen-grokbot-avatar.py"


def node(script, *args):
    return json.loads(subprocess.check_output(["node", "-e", script, *map(str, args)], text=True))


VENDOR_DUMP = r"""
const fs = require("fs"); const window = {};
eval(fs.readFileSync(process.argv[1], "utf8"));
const S = window.GrokbotShapes, P = window.GrokbotPalette, F = window.GrokbotFace;
console.log(JSON.stringify({
  names: S.NAMES, aliases: S.ALIASES, stemShape: S.STEM_SHAPE, stemColor: S.STEM_COLOR,
  shapes: S.NAMES.map(n => ({ points: S.SHAPES[n].points, eyes: S.SHAPES[n].eyes, stem: S.SHAPES[n].stem, look: S.look(n) })),
  colorNames: P.NAMES, colors: P.COLORS, colorAliases: P.ALIASES, cutout: P.CUTOUT, poses: F.POSES }));
"""


def java_strings(text, field):
    """The string literals of `static final String[] FIELD = {...};` or `static final String FIELD = "...";`."""
    m = re.search(r"static final String(\[\])? " + field + r" = (\{.*?\};|\".*?\";)", text, re.S)
    assert m, field
    return re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(2))


def floats(csv):
    return [float(x) for x in csv.split(",")]


class DataParity(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.js = node(VENDOR_DUMP, VENDOR)
        cls.java = (JAVA / "GrokbotAvatarData.java").read_text()

    def test_generated_file_is_current(self):
        r = subprocess.run([sys.executable, str(GEN), "--check"], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)

    def test_fourteen_shapes_in_his_order(self):
        names = java_strings(self.java, "NAMES")
        self.assertEqual(len(names), 14)
        self.assertEqual(names, self.js["names"])

    def test_outlines_eyes_stem_and_look(self):
        pts, eyes, look = (java_strings(self.java, f) for f in ("POINTS", "EYES", "LOOK"))
        self.assertEqual(len(pts), 14)
        for i, s in enumerate(self.js["shapes"]):
            p = floats(pts[i])
            self.assertEqual(len(p), 240, self.js["names"][i])
            self.assertEqual(p, [c for xy in s["points"] for c in xy])
            self.assertEqual(floats(eyes[i]), [e[k] for e in s["eyes"] for k in ("x", "y", "length", "width", "angle")])
            self.assertEqual(floats(look[i]), s["look"]["face"] + [s["look"]["size"]] + s["look"]["fit"])
            self.assertEqual(len(s["look"]["fit"]), 81)
        stem = next(s["stem"] for s in self.js["shapes"] if s["stem"])
        self.assertEqual(floats(java_strings(self.java, "STEM")[0]), [c for xy in stem for c in xy])
        self.assertEqual(java_strings(self.java, "STEM_SHAPE"), [self.js["stemShape"]])
        self.assertEqual(java_strings(self.java, "STEM_COLOR"), [self.js["stemColor"]])

    def test_every_alias(self):
        a = java_strings(self.java, "SHAPE_ALIASES")
        self.assertEqual(dict(zip(a[0::2], a[1::2])), self.js["aliases"])

    def test_eleven_palette_colours(self):
        c = java_strings(self.java, "COLORS")
        rows = [c[i:i + 4] for i in range(0, len(c), 4)]
        self.assertEqual([r[0] for r in rows], self.js["colorNames"])
        self.assertEqual(len(rows), 11)
        for name, fill, dark, eye in rows:
            tone = self.js["colors"][name]
            self.assertEqual(fill, tone["fill"])
            self.assertEqual(dark, tone.get("darkFill", ""))
            self.assertEqual(eye, tone["eye"])
        a = java_strings(self.java, "COLOR_ALIASES")
        self.assertEqual(dict(zip(a[0::2], a[1::2])), self.js["colorAliases"])
        self.assertEqual(java_strings(self.java, "CUTOUT"), [self.js["cutout"]])

    def test_poses(self):
        p = java_strings(self.java, "POSES")
        got = {p[i]: [float(p[i + 1]), float(p[i + 2]), float(p[i + 3])] for i in range(0, len(p), 4)}
        want = {k: [v["tilt"], v["height"], v["open"]] for k, v in self.js["poses"].items()}
        self.assertEqual(got, want)


METHODS = ["eyeBox", "rotation", "lookRoll", "rotate", "centroid", "rotatePoints", "fitAt", "at"]


def method_source(java, name, params_regex=r"[^)]*"):
    m = re.search(r"\n    (?:private )?static [\w\[\]]+ " + name + r"\((" + params_regex + r")\) \{\n(.*?)\n    \}\n", java, re.S)
    assert m, name
    return m.group(1), m.group(2)


def to_js(name, params, body):
    params = ", ".join(p.split()[-1] for p in params.split(",") if p.strip())
    b = body
    b = re.sub(r"new float\[\]\{", "[", b)
    b = re.sub(r"\[(\w+(?: [-+*/] \w+)*)\]\}", r"[\1]]", b)   # not used; kept harmless
    b = b.replace("}", "}")
    b = re.sub(r"\(float\) |\(int\) |\(double\) ", "", b)
    b = re.sub(r"new float\[(\w+(?:\.\w+)?)\]", r"new Array(\1).fill(0)", b)
    b = re.sub(r"\b(?:final )?(?:float|double|int|boolean)(?:\[\])? (?=\w+ ?[=;,])", "var ", b)
    b = re.sub(r"\bLook (\w+) = new Look\(\);", r"var \1 = { eyes: new Array(10).fill(0) };", b)
    b = re.sub(r"(\d)f\b", r"\1", b)
    b = b.replace("Float.isNaN", "isNaN")
    return "function " + name + "(" + params + ") {\n" + b + "\n}\n"


def java_math_as_js(java):
    out = []
    for n in METHODS:
        params, body = method_source(java, n)
        # array literal "new float[]{a, b}" → "[a, b]": close brace after the list
        body = re.sub(r"new float\[\]\{([^{}]*)\}", r"[\1]", body)
        out.append(to_js(n, params, body))
    params, body = method_source(java, "look", r"int shape, float gx, float gy")
    body = body.replace("look(shape)", "lookParams(shape)")
    body = re.sub(r"new float\[\]\{([^{}]*)\}", r"[\1]", body)
    out.append(to_js("lookG", params, body))
    js = "\n".join(out)
    for const in ("LOOK_X", "LOOK_UP", "LOOK_DOWN", "FRONT_SEP", "FRONT_LENGTH", "FRONT_WIDTH", "PICKER_ROLL", "FIT_STEPS"):
        m = re.search(r"\b" + const + r" = (-?[\d.]+)f?\b", java)
        js = "var " + const + " = " + m.group(1) + ";\n" + js
    return js


MATH_COMPARE = r"""
const fs = require("fs"); const window = {};
eval(fs.readFileSync(process.argv[1], "utf8"));
eval(fs.readFileSync(process.argv[2], "utf8"));
const S = window.GrokbotShapes, F = window.GrokbotFace;
const flat = a => [].concat(...a);
function points(i) { return flat(S.SHAPES[S.NAMES[i]].points); }
function lookParams(i) { const l = S.look(S.NAMES[i]); return l.face.concat([l.size], l.fit); }
let worst = 0, n = 0; const bad = [];
function cmp(a, b, what) { const d = Math.abs(a - b); n++; if (!(d <= worst)) worst = d; if (!(d < 1e-6)) bad.push([what, a, b]); }
S.NAMES.forEach((name, i) => {
  const sh = S.SHAPES[name], params = S.look(name);
  const c = F.look(sh.points, params, 0, 0).centre, cj = centroid(points(i));
  cmp(cj[0], c[0], name + " centroid x"); cmp(cj[1], c[1], name + " centroid y");
  for (let gx = -1; gx <= 1.0001; gx += 0.25) for (let gy = -1; gy <= 1.0001; gy += 0.25) {
    const want = F.look(sh.points, params, gx, gy), got = lookG(i, gx, gy);
    cmp(got.roll, want.roll, name + " roll"); cmp(got.turn, want.turn, name + " turn");
    cmp(fitAt(lookParams(i), gx, gy), F.fitAt(params.fit, gx, gy), name + " fit");
    want.eyes.forEach((e, k) => ["x", "y", "length", "width", "angle"].forEach((f, m) => cmp(got.eyes[k * 5 + m], e[f], name + " eye " + f)));
    const rp = rotatePoints(points(i), want.turn, want.centre[0], want.centre[1]);
    const wp = flat(F.rotatePoints(sh.points, want.turn, want.centre));
    for (let q = 0; q < wp.length; q += 17) cmp(rp[q], wp[q], name + " rotated point");
  }
  sh.eyes.forEach(e => [0, 0.04, 0.2, 0.5, 1].forEach(o => [0.62, 1, 1.12].forEach(hs => {
    const w = F.eye(e, 25, o, hs), g = eyeBox(e.length, e.width, 25, o, hs);
    cmp(g[0], w.w, "eye w"); cmp(g[1], w.h, "eye h"); cmp(g[2], w.r, "eye r");
    cmp(rotation(e.angle, 5), F.rotation(e, 5), "rotation");
  })));
});
console.log(JSON.stringify({ n, worst, bad: bad.slice(0, 5) }));
"""


class MathParity(unittest.TestCase):
    def test_java_face_math_matches_his_face_js(self):
        js = java_math_as_js((JAVA / "GrokbotAvatar.java").read_text())
        tmp = pathlib.Path("/tmp/grokbot-avatar-java-as.js")
        tmp.write_text(js)
        r = node(MATH_COMPARE, VENDOR, tmp)
        self.assertGreater(r["n"], 30000)
        self.assertEqual(r["bad"], [], js[:400])


if __name__ == "__main__":
    unittest.main(verbosity=2)
