/* v2.20 Home wallpaper slideshow: still fractals (our own art). No canvas loop on Home. */
(function (root) {
  root.OmarchyFractalSlides = {
  "intervalMs": 12000,
  "fadeMs": 1600,
  "width": 1280,
  "height": 720,
  "slides": [
    {
      "file": "fractals/01-mandelbrot-seahorses.jpg",
      "pal": [
        [
          0.4235,
          0.0471,
          0.8118
        ],
        [
          0.45,
          0.08,
          0.42
        ],
        [
          0.2235,
          0.1569,
          0.2902
        ],
        [
          0.95,
          0.8,
          0.7
        ]
      ]
    },
    {
      "file": "fractals/02-mandelbrot-elephants.jpg",
      "pal": [
        [
          0.2078,
          0.1843,
          0.0588
        ],
        [
          0.45,
          0.22,
          0.05
        ],
        [
          0.6118,
          0.4745,
          0.3647
        ],
        [
          1.0,
          0.95,
          0.7
        ]
      ]
    },
    {
      "file": "fractals/03-mandelbrot-spiral.jpg",
      "pal": [
        [
          0.8078,
          0.8431,
          1.0
        ],
        [
          0.48,
          0.64,
          0.97
        ],
        [
          0.1098,
          0.1961,
          0.2392
        ],
        [
          0.49,
          0.81,
          1.0
        ]
      ]
    },
    {
      "file": "fractals/04-julia-dendrite.jpg",
      "pal": [
        [
          0.0863,
          0.2078,
          0.1882
        ],
        [
          0.2,
          0.4,
          0.7
        ],
        [
          0.3686,
          0.498,
          0.9529
        ],
        [
          0.95,
          0.98,
          1.0
        ]
      ]
    },
    {
      "file": "fractals/05-julia-rabbit.jpg",
      "pal": [
        [
          0.3294,
          0.1412,
          0.1412
        ],
        [
          1.0,
          0.82,
          0.25
        ],
        [
          1.0,
          0.6118,
          1.0
        ],
        [
          0.36,
          0.36,
          1.0
        ]
      ]
    },
    {
      "file": "fractals/06-julia-silk.jpg",
      "pal": [
        [
          0.6118,
          0.3373,
          1.0
        ],
        [
          0.35,
          0.15,
          0.55
        ],
        [
          0.1765,
          0.1725,
          0.298
        ],
        [
          0.95,
          0.85,
          0.7
        ]
      ]
    },
    {
      "file": "fractals/07-burning-ship.jpg",
      "pal": [
        [
          0.04,
          0.0,
          0.02
        ],
        [
          0.45,
          0.02,
          0.1
        ],
        [
          0.9,
          0.15,
          0.2
        ],
        [
          1.0,
          0.8,
          0.55
        ]
      ]
    },
    {
      "file": "fractals/08-tricorn.jpg",
      "pal": [
        [
          0.0392,
          0.2471,
          0.0667
        ],
        [
          0.08,
          0.32,
          0.18
        ],
        [
          0.3529,
          0.549,
          0.4
        ],
        [
          0.95,
          0.9,
          0.45
        ]
      ]
    },
    {
      "file": "fractals/09-newton.jpg",
      "pal": [
        [
          0.098,
          1.0,
          0.8353
        ],
        [
          1.0,
          0.82,
          0.25
        ],
        [
          1.0,
          0.0392,
          0.4588
        ],
        [
          0.36,
          0.36,
          1.0
        ]
      ]
    },
    {
      "file": "fractals/10-multibrot.jpg",
      "pal": [
        [
          0.2275,
          0.1961,
          0.1059
        ],
        [
          0.55,
          0.2,
          0.08
        ],
        [
          0.7098,
          0.3451,
          0.2784
        ],
        [
          1.0,
          0.88,
          0.65
        ]
      ]
    },
    {
      "file": "fractals/11-kaliset.jpg",
      "pal": [
        [
          0.1647,
          0.2118,
          0.0235
        ],
        [
          0.1,
          0.4,
          0.38
        ],
        [
          0.6314,
          0.902,
          0.9608
        ],
        [
          0.95,
          0.9,
          0.55
        ]
      ]
    },
    {
      "file": "fractals/12-mandelbrot-antenna.jpg",
      "pal": [
        [
          0.1176,
          0.1961,
          0.2392
        ],
        [
          0.02,
          0.28,
          0.42
        ],
        [
          0.2118,
          0.5137,
          0.9294
        ],
        [
          0.85,
          0.95,
          0.8
        ]
      ]
    }
  ]
};

})(this);

(function (root) {
  var api = root.OmarchyFractalSlides;
  api.mount = function (parent) {
    if (!parent || !api.slides || !api.slides.length) return null;
    var box = parent.ownerDocument.createElement("div");
    box.className = "radio-fractal-slides";
    box.setAttribute("aria-hidden", "true");
    box.style.cssText = "position:absolute;left:0;top:0;right:0;bottom:0;overflow:hidden;pointer-events:none;z-index:0;";
    var cssId = "radio-fractal-slides-css";
    var doc = parent.ownerDocument;
    if (!doc.getElementById(cssId)) {
      var css = doc.createElement("style");
      css.id = cssId;
      css.textContent = ".radio-fractal-slides img{position:absolute;left:0;top:0;width:100%;height:100%;object-fit:cover;border:0;opacity:0;transition:opacity 1.6s ease;will-change:opacity,transform;transform-origin:center center;}"
        + ".radio-fractal-slides img.on{opacity:1;}"
        + "@keyframes radio-fractal-kb{from{transform:scale(1) translate(0,0);}to{transform:scale(1.05) translate(0.7%,-0.5%);}}"
        + ".radio-fractal-slides img.kb{animation:radio-fractal-kb 13.6s linear forwards;}";
      (doc.head || parent).appendChild(css);
    }
    var a = doc.createElement("img"), b = doc.createElement("img");
    a.alt = b.alt = "";
    a.draggable = b.draggable = false;
    box.appendChild(a); box.appendChild(b);
    parent.insertBefore(box, parent.firstChild);
    var front = 0, idx = 0, timer = 0, imgs = [a, b];
    function show(i, animate) {
      var slides = api.slides;
      var incoming = imgs[1 - front], outgoing = imgs[front];
      var slide = slides[((i % slides.length) + slides.length) % slides.length];
      incoming.src = slide.file;
      incoming.className = animate ? "kb" : "on kb";
      if (animate) { incoming.offsetWidth; incoming.className = "on kb"; if (outgoing) outgoing.className = ""; }
      else if (outgoing && outgoing !== incoming) outgoing.className = "";
      front = 1 - front;
      idx = ((i % slides.length) + slides.length) % slides.length;
    }
    function schedule() {
      if (timer) clearTimeout(timer);
      timer = setTimeout(function () { timer = 0; show(idx + 1, true); schedule(); }, api.intervalMs || 12000);
    }
    show(0, false);
    schedule();
    return {
      box: box,
      destroy: function () {
        if (timer) clearTimeout(timer);
        if (box && box.parentNode) box.parentNode.removeChild(box);
      }
    };
  };

})(this);
