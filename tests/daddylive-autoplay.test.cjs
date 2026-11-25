const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const root = 'app/src/main/assets/ja_autoplay/';
const code = fs.readFileSync(root + 'autoplay.js', 'utf8');
const manifest = JSON.parse(fs.readFileSync(root + 'manifest.json', 'utf8'));
const cssScript = manifest.content_scripts.find(entry => entry.css);
assert.deepEqual(cssScript.matches, ['https://thedaddy.li/stream/stream-*.php']);
assert.equal(cssScript.run_at, 'document_start');
assert.match(fs.readFileSync(root + cssScript.css[0], 'utf8'), /background: +#000/);

function run({ host = 'dembed.st', path = '/premiumtv/daddy.php', top = false,
  known = false, text = 'Tap to Play', covered = false, playing = false, linked = false,
  delayed = false } = {}) {
  let clicks = 0;
  let plays = 0;
  let stopped = false;
  let tick;
  let mounted = !delayed;
  const rect = { left: 0, top: 0, width: 50, height: 50 };
  const button = {
    isConnected: true, disabled: false, textContent: text,
    getAttribute: () => null, getBoundingClientRect: () => rect,
    closest: () => linked ? {} : null, contains: node => node === button,
    click: () => { clicks++; }
  };
  const video = {
    isConnected: true, paused: !playing, ended: false, currentSrc: 'https://example.invalid/live',
    readyState: playing ? 3 : 1, getBoundingClientRect: () => rect, closest: () => null,
    play: () => { plays++; }
  };
  const player = {
    querySelectorAll(selector) {
      if (!mounted) return [];
      return selector.startsWith('.play-wrapper') ? (known ? [button] : []) : (known ? [] : [button]);
    }
  };
  const window = {};
  window.top = top ? window : {};
  vm.runInNewContext(code, {
    window, location: { protocol: 'https:', hostname: host, pathname: path },
    document: {
      querySelector: selector => selector === '#player' ? player : null,
      querySelectorAll: () => [video],
      elementFromPoint: () => covered ? {} : button
    },
    getComputedStyle: () => ({ display: 'block', visibility: 'visible', pointerEvents: 'auto' }),
    console: { info() {} },
    setInterval(callback) { tick = callback; return 1; },
    clearInterval() { stopped = true; },
    addEventListener() {}
  });
  mounted = true;
  for (let i = 0; i < 20 && !stopped; i++) tick();
  return { clicks, plays };
}

assert.deepEqual(run(), { clicks: 1, plays: 0 });
assert.deepEqual(run({ known: true, text: '' }), { clicks: 1, plays: 0 });
assert.deepEqual(run({ delayed: true }), { clicks: 1, plays: 1 });
assert.deepEqual(run({ covered: true }), { clicks: 0, plays: 1 });
assert.deepEqual(run({ linked: true }), { clicks: 0, plays: 1 });
assert.deepEqual(run({ playing: true }), { clicks: 0, plays: 0 });
for (const scope of [{ host: 'example.invalid' }, { path: '/elsewhere' }, { top: true }]) {
  assert.equal(run(scope).clicks, 0);
}
assert.equal(run({ text: 'Skip ad' }).clicks, 0);
console.log('PASS: DaddyLive border scope, delayed play controls, overlay rejection and single click');
