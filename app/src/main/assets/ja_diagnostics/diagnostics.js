// Read-only, opt-in diagnostics. Does not synthesize taps, navigate, or alter player controls.
(() => {
  'use strict';
  const tag = '[JA_PLAYER_DIAG]';
  const topFrame = window === window.top;
  let tapCount = 0, playEvents = 0, videoCount = 0, lastTouch = 'none';
  let panel = null;
  const log = (message) => console.info(tag, message);
  const visible = (el) => {
    if (!el) return false;
    const r = el.getBoundingClientRect(), st = getComputedStyle(el);
    return r.width > 0 && r.height > 0 && st.visibility !== 'hidden' && st.display !== 'none';
  };
  const describe = (el) => {
    if (!el) return 'none';
    const name = (el.tagName || '?').toLowerCase();
    const cls = typeof el.className === 'string' ? el.className.trim().split(/\s+/).slice(0, 2).join('.') : '';
    return (name + (el.id ? '#' + el.id : '') + (cls ? '.' + cls : '')).slice(0, 95);
  };
  const videos = new WeakSet();
  function scan() {
    const found = [...document.querySelectorAll('video')];
    videoCount = found.length;
    for (const video of found) {
      if (videos.has(video)) continue;
      videos.add(video);
      ['loadstart', 'waiting', 'playing', 'play', 'pause', 'error'].forEach(event => {
        video.addEventListener(event, () => {
          if (event === 'playing') playEvents++;
          log('video event=' + event + ' readyState=' + video.readyState + ' paused=' + video.paused + ' error=' + (video.error?.code ?? 'none'));
          render();
        });
      });
    }
    render();
  }
  function render() {
    if (!topFrame) return;
    if (!panel) {
      panel = document.createElement('div');
      panel.setAttribute('data-ja-diagnostic', 'true');
      panel.style.cssText = 'position:fixed;left:8px;bottom:8px;z-index:2147483647;max-width:80vw;background:rgba(0,0,0,.85);color:#fff;font:11px/1.5 monospace;padding:7px;border:1px solid #aaa;border-radius:5px;pointer-events:none;white-space:pre-wrap';
      (document.body || document.documentElement).appendChild(panel);
    }
    const frames = document.querySelectorAll('iframe');
    const center = document.elementFromPoint(innerWidth / 2, innerHeight / 2);
    panel.textContent = 'JA interaction diagnostics (read-only)\n' +
      'Page: ' + location.hostname + ' | ready: ' + document.readyState + '\n' +
      'Iframes: ' + frames.length + ' | Videos (this frame): ' + videoCount + '\n' +
      'Center element: ' + describe(center) + '\n' +
      'Touches: ' + tapCount + ' | Playing events: ' + playEvents + '\n' +
      'Last touch: ' + lastTouch;
  }
  log('script loaded host=' + location.hostname + ' topFrame=' + topFrame + ' readyState=' + document.readyState);
  document.addEventListener('pointerdown', e => {
    tapCount++;
    lastTouch = describe(e.target);
    log('pointerdown trusted=' + e.isTrusted + ' target=' + lastTouch);
    render();
  }, true);
  document.addEventListener('click', e => log('click trusted=' + e.isTrusted + ' target=' + describe(e.target)), true);
  scan();
  const interval = setInterval(scan, 1500);
  window.addEventListener('pagehide', () => clearInterval(interval), {once:true});
})();
