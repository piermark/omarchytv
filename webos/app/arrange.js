// OmarchyTV v2.21 (#5) Arrange panels: the saved order of the Home widgets. Shared by app.js and node tests. MIT.
(function (root) {
  /** The saved order, cleaned: known ids only, no repeats, any id missing from it appended in its default place. */
  function order(saved, all) {
    var out = [], i;
    if (saved && saved.length) for (i = 0; i < saved.length; i++) if (all.indexOf(saved[i]) >= 0 && out.indexOf(saved[i]) < 0) out.push(saved[i]);
    for (i = 0; i < all.length; i++) if (out.indexOf(all[i]) < 0) out.push(all[i]);
    return out;
  }
  /** Move item i one place up; the first one goes to the bottom. Returns a new array. */
  function up(list, i) {
    var a = list.slice();
    if (i <= 0) { a.push(a.shift()); return a; }
    var t = a[i - 1]; a[i - 1] = a[i]; a[i] = t;
    return a;
  }
  var api = { order: order, up: up };
  root.OmarchyArrange = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})(typeof window !== "undefined" ? window : this);
