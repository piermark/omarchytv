// node webos/tests/city-search.test.js — typed city search via Open-Meteo + curated presets (issue #88).
const assert = require("assert");
const fs = require("fs"), path = require("path"), vm = require("vm");
const G = require("../app/geo.js");
assert.strictEqual(G.searchUrl(" Lisbon ", "pt"), "https://geocoding-api.open-meteo.com/v1/search?count=6&format=json&language=pt&name=Lisbon");
assert.ok(G.searchUrl("São Paulo", "xx-bad").includes("language=en&name=S%C3%A3o%20Paulo"));
const r = G.parseSearch({ results: [
  { name: "Lisbon", admin1: "Lisbon", country_code: "PT", latitude: 38.71667, longitude: -9.13333 },
  { name: "<x>", latitude: 999, longitude: 0 },
  { name: "Lisbon", admin1: "Maine", country_code: "US", latitude: "44.03", longitude: "-70.1" }] });
assert.deepStrictEqual(r, [
  { name: "Lisbon", label: "Lisbon, PT", lat: 38.717, lon: -9.133 },
  { name: "Lisbon", label: "Lisbon, Maine, US", lat: 44.03, lon: -70.1 }]);
assert.deepStrictEqual(G.parseSearch({}), []);
assert.deepStrictEqual(G.parseSearch(null), []);
const ctx = { window: {} }; vm.runInNewContext(fs.readFileSync(path.join(__dirname, "../app/data.js"), "utf8"), ctx);
const cities = ctx.window.OMARCHY_DATA.weatherCities;
assert.ok(cities.length >= 50, "curated list");
for (const n of ["Milano", "Alessandria", "Roma", "Berlin", "San Francisco", "Tokyo", "Sydney", "Singapore"]) assert.ok(cities.some((c) => c.name === n), n);
for (const c of cities) assert.ok(Math.abs(c.lat) <= 90 && Math.abs(c.lon) <= 180 && c.label.startsWith(c.name), c.name);
assert.strictEqual(G.filterPresets(cities, "").length, cities.length);
assert.strictEqual(G.filterPresets(cities, "tok").map((c) => c.name).join(), "Tokyo");
assert.ok(G.filterPresets(cities, ", jp").length === 1);
console.log("city-search ok");
