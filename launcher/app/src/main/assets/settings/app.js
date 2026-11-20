// OmarchyTV web settings (v2.22, #163). Served by the TV itself on :47800/settings/.
// The session is an HttpOnly cookie this script never sees; changes carry the per-session CSRF value.
'use strict';

const $ = (id) => document.getElementById(id);
const state = { me: null, schema: null, values: null, secrets: null, tab: null };

function el(tag, attrs, ...kids) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === undefined || v === null || v === false) continue;
    if (k === 'text') e.textContent = v;
    else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else e.setAttribute(k, v === true ? '' : String(v));
  }
  for (const k of kids) if (k !== null && k !== undefined) e.append(k);
  return e;
}

async function call(method, path, body) {
  const h = {};
  if (body !== undefined) h['Content-Type'] = 'application/json';
  if (method !== 'GET' && state.me) h['X-Omarchy-CSRF'] = state.me.csrf;
  const r = await fetch(path, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body),
    credentials: 'same-origin', cache: 'no-store' });
  let j = {};
  try { j = await r.json(); } catch (_) { /* empty body */ }
  return { status: r.status, json: j };
}

function reason(res) {
  const e = res.json && res.json.error;
  if (e && typeof e === 'object') return e.message || e.code || 'error ' + res.status;
  return (res.json && res.json.message) || (typeof e === 'string' ? e : '') || 'error ' + res.status;
}

