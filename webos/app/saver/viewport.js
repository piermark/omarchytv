/* Give tte.js a viewport-sized surface without changing its text grid or cell size. */
(function (root, factory) {
  var api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.OmarchySaverViewport = api;
}(this, function () {
  'use strict';

  function metrics(renderer, view) {
    var box = renderer.source.getBoundingClientRect();
    var doc = view.document && view.document.documentElement;
    return {
      width: Math.max(1, view.innerWidth || (doc && doc.clientWidth) || box.width),
      height: Math.max(1, view.innerHeight || (doc && doc.clientHeight) || box.height),
      ratio: Math.max(1, view.devicePixelRatio || 1),
      left: box.left, top: box.top, textWidth: box.width, textHeight: box.height
    };
  }

  function changed(a, b) {
    if (!a) return true;
    return Object.keys(b).some(function (key) { return a[key] !== b[key]; });
  }

  function attach(effect, view) {
    if (effect.__omarchyViewport) return effect;
    view = view || window;
    var renderer = effect.renderer;
    if (!renderer || !renderer.source) throw new Error('Fullscreen saver requires a text source');
    var resize = renderer.resize, draw = renderer.draw, destroy = effect.destroy;
    var canvas = renderer.canvas, current;
    // A fixed child remains owned by tte.js's wrapper/destroy lifecycle, but no
    // longer inherits that wrapper's text-sized positioning or clipping surface.
    canvas.style.position = 'fixed';
    canvas.style.top = '0';
    canvas.style.left = '0';

    renderer.resize = function () {
      // Keep tte.js's logical dimensions and cell metrics tied to the text.
      resize.call(this);
      current = metrics(this, view);
      canvas.width = Math.round(current.width * current.ratio);
      canvas.height = Math.round(current.height * current.ratio);
      canvas.style.width = current.width + 'px';
      canvas.style.height = current.height + 'px';
    };

    renderer.draw = function (state) {
      // Font loading, the clock/status layout and viewport/DPR changes may move
      // or resize the source even on engines without a real ResizeObserver.
      if (changed(current, metrics(this, view))) this.resize();
      var ctx = this.context, ratio = current.ratio;
      ctx.save();
      try {
        ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
        ctx.globalAlpha = 1;
        ctx.shadowBlur = 0;
        ctx.clearRect(0, 0, current.width, current.height);
        if (this.options.background) {
          ctx.fillStyle = this.options.background;
          ctx.fillRect(0, 0, current.width, current.height);
        }
        ctx.setTransform(ratio, 0, 0, ratio, current.left * ratio, current.top * ratio);
        // Characters, beams and particles share the same translated grid. Do not
        // clip to the text rectangle: the bitmap now extends to the screen edges.
        draw.call(this, state);
      } finally {
        ctx.restore();
      }
    };

    function onResize() { if (!effect.destroyed) effect.resize(); }
    view.addEventListener('resize', onResize);
    effect.destroy = function () {
      view.removeEventListener('resize', onResize);
      return destroy.apply(this, arguments);
    };
    effect.__omarchyViewport = true;
    renderer.resize();
    return effect;
  }

  return { attach: attach };
}));
