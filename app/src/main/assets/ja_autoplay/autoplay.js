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

// Native TV controls: each permitted frame controls only its own HTML5 videos.
// No arbitrary DOM clicks, no media-key injection, no cross-origin DOM access.
(() => {
  if (window.__jaTvControlBridge) return;
  window.__jaTvControlBridge = true;
  try {
    const port = browser.runtime.connectNative('jaControls');
    port.onMessage.addListener(message => {
      const action = message && message.action;
      if (!['toggle', 'back10', 'forward10'].includes(action)) return;
      const videos = [...document.querySelectorAll('video')];
      const video = videos.find(v => !v.paused && !v.ended) || videos.find(v => v.readyState > 0);
      if (!video) return;
      try {
        if (action === 'toggle') {
          if (video.paused) video.play().catch(e => console.info('[JA_TV] play rejected', e.name));
          else video.pause();
        } else if (Number.isFinite(video.duration) && video.seekable.length) {
          const target = video.currentTime + (action === 'back10' ? -10 : 10);
          const start = video.seekable.start(0);
          const end = video.seekable.end(video.seekable.length - 1);
          video.currentTime = Math.max(start, Math.min(end, target));
        }
      } catch (e) { console.info('[JA_TV] control failed', e.name); }
    });
  } catch (e) { console.info('[JA_TV] native control bridge unavailable', e.name); }
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