// ---- theme: the TV's own colours ----------------------------------------------------------------------
async function theme() {
  const r = await call('GET', '/v1/settings/theme');
  if (r.status !== 200 || !r.json.background) return;
  const t = r.json, c = t.colors || {}, s = document.documentElement.style;
  const hex = (v) => (typeof v === 'string' && /^#[0-9a-fA-F]{6}$/.test(v) ? v : null);
  const set = (k, v) => { if (hex(v)) s.setProperty(k, v); };
  set('--bg', t.background); set('--fg', t.foreground); set('--bright', t.foreground);
  set('--accent', t.accent); set('--accent2', t.accent2);
  set('--muted', c.color8); set('--red', c.color1); set('--green', c.color2);
}

// ---- connect -----------------------------------------------------------------------------------------
function guessDevice() {
  const ua = navigator.userAgent;
  const os = /Android/.test(ua) ? 'Android' : /iPhone|iPad/.test(ua) ? 'iPhone' : /Mac OS X/.test(ua) ? 'Mac'
    : /Windows/.test(ua) ? 'Windows' : /Linux/.test(ua) ? 'Linux' : 'browser';
  const br = /Firefox\//.test(ua) ? 'Firefox' : /Edg\//.test(ua) ? 'Edge' : /Chrome\//.test(ua) ? 'Chrome'
    : /Safari\//.test(ua) ? 'Safari' : '';
  return (br ? br + ' on ' : '') + os;
}

function showConnect() {
  $('connect').hidden = false;
  $('tabs').hidden = true;
  $('panes').replaceChildren();
  const d = $('device');
  if (!d.value) d.value = guessDevice();
  $('ask').onclick = ask;
}

async function ask() {
  const msg = $('connect-msg');
  const code = new URLSearchParams(location.search).get('code') || '';
  $('ask').disabled = true;
  msg.className = 'msg';
  msg.textContent = 'Asking the TV…';
  const r = await call('POST', '/v1/settings/session', { device: $('device').value.trim(), code });
  if (code) history.replaceState(null, '', location.pathname);   // one use: do not resend it
  if (r.status !== 200) {
    msg.className = 'msg bad';
    msg.textContent = reason(r);
    $('ask').disabled = false;
    return;
  }
  msg.textContent = 'Answer on the TV with the remote (2 minutes).';
  for (;;) {
    const p = await call('GET', '/v1/settings/session/' + encodeURIComponent(r.json.id));
    if (p.status !== 200) { msg.className = 'msg bad'; msg.textContent = reason(p); break; }
    if (p.json.state === 'pending') { await new Promise((ok) => setTimeout(ok, 300)); continue; }   // long poll
    if (p.json.state === 'approved') { msg.className = 'msg ok'; msg.textContent = 'Allowed.'; await start(); return; }
    msg.className = 'msg bad';
    msg.textContent = p.json.state === 'denied' ? 'Denied on the TV.' : 'No answer in time. Try again.';
    break;
  }
  $('ask').disabled = false;
}

// ---- settings ----------------------------------------------------------------------------------------
const readOnly = () => !state.me || state.me.scope !== 'control';

async function save(key, value, row) {
  const err = row.querySelector('.err');
  err.textContent = '';
  let r = await call('PATCH', '/v1/settings', { [key]: value });
  if (r.status === 403 && r.json.error === 'csrf') {   // the TV restarted: a new CSRF value, same session
    const me = await call('GET', '/v1/settings/me');
    if (me.status === 200) { state.me = me.json; r = await call('PATCH', '/v1/settings', { [key]: value }); }
  }
  if (r.status !== 200) {
    const why = reason(r);
    await loadValues();
    await render();   // back to the TV's value, then the reason under it
    const again = document.querySelector('.row[data-key="' + CSS.escape(key) + '"] .err');
    if (again) again.textContent = why;
    return false;
  }
  await loadValues();
  if (key === 'theme') await theme();
  return true;
}

function control(f, row) {
  const v = state.values[f.key];
  const dis = readOnly();
  const id = 'f-' + f.key.replace(/[^a-z0-9]/gi, '-');
  switch (f.type) {
    case 'bool':
      return el('input', { type: 'checkbox', id, checked: v === true, disabled: dis,
        onchange: (e) => save(f.key, e.target.checked, row) });
    case 'enum': {
      const s = el('select', { id, disabled: dis, onchange: (e) => save(f.key, e.target.value, row) });
      for (const o of f.values) s.append(el('option', { value: o, selected: o === v, text: o }));
      return s;
    }
    case 'int':
    case 'number':
      return el('input', { type: 'number', id, value: v === null ? '' : v, min: f.min, max: f.max,
        step: f.type === 'int' ? 1 : 'any', disabled: dis,
        onchange: (e) => {
          const t = e.target.value.trim();
          save(f.key, t === '' ? (f.type === 'number' ? null : 0) : Number(t), row);
        } });
    case 'secret': {
      const set = state.secrets[f.key] && state.secrets[f.key].set;
      const i = el('input', { type: 'password', id, autocomplete: 'off', maxlength: f.maxLength, disabled: dis,
        placeholder: set ? 'set (hidden) · type to replace' : 'not set' });
      const b = el('button', { type: 'button', disabled: dis, text: 'Save',
        onclick: async () => { if (await save(f.key, i.value, row)) { i.value = ''; render(); } } });
      return el('div', { class: 'inline' }, i, b);
    }
    default:
      return el('input', { type: f.type === 'url' ? 'url' : 'text', id, value: v === null ? '' : v,
        maxlength: f.maxLength, disabled: dis, spellcheck: 'false',
        onchange: (e) => save(f.key, e.target.value, row) });
  }
}

function sectionPane(sec) {
  const card = el('section', { class: 'card' }, el('h2', { text: sec.label }));
  for (const f of sec.fields) {
    const row = el('div', { class: 'row', 'data-key': f.key });
    const label = el('label', { for: 'f-' + f.key.replace(/[^a-z0-9]/gi, '-'), text: f.label });
    row.append(label, control(f, row), el('div', { class: 'err', role: 'alert' }));
    card.append(row);
  }
  if (sec.id === 'homeassistant') {
    card.append(el('p', { class: 'dim', text: 'The token stays on the TV and is never shown again. A new address without a new token forgets the old one.' }));
  }
  return card;
}

async function accessPane() {
  const card = el('section', { class: 'card' }, el('h2', { text: 'This browser' }));
  card.append(el('p', { text: (state.me.device || 'this browser') + ' · ' + state.me.scope }));
  card.append(el('button', { type: 'button', class: 'danger', text: 'Sign out',
    onclick: async () => { await call('DELETE', '/v1/settings/session'); state.me = null; showConnect(); } }));
  const out = el('div', {}, card);
  if (readOnly()) return out;
  const tc = el('section', { class: 'card' }, el('h2', { text: 'App tokens' }));
  const list = el('ul', { class: 'list' });
  const msg = el('p', { class: 'msg', role: 'status' });
  const r = await call('GET', '/v1/settings/tokens');
  for (const t of (r.json.tokens || [])) {
    const self = t.id === r.json.self;
    list.append(el('li', {}, el('span', { text: t.name + ' · ' + t.scope + (self ? ' (this browser)' : '') }),
      self ? null : el('button', { type: 'button', class: 'danger', text: 'Revoke',
        onclick: async () => { const d = await call('DELETE', '/v1/settings/tokens/' + t.id); if (d.status === 200) render(); else msg.textContent = reason(d); } })));
  }
  const name = el('input', { maxlength: 40, placeholder: 'name, e.g. hermes', autocomplete: 'off' });
  const scope = el('select', {}, ...['read', 'notify', 'control'].map((s) => el('option', { value: s, text: s, selected: s === 'notify' })));
  const make = el('button', { type: 'button', text: 'New token', onclick: async () => {
    const n = await call('POST', '/v1/settings/tokens', { name: name.value.trim(), scope: scope.value });
    if (n.status !== 200) { msg.className = 'msg bad'; msg.textContent = reason(n); return; }
    msg.className = 'msg';
    msg.replaceChildren('Copy it now, it is shown once: ', el('span', { class: 'token', text: n.json.token }));
    name.value = '';
  } });
  tc.append(list, el('div', { class: 'inline' }, name, scope, make), msg);
  out.append(tc);
  return out;
}

async function render() {
  const tabs = $('tabs'), panes = $('panes');
  tabs.hidden = false;
  tabs.replaceChildren();
  const all = state.schema.sections.map((s) => ({ id: s.id, label: s.label })).concat([{ id: 'access', label: 'Access' }]);
  if (!all.some((t) => t.id === state.tab)) state.tab = all[0].id;
  for (const t of all) {
    tabs.append(el('button', { type: 'button', role: 'tab', 'aria-selected': t.id === state.tab ? 'true' : 'false', text: t.label,
      onclick: () => { state.tab = t.id; render(); } }));
  }
  const kids = [];
  if (readOnly()) kids.push(el('div', { class: 'banner', text: 'Read-only: approve this browser with "Allow" on the TV to change settings.' }));
  if (state.tab === 'access') kids.push(await accessPane());
  else kids.push(sectionPane(state.schema.sections.find((s) => s.id === state.tab)));
  panes.replaceChildren(...kids);
}

async function loadValues() {
  const v = await call('GET', '/v1/settings');
  if (v.status === 401) { state.me = null; showConnect(); throw new Error('signed out'); }
  state.values = v.json.values || {};
  state.secrets = v.json.secrets || {};
}

async function start() {
  const me = await call('GET', '/v1/settings/me');
  if (me.status !== 200) { showConnect(); return; }
  state.me = me.json;
  $('connect').hidden = true;
  $('who').textContent = (state.me.device || '') + ' · ' + state.me.scope;
  await theme();
  const s = await call('GET', '/v1/settings/schema');
  if (s.status !== 200) { $('panes').replaceChildren(el('p', { class: 'bad', text: reason(s) })); return; }
  state.schema = s.json;
  await loadValues();
  await render();
}

document.addEventListener('visibilitychange', async () => {
  if (document.visibilityState !== 'visible' || !state.me || !state.schema) return;
  try { await loadValues(); if (state.tab !== 'access') await render(); } catch (_) { /* signed out */ }
});

start().catch((e) => { $('panes').replaceChildren(el('p', { class: 'bad', text: String(e.message || e) })); });
