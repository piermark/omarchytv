/* Opt-in terminal block-art adapter for tte.js; ordinary glyphs keep its renderer. */
(function (root, factory) {
  var api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.OmarchyBlockGlyphs = api;
}(this, function () {
  'use strict';
// Terminal block art is cell geometry, not font ink: many monospace fonts inset
// block glyphs, leaving visible seams when Canvas fillText draws each cell.
function rectangles(character) {
  var code = character.charCodeAt(0);
  if (code === 0x2580) return [[0, 0, 1, 0.5]];
  if (code >= 0x2581 && code <= 0x2588) {
    var height = (code - 0x2580) / 8;
    return [[0, 1 - height, 1, height]];
  }
  if (code >= 0x2589 && code <= 0x258f) return [[0, 0, (0x2590 - code) / 8, 1]];
  if (code === 0x2590) return [[0.5, 0, 0.5, 1]];
  if (code === 0x2594) return [[0, 0, 1, 0.125]];
  if (code === 0x2595) return [[0.875, 0, 0.125, 1]];
  var quadrants = {
    '\u2596': [2], '\u2597': [3], '\u2598': [0], '\u2599': [0, 2, 3],
    '\u259a': [0, 3], '\u259b': [0, 1, 2], '\u259c': [0, 1, 3],
    '\u259d': [1], '\u259e': [1, 2], '\u259f': [1, 2, 3]
  };
  var parts = quadrants[character];
  return parts ? parts.map(function (q) { return [q % 2 / 2, Math.floor(q / 2) / 2, 0.5, 0.5]; }) : null;
}
function drawBlock(renderer, cell) {
  var parts = rectangles(cell.character);
  if (!parts) return false;
  var ctx = renderer.context, cw = renderer.cellWidth, ch = renderer.cellHeight;
  ctx.globalAlpha = cell.alpha == null ? 1 : cell.alpha;
  ctx.fillStyle = cell.color == null ? '#ffffff' : cell.color;
  ctx.shadowColor = ctx.fillStyle;
  ctx.shadowBlur = cell.glow == null ? 0 : cell.glow;
  parts.forEach(function (r) {
    var left = Math.floor((cell.column + r[0]) * cw);
    var top = Math.floor((cell.row + r[1]) * ch);
    var right = Math.ceil((cell.column + r[0] + r[2]) * cw);
    var bottom = Math.ceil((cell.row + r[1] + r[3]) * ch);
    ctx.fillRect(left, top, right - left, bottom - top);
  });
  return true;
}
function install(renderer) {
  var original = renderer.drawCharacter;
  renderer.drawCharacter = function (cell) {
    if (!drawBlock(this, cell)) original.call(this, cell);
  };
}
function wrap(BaseTextEffect) {
  function TextEffect(target, options) {
    var effect = new BaseTextEffect(target, options);
    install(effect.renderer);
    return effect;
  }
  TextEffect.prototype = BaseTextEffect.prototype;
  return TextEffect;
}
return { rectangles: rectangles, drawBlock: drawBlock, wrap: wrap };

}));
