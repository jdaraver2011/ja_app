'use strict';
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const code = fs.readFileSync('app/src/main/assets/ja_autoplay/movy-landing.js', 'utf8');
function fixture(options = {}) {
  let clicks = 0, tick, cleared = false;
  const events = {}, logs = [];
  const button = {
    isConnected: true, disabled: false,
    getAttribute: () => null, closest: () => null,
    getBoundingClientRect: () => ({width: 300, height: 50}),
    click() { clicks++; if (options.dismiss) button.isConnected = false; }
  };
  const window = {}; window.top = options.iframe ? {} : window;
  const context = {
    window, location: {hostname: options.host || 'www.movy.sx', pathname: options.path || '/movie/969681'},
    document: {querySelectorAll(selector) {
      assert.equal(selector, '[class*="HomeBanner-"][class*="__ctaRow"] > button.mv-btn--primary');
      return options.absent ? [] : options.ambiguous ? [button, {...button}] : [button];
    }},
    getComputedStyle: () => ({display: 'block', visibility: options.hidden ? 'hidden' : 'visible', pointerEvents: 'auto'}),
    setInterval(fn) { tick = fn; return 1; },
    clearInterval() { cleared = true; },
    addEventListener(name, fn) { events[name] = fn; },
    removeEventListener(name) { delete events[name]; },
    console: {info(...args) { logs.push(args.join(' ')); }}
  };
  vm.runInNewContext(code, context);
  return {button, context, events, logs, clicks: () => clicks,
    run(n) { for (let i = 0; i < n; i++) if (!cleared && tick) tick(); },
    cleared: () => cleared};
}
for (const path of ['/movie/969681', '/movie/1368337', '/tv/254630/1/2', '/tv/254630/0/1']) {
  const f = fixture({path, dismiss: true}); assert.equal(f.clicks(), 1);
  f.run(5); assert.equal(f.clicks(), 1); assert(f.cleared());
}
for (const opts of [{host:'vidsrc.sh'}, {host:'fake.movy.sx'}, {iframe:true}, {path:'/tv/254630'}, {path:'/movie/0'}, {path:'/search'}]) {
  assert.equal(fixture(opts).clicks(), 0);
}
for (const opts of [{hidden:true}, {absent:true}, {ambiguous:true}]) {
  const f=fixture(opts); f.run(45); assert.equal(f.clicks(),0); assert(f.cleared());
}
const retry=fixture(); retry.run(45); assert.equal(retry.clicks(),3); assert(retry.cleared());
const manual=fixture(); manual.events.keydown({isTrusted:true}); manual.run(45); assert.equal(manual.clicks(),1);
const synthetic=fixture(); synthetic.events.pointerdown({isTrusted:false}); synthetic.run(45); assert.equal(synthetic.clicks(),3);
const changed=fixture(); changed.context.location.pathname='/movie/550'; changed.run(45); assert.equal(changed.clicks(),1);
const hidden=fixture(); hidden.events.pagehide(); hidden.run(45); assert.equal(hidden.clicks(),1);
const duplicate=fixture(); vm.runInNewContext(code,duplicate.context); assert.equal(duplicate.clicks(),1);
// No video lookup: an already-playing hero preview never suppresses landing activation.
assert(!code.includes("querySelectorAll('video')"));
console.log('PASS: Movy title/episode landing activation, bounded retries, scope and manual cancellation');
