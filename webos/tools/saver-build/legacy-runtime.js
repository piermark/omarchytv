/* Scoped to the saver iframe. Chromium 38 has Promise/Map/Set, but lacks these APIs. */
(function () {
  'use strict';
  if (!Object.assign) Object.assign = function (target) {
    if (target == null) throw new TypeError('Object.assign target is null');
    var out = Object(target);
    for (var i = 1; i < arguments.length; i++) {
      if (arguments[i] == null) continue;
      var source = Object(arguments[i]), keys = Object.keys(source);
      for (var k = 0; k < keys.length; k++) out[keys[k]] = source[keys[k]];
    }
    return out;
  };
  if (!Array.from) Array.from = function (value, mapper, receiver) {
    if (value == null) throw new TypeError('Array.from input is null');
    var out = [], iterator, step, i = 0;
    if (typeof Symbol !== 'undefined' && value[Symbol.iterator]) {
      iterator = value[Symbol.iterator]();
      while (!(step = iterator.next()).done) {
        out.push(mapper ? mapper.call(receiver, step.value, i++) : step.value);
      }
    } else {
      for (; i < value.length; i++) out.push(mapper ? mapper.call(receiver, value[i], i) : value[i]);
    }
    return out;
  };
  if (!String.prototype.padStart) String.prototype.padStart = function (length, fill) {
    var text = String(this), prefix = '', pad = fill === undefined ? ' ' : String(fill);
    if (!pad) return text;
    while (prefix.length < length - text.length) prefix += pad;
    return prefix.slice(0, Math.max(0, length - text.length)) + text;
  };
  if (typeof Element !== 'undefined' && !Element.prototype.append) {
    Element.prototype.append = function () {
      for (var i = 0; i < arguments.length; i++) {
        var node = arguments[i];
        this.appendChild(typeof node === 'string' ? document.createTextNode(node) : node);
      }
    };
  }
}());
