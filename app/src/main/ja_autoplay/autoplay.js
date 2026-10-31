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

// Low-frequency frame-drop sampling; console logging only, no overlay or reflows.
(() => {
  if (window.__jaFrameQuality) return;
  window.__jaFrameQuality = true;
  let samples = 0;
  const timer = setInterval(() => {
    const v = [...document.querySelectorAll('video')].find(x => !x.paused && x.readyState >= 2);
    if (!v || typeof v.getVideoPlaybackQuality !== 'function') return;
    const q = v.getVideoPlaybackQuality();
    console.info('[JA_PERF]', location.hostname,
      'resolution=' + v.videoWidth + 'x' + v.videoHeight,
      'decoded=' + q.totalVideoFrames, 'dropped=' + q.droppedVideoFrames);
    if (++samples >= 10) clearInterval(timer);
  }, 30000);
  addEventListener('pagehide', () => clearInterval(timer), {once:true});
})();

// Google TV: use the webpage's own controls, not a native Android overlay.
// GeckoView receives real remote key events. This helper only intervenes when
// recognizable, visible controls exist in the current frame.
(() => {
  'use strict';
  if (window.__jaTvWebFocus) return;
  window.__jaTvWebFocus = true;
  const selectors = [
    '.vjs-control-bar button', '.vjs-control-bar [tabindex]',
    '.jw-controlbar button', '.jw-controlbar [tabindex]',
    '.plyr__controls button', '.plyr__controls input',
    '.fp-controls button', '.fp-controls [tabindex]',
    'video[controls]',
    'button[aria-label="Play"]', 'button[aria-label="Pause"]',
    'button[aria-label="Fullscreen"]', 'button[aria-label="Full screen"]'
  ];
  let current = -1;
  function controls() {
    const nodes = [...document.querySelectorAll(selectors.join(','))];
    return [...new Set(nodes)].filter(el => {
      if (!el.isConnected || el.disabled) return false;
      const r = el.getBoundingClientRect(), css = getComputedStyle(el);
      return r.width > 5 && r.height > 5 && css.visibility !== 'hidden' && css.display !== 'none';
    });
  }
  function focusControl(el) {
    try { el.focus({preventScroll:true}); } catch (_) { el.focus(); }
    if (document.activeElement === el) {
      el.classList.add('ja-tv-focused');
      return true;
    }
    return false;
  }
  const style = document.createElement('style');
  style.textContent = '.ja-tv-focused:focus-visible,.ja-tv-focused:focus{outline:3px solid white!important;outline-offset:2px!important}';
  (document.head || document.documentElement).appendChild(style);
  document.addEventListener('keydown', event => {
    if (!['ArrowLeft','ArrowRight','ArrowUp','ArrowDown','Enter'].includes(event.key)) return;
    const nodes = controls();
    if (!nodes.length) return; // Let GeckoView/player handle it normally.
    const active = document.activeElement;
    const activeIndex = nodes.indexOf(active);
    if (event.key === 'Enter') {
      // First OK focuses a real existing control. Further OK uses native
      // browser activation, preserving the player's own click handlers.
      if (activeIndex < 0 && focusControl(nodes[0])) {
        current = 0;
        event.preventDefault(); event.stopPropagation();
      }
      return;
    }
    // Only take over directional keys after a player control has focus.
    // Otherwise preserve player-specific keyboard seeking/navigation.
    if (activeIndex < 0) return;
    const direction = event.key === 'ArrowLeft' || event.key === 'ArrowUp' ? -1 : 1;
    const next = (activeIndex + direction + nodes.length) % nodes.length;
    active.classList.remove('ja-tv-focused');
    if (focusControl(nodes[next])) {
      current = next;
      event.preventDefault(); event.stopPropagation();
    }
  }, true);
})();
