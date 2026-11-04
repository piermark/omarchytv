const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, '../app/index.html'), 'utf8');
function rule(selector, source = html) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = source.match(new RegExp(escaped + '\\s*\\{([^}]+)\\}'));
  assert.ok(match, 'Missing CSS rule: ' + selector);
  return Object.fromEntries(match[1].split(';').filter((s) => s.trim()).map((s) => {
    const colon = s.indexOf(':');
    return [s.slice(0, colon).trim(), s.slice(colon + 1).trim()];
  }));
}

const logo = '.bar-btn.logo-btn';
test('logo focus underline is moderately shorter but remains centered on the mark', () => {
  const padding = rule(logo).padding.split(/\s+/).map(parseFloat);
  const svgWidth = Number(html.match(/class="bar-btn logo-btn"[^>]*>\s*<svg\s+width="(\d+)"/)[1]);
  const underline = rule(logo + '.focused::after');
  const left = parseFloat(underline.left), right = parseFloat(underline.right);
  const buttonWidth = padding[3] + svgWidth + padding[1];
  const underlineWidth = buttonWidth - left - right;
  assert.equal(left, padding[3] - 6);
  assert.equal(right, padding[1] - 6);
  assert.equal(underlineWidth, 36);
  assert.equal(underlineWidth, svgWidth + 12);
  assert.ok(underlineWidth < buttonWidth);
  assert.equal(left + underlineWidth / 2, padding[3] + svgWidth / 2);
});

test('logo-specific underline keeps the accent and existing bottom-border position', () => {
  const border = parseFloat(rule('.bar-btn')['border-bottom']);
  const underline = rule(logo + '.focused::after');
  assert.equal(rule(logo + '.focused')['border-bottom-color'], 'transparent');
  assert.equal(underline.content, '""');
  assert.equal(underline.position, 'absolute');
  assert.equal(parseFloat(underline.height), border);
  assert.equal(parseFloat(underline.bottom), -border);
  assert.equal(underline.color, 'var(--t-accent)');
  assert.equal(underline.background, 'var(--t-focus, currentColor)');
  assert.equal(underline['pointer-events'], 'none');
});

test('logo hit area, workspace spacing and other button underlines are unchanged', () => {
  const box = rule(logo);
  assert.equal(box.padding, '0 14px 0 6px');
  assert.equal(box['margin-right'], '10px');
  assert.equal(box.width, undefined);
  assert.equal(box.transform, undefined);
  const focusColor = rule('.bar-btn.focused')['border-bottom-color'];
  assert.match(focusColor, /^var\(--t-(?:focus|accent)\)$/);
  assert.equal(rule('.bar-icon-btn.focused')['border-bottom-color'], focusColor);
});

test('legacy CSS rewrite retains a valid accent fallback and distinct authored focus color', () => {
  const css = html.match(/<style>([\s\S]*?)<\/style>/)[1];
  const style = { textContent: css.replace(/--t-focus\s*:[^;]+;/g, '') };
  const timers = [];
  function Declaration() {}
  Declaration.prototype.setProperty = function () {};
  const rootStyle = new Declaration();
  const body = { style: {}, querySelectorAll: () => [], getAttribute: () => null };
  const cssAPI = { supports: () => false };
  const context = {
    window: { CSS: cssAPI, addEventListener() {} }, CSS: cssAPI,
    document: { body, querySelector: () => style, querySelectorAll: () => [] },
    CSSStyleDeclaration: Declaration,
    MutationObserver: function () { this.observe = function () {}; },
    setTimeout: (fn) => timers.push(fn)
  };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../app/compat.js'), 'utf8'), context);
  let underline = rule(logo + '.focused::after', style.textContent);
  assert.equal(underline.background, 'currentColor');
  assert.equal(underline.color, css.match(/--t-accent\s*:\s*([^;]+);/)[1]);
  rootStyle.setProperty('--t-accent', '#78824b');
  rootStyle.setProperty('--t-focus', '#c9a554');
  while (timers.length) timers.shift()();
  underline = rule(logo + '.focused::after', style.textContent);
  assert.equal(underline.color, '#78824b');
  assert.equal(underline.background, '#c9a554');
});
