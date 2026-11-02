const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

test('absolute top-bar clock explicitly centers in both axes on legacy browsers', () => {
  const html = fs.readFileSync(path.join(__dirname, '../app/index.html'), 'utf8');
  const clock = html.match(/#bar-clock\s*\{([^}]+)\}/)[1];
  assert.match(clock, /position:\s*absolute;/);
  assert.match(clock, /top:\s*50%;/);
  assert.match(clock, /left:\s*50%;/);
  assert.match(clock, /transform:\s*translate\(-50%,\s*-50%\);/);
  assert.doesNotMatch(clock, /translateX\(/);
});
