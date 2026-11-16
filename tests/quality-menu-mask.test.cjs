const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {create} = require('../app/src/main/assets/ja_tv_remote/quality-menu-mask.js');
const activation = require('../app/src/main/assets/ja_tv_remote/quality-activation.js');
function menu(value = '', priority = '') {
  const properties = new Map(value ? [['clip-path', [value, priority]]] : []);
  properties.set('opacity', ['1', '']);
  const attrs = new Map();
  return {getAttribute: k => attrs.get(k) || null, setAttribute: (k,v) => attrs.set(k,v), removeAttribute: k => attrs.delete(k), style: {
    getPropertyValue: k => properties.get(k)?.[0] || '',
    getPropertyPriority: k => properties.get(k)?.[1] || '',
    setProperty: (k, v, p = '') => properties.set(k, [v, p]),
    removeProperty: k => properties.delete(k)
  }};
}
function fixture() {
  const menus = [menu('circle(50%)', 'important')], attrs = new Map();
  let callback, disconnected = false;
  const doc = {querySelectorAll: () => menus, documentElement: {
    setAttribute: (k, v) => attrs.set(k, v), removeAttribute: k => attrs.delete(k)
  }};
  const mask = create({doc, observe: fn => {
    callback = fn; return {observe() {}, disconnect() {disconnected = true;}};
  }});
  return {mask, menus, attrs, mutate: () => callback(), get disconnected() {return disconnected;}};
}
(async () => {
  const f = fixture();
  assert.equal(f.attrs.size, 0, 'Manual menus remain unmasked outside automation');
  f.mask.set(true);
  assert.equal(f.menus[0].style.getPropertyValue('clip-path'), 'inset(100%)');
  assert.equal(f.menus[0].style.getPropertyValue('opacity'), '1', 'Discovery visibility remains intact');
  const replaced = menu(); f.menus.push(replaced); f.mutate();
  assert.equal(replaced.style.getPropertyValue('clip-path'), 'inset(100%)', 'New provider menu masked before paint');
  const cloned = menu('inset(100%)', 'important');
  cloned.setAttribute('data-ja-quality-original-clip', f.menus[0].getAttribute('data-ja-quality-original-clip'));
  f.menus.push(cloned); f.mutate();
  f.mask.set(true); // Must not overwrite the saved provider style.
  f.mask.set(false);
  assert.equal(f.attrs.size, 0); assert.equal(f.disconnected, true);
  assert.equal(f.menus[0].style.getPropertyValue('clip-path'), 'circle(50%)');
  assert.equal(f.menus[0].style.getPropertyPriority('clip-path'), 'important');
  assert.equal(replaced.style.getPropertyValue('clip-path'), '');
  assert.equal(cloned.style.getPropertyValue('clip-path'), 'circle(50%)', 'Cloned masks restore the provider original');
  f.mask.set(false); // Idempotent cleanup.

  let closed = 0, activated = 0, resumed;
  const q = activation.create({
    open: () => true, find: () => replaced, focus() {}, activate: () => activated++,
    close: () => closed++, mask: v => f.mask.set(v), valid: () => true, report() {},
    wait: () => new Promise(resolve => {resumed = resolve;})
  });
  const p = q.run('1080p');
  assert.equal(f.attrs.size, 1);
  q.cancel(); // Viewer opens controls while the automatic menu is waiting.
  assert.equal(f.attrs.size, 0, 'User takeover immediately restores visible controls');
  resumed(); await assert.rejects(p, /CANCELLED/);
  assert.equal(activated, 0); assert.equal(closed, 0, 'Delayed cleanup must not close viewer menu');

  const manifest = JSON.parse(fs.readFileSync(path.join(__dirname, '../app/src/main/assets/ja_tv_remote/manifest.json')));
  const script = manifest.content_scripts[0];
  assert.ok(script.css.includes('quality-menu-mask.css'));
  assert.ok(script.js.indexOf('quality-menu-mask.js') < script.js.indexOf('remote.js'));
  console.log('PASS: dynamic menu masking, original-style restoration, unchanged discovery visibility, immediate user takeover and extension registration');
})().catch(e => {console.error(e); process.exitCode = 1;});
