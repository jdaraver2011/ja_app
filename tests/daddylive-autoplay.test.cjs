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
  delayed = false, secondPrompt = false, outsidePlayer = false } = {}) {
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
    matches: () => known,
    click: () => { clicks++; }
  };
  const second = { ...button, contains: node => node === second };
  const visiblePrompt = () => secondPrompt && clicks > 0 ? second : button;
  const video = {
    isConnected: true, paused: !playing, ended: false, currentSrc: 'https://example.invalid/live',
    readyState: playing ? 3 : 1, getBoundingClientRect: () => rect, closest: () => null,
    play: () => { plays++; }
  };
  const player = {
    querySelectorAll(selector) {
      if (!mounted || outsidePlayer) return [];
      return selector.startsWith('.play-wrapper') ? (known ? [button] : []) : (known ? [] : [button]);
    }
  };
  const window = {};
  window.top = top ? window : {};
  vm.runInNewContext(code, {
    window, location: { protocol: 'https:', hostname: host, pathname: path },
    document: {
      querySelector: selector => selector === '#player' ? player : null,
      querySelectorAll: selector => selector === 'video' ? [video] :
        mounted && (!known || (secondPrompt && clicks > 0)) ? [visiblePrompt()] : [],
      elementFromPoint: () => covered ? {} : visiblePrompt()
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

assert.deepEqual(run(), { clicks: 1, plays: 1 });
assert.deepEqual(run({ known: true, text: '' }), { clicks: 1, plays: 1 });
assert.equal(run({ outsidePlayer: true }).clicks, 1);
assert.equal(run({ secondPrompt: true, outsidePlayer: true }).clicks, 2);
assert.deepEqual(run({ delayed: true }), { clicks: 1, plays: 1 });
assert.deepEqual(run({ covered: true }), { clicks: 0, plays: 1 });
assert.deepEqual(run({ linked: true }), { clicks: 0, plays: 1 });
assert.deepEqual(run({ playing: true }), { clicks: 0, plays: 0 });
for (const scope of [{ host: 'example.invalid' }, { path: '/elsewhere' }, { top: true }]) {
  assert.equal(run(scope).clicks, 0);
}
assert.equal(run({ text: 'Skip ad' }).clicks, 0);
assert.equal(run({ text: 'Loading' }).clicks, 0);
console.log('PASS: DaddyLive border scope, delayed and second prompts, loading and overlay rejection');

const remote = fs.readFileSync('app/src/main/assets/ja_tv_remote/remote.js', 'utf8');
const audioStart = remote.indexOf('  function startupRequestUnmute(');
const audioEnd = remote.indexOf('  function finishStartupPreferences', audioStart);
assert(audioStart >= 0 && audioEnd > audioStart);
const video = { paused: true, readyState: 1, currentTime: 0, muted: true, volume: 0 };
let onProgress;
const reports = [];
vm.runInNewContext(remote.slice(audioStart, audioEnd), {
  window: { top: {} },
  location: { origin: 'https://dembed.st', pathname: '/premiumtv/daddy.php' },
  currentVideo: () => video,
  audioChecked: false,
  startupLog: event => reports.push(event),
  setTimeout() {},
  document: {
    addEventListener(type, callback, capture) {
      assert.equal(type, 'timeupdate');
      assert.equal(capture, true);
      onProgress = callback;
    },
    removeEventListener(type, callback, capture) {
      assert.equal(type, 'timeupdate');
      assert.equal(callback, onProgress);
      assert.equal(capture, true);
      onProgress = null;
    }
  }
});
onProgress({ target: video });
assert.equal(video.muted, true);
Object.assign(video, { paused: false, readyState: 3, currentTime: 1 });
onProgress({ target: video });
assert.equal(video.muted, false);
assert.equal(video.volume, 1);
assert.equal(onProgress, null);
assert.deepEqual(reports, ['DEFAULT_AUDIO_UNMUTE_REQUESTED']);
console.log('PASS: DaddyLive unmutes on first playback progress after a paused startup');
