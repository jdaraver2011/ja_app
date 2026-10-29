// JA Experimental Auto-Start v1.1 — installed only when Settings enables it.
// Executes in each HTTP(S) frame, including cross-origin player iframes.
// No coordinate clicks, links, overlays, or repeated synthetic clicks.
(() => {
  'use strict';
  if (window.__jaAutoStart11) return;
  window.__jaAutoStart11 = true;
  const label = '[JA_AUTOSTART]';
  const frame = window === window.top ? 'top' : 'iframe';
  const selectors = [
    '.vjs-big-play-button', '.jw-display-icon-container .jw-icon-display',
    '.plyr__control--overlaid', '.fp-play',
    'button[aria-label="Play"]', 'button[title="Play"]',
    'button[aria-label="Start playback"]', '.play-button[role="button"]'
  ];
  let tries = 0, activated = false, finished = false;
  const maxTries = 15;
  const log = (...args) => console.info(label, frame, location.hostname, ...args);
  function visible(el) {
    if (!el || !el.isConnected || el.closest('a,form,[href]')) return false;
    const r = el.getBoundingClientRect();
    const s = getComputedStyle(el);
    return r.width >= 12 && r.height >= 12 && s.display !== 'none' &&
      s.visibility !== 'hidden' && s.pointerEvents !== 'none';
  }
  function stop(reason) {
    if (finished) return;
    finished = true;
    clearInterval(timer);
    log(reason);
  }
  function tick() {
    if (finished) return;
    tries++;
    const videos = [...document.querySelectorAll('video')];
    if (videos.some(v => !v.paused && v.readyState >= 2)) return stop('Playback detected');
    // First try a real HTML5 video in this frame.
    for (const video of videos) {
      if (!visible(video) || !video.paused || video.ended) continue;
      try {
        const promise = video.play();
        if (promise && typeof promise.then === 'function') {
          promise.then(() => stop('video.play succeeded'))
            .catch(e => log('video.play rejected:', e.name || 'unknown'));
        }
      } catch (e) { log('video.play error:', e.name || 'unknown'); }
      break;
    }
    // Only recognized video-player controls, once per frame.
    if (!activated) {
      for (const selector of selectors) {
        const control = document.querySelector(selector);
        if (!visible(control)) continue;
        activated = true;
        log('Trying player control:', selector);
        try { control.click(); } catch (e) { log('Control click failed:', e.name || 'unknown'); }
        break;
      }
    }
    if (tries >= maxTries || activated) stop(activated ? 'Control attempt complete; manual Play remains available' : 'No recognized player control');
  }
  log('Frame script loaded');
  const timer = setInterval(tick, 1200);
  tick();
  addEventListener('pagehide', () => stop('Page hidden'), {once: true});
})();
