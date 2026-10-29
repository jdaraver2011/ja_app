// JA diagnostics v1.1: read-only. Never synthesizes clicks or changes navigation.
(() => {
  'use strict';
  if (window.__jaInteractionDiagnostics) return;
  window.__jaInteractionDiagnostics = true;
  const TAG = '[JA_PLAYER_DIAG]';
  const isTop = window === window.top;
  const host = location.hostname || '(opaque frame)';
  const seen = new WeakSet();
  let touches = 0, plays = 0, lastTouch = 'none', lastEvent = 'none', panel;
  const log = (s) => console.info(TAG, 'host=' + host + ' top=' + isTop + ' ' + s);
  const describe = (el) => {
    if (!el) return 'none';
    let s = (el.tagName || '?').toLowerCase();
    if (el.id) s += '#' + el.id;
    if (typeof el.className === 'string' && el.className.trim())
      s += '.' + el.className.trim().split(/\s+/).slice(0, 2).join('.');
    return s.slice(0, 85);
  };
  function framesSummary() {
    const frames = [...document.querySelectorAll('iframe')];
    return frames.slice(0, 8).map((f, i) => {
      let frameHost = '(unknown)';
      try { frameHost = new URL(f.getAttribute('src') || 'about:blank', location.href).hostname || '(blank)'; }
      catch (_) {}
      let access = 'cross-origin/blocked';
      try { if (f.contentDocument) access = 'same-origin'; } catch (_) {}
      return (i + 1) + ':' + describe(f) + ' @' + frameHost + ' [' + access + ']';
    }).join('\n');
  }
  function render() {
    if (!isTop) return;
    if (!document.body) return;
    if (!panel || !panel.isConnected) {
      panel = document.createElement('div');
      panel.setAttribute('data-ja-diagnostic', 'true');
      panel.style.cssText = 'position:fixed;left:8px;bottom:8px;z-index:2147483647;max-width:92vw;max-height:35vh;overflow:hidden;background:rgba(0,0,0,.86);color:#fff;font:11px/1.4 monospace;padding:7px;border:1px solid #aaa;border-radius:5px;pointer-events:none;white-space:pre-wrap';
      document.body.appendChild(panel);
    }
    const center = document.elementFromPoint(innerWidth / 2, innerHeight / 2);
    panel.textContent = 'JA iframe diagnostics v1.1 (read-only)\n' +
      'Top: ' + host + ' | ready: ' + document.readyState + '\n' +
      'Videos here: ' + document.querySelectorAll('video').length +
      ' | playing: ' + plays + ' | touches: ' + touches + '\n' +
      'Center: ' + describe(center) + ' | last: ' + lastTouch + '\n' +
      'Last video event: ' + lastEvent + '\n' +
      'Frames (host / DOM access):\n' + (framesSummary() || '(none)') + '\n' +
      'Child-frame video events: Android logcat JA_PLAYER_DIAG';
  }
  function scan() {
    const videos = document.querySelectorAll('video');
    for (const video of videos) {
      if (seen.has(video)) continue;
      seen.add(video);
      log('video found readyState=' + video.readyState + ' paused=' + video.paused);
      for (const event of ['loadstart','loadedmetadata','waiting','play','playing','pause','error']) {
        video.addEventListener(event, () => {
          if (event === 'playing') plays++;
          lastEvent = event + ' ready=' + video.readyState + ' error=' + (video.error ? video.error.code : 'none');
          log('video ' + lastEvent);
          render();
        });
      }
    }
    render();
  }
  document.addEventListener('pointerdown', e => {
    touches++;
    lastTouch = describe(e.target);
    log('pointerdown trusted=' + e.isTrusted + ' target=' + lastTouch);
    render();
  }, true);
  document.addEventListener('click', e => log('click trusted=' + e.isTrusted + ' target=' + describe(e.target)), true);
  log('script loaded ready=' + document.readyState + ' urlScheme=' + location.protocol);
  scan();
  const interval = setInterval(scan, 1500);
  window.addEventListener('pagehide', () => clearInterval(interval), {once:true});
})();
