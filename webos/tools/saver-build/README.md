# Legacy screensaver bundle

The TV fallback bundle must run in webOS 3's Chromium 38. The original modern
IIFE used arrow functions and other syntax that this engine cannot parse; it
silently selected the emergency ASCII scramble instead of TerminalTextEffects.

Rebuild off-device with Node 22+:

```sh
npm ci --prefix webos/tools/saver-build --ignore-scripts
node webos/tools/saver-build/build.cjs
node webos/tools/saver-build/build.cjs --check
node --test webos/tests/saver-legacy.test.js
```

The lockfile pins esbuild, Babel and the ES5 parser. The build bundles the
unmodified, credited tte.js sources in the Android assets, lowers syntax to ES5,
and prepends the small missing-runtime-API shim. It checks the entire generated
bundle as ES5, including helpers. The existing webOS packaging script copies this
shared bundle; Android and other consumers can use it as well. No runtime npm
packages, network calls or services are added.

The runtime shim supplies Array.from (including Map/Set iterators), Object.assign,
String.padStart and Element.append only when absent. Promise, Map, Set, Symbol,
Math.imul and requestAnimationFrame are native in the target engine. The saver
page supplies its existing ResizeObserver/queueMicrotask compatibility helpers.

For real-engine acceptance, run the logo saver in Chromium 38, verify all 37
effects can construct, render and destroy, and check actual animation completion,
not just bundle load. Check repeated preview/exit and absence of ASCII fallback
or runtime errors. A modern-browser or Node-only pass is not TV acceptance.
