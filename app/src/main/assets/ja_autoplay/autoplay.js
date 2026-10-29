// JA experimental auto-start. Opt-in installation from Settings.
// Try a recognizable in-page player Play control once. Do not click links,
// popups, generic overlays or coordinates. Browser gesture restrictions may
// reject synthetic clicks. The user's manual Play button always remains.
(() => {
  'use strict';
  let attempts = 0;
  let clicked = false;
  const maxAttempts = 12;
  const selectors = [
    'button[aria-label="Play"]',
    'button[title="Play"]',
    '.vjs-big-play-button',
    '.jw-display-icon-container .jw-icon-display',
    '.plyr__control--overlaid',
    '.fp-play',
    '.play-button[role="button"]'
  ];
  const safeCandidate = (el) => {
    if (!el || !el.isConnected || el.closest('a, form')) return false;
    const rect = el.getBoundingClientRect();
    if (rect.width < 12 || rect.height < 12) return false;
    const style = getComputedStyle(el);
    return style.visibility !== 'hidden' && style.display !== 'none';
  };
  const attempt = () => {
    if (++attempts > maxAttempts || clicked) { clearInterval(timer); return; }
    const video = document.querySelector('video');
    if (video && !video.ended && video.paused) {
      try { const p = video.play(); if (p && p.catch) p.catch(() => {}); } catch (_) {}
    }
    for (const selector of selectors) {
      const candidate = document.querySelector(selector);
      if (!safeCandidate(candidate)) continue;
      clicked = true;
      console.info('[JA_AUTOSTART] Attempting recognized player control:', selector);
      candidate.click();
      clearInterval(timer);
      return;
    }
    if (attempts === maxAttempts) console.info('[JA_AUTOSTART] No recognized Play control; manual Play required');
  };
  const timer = setInterval(attempt, 1500);
  attempt();
  window.addEventListener('pagehide', () => clearInterval(timer), { once: true });
})();
