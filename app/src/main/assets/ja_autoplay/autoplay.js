// JA Experimental Auto-Start v1.1 — installed only when Settings enables it.
// Executes in each HTTP(S) frame, including cross-origin player iframes.
// Only activate recognized player controls, with no coordinate clicks or links.
(() => {
  'use strict';
  if (window.__jaAutoStart11) return;
  window.__jaAutoStart11 = true;
  const label = '[JA_AUTOSTART]';
  const frame = window === window.top ? 'top' : 'iframe';
  const daddyFrame = frame === 'iframe' && location.protocol === 'https:' &&
    location.hostname === 'dembed.st' && location.pathname === '/premiumtv/daddy.php';
  const selectors = [
    '.vjs-big-play-button', '.jw-display-icon-container .jw-icon-display',
    '.plyr__control--overlaid', '.fp-play',
    'button[aria-label="Play"]', 'button[title="Play"]',
    'button[aria-label="Start playback"]', '.play-button[role="button"]'
  ];
  let tries = 0, activated = false, finished = false;
  let daddyVideoAttempted = false;
  const daddyControlsAttempted = new WeakSet();
  let daddyControlAttempts = 0;
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
  function daddyPlayControl() {
    const prompt = /^(?:tap|click) to play$/i;
    const labels = control => [
      control.getAttribute('aria-label'), control.getAttribute('title'),
      control.textContent?.trim().replace(/\s+/g, ' ')
    ];
    // A provider prompt may be a plain element outside the JW player.
    const prompts = [...document.querySelectorAll('button, [role="button"], [aria-label], [title], div, span')]
      .filter(control => labels(control).some(text => prompt.test(text?.trim() || '')))
      .reverse();
    const player = document.querySelector('#player');
    const controls = [...(player?.querySelectorAll(
      '.play-wrapper[data-poster], .jw-icon-display, .vjs-big-play-button, button, [role="button"]'
    ) || [])].filter(control => control.matches('.play-wrapper[data-poster], .vjs-big-play-button') ||
      labels(control).some(text => /^(?:play|start playback)$/i.test(text?.trim() || '')));
    return [...prompts, ...controls].find(control => {
      if (daddyControlsAttempted.has(control) || !visible(control) ||
          control.disabled || control.getAttribute('aria-disabled') === 'true') return false;
      const bounds = control.getBoundingClientRect();
      const hit = document.elementFromPoint(bounds.left + bounds.width / 2, bounds.top + bounds.height / 2);
      return !!hit && control.contains(hit);
    });
  }
  function tryDaddyStart(videos) {
    const control = daddyControlAttempts < 2 && daddyPlayControl();
    if (control) {
      // A click can reveal a second prompt. Do not click its original subtree again.
      for (let node = control; node && /^(?:tap|click) to play$/i.test(node.textContent?.trim().replace(/\s+/g, ' ')); node = node.parentElement) {
        daddyControlsAttempted.add(node);
      }
      daddyControlsAttempted.add(control);
      daddyControlAttempts++;
      log('DaddyLive play control detected; activating', daddyControlAttempts);
      try { control.click(); } catch (error) { log('Control click failed:', error.name || 'unknown'); }
      return;
    }
    const video = videos.find(video => video.currentSrc && video.paused && !video.ended && visible(video));
    if (!daddyVideoAttempted && video) {
      daddyVideoAttempted = true;
      try {
        video.play()?.catch(error => log('video.play rejected:', error.name || 'unknown'));
      } catch (error) { log('video.play error:', error.name || 'unknown'); }
    }
    if (tries >= 30) stop('DaddyLive startup window ended');
  }
  function tick() {
    if (finished) return;
    tries++;
    const videos = [...document.querySelectorAll('video')];
    if (videos.some(v => !v.paused && v.readyState >= 2)) return stop('Playback detected');
    if (daddyFrame) return tryDaddyStart(videos);
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
