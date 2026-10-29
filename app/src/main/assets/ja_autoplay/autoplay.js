// JA experimental autoplay. Never clicks page elements or navigates.
// Scoped to the configured streaming site. Cross-origin player iframes
// hosted elsewhere cannot be accessed by this extension.
(() => {
  'use strict';
  let attempts = 0;
  const maxAttempts = 12;
  let timer = null;
  const tryVideos = () => {
    if (++attempts > maxAttempts) { if (timer) clearInterval(timer); return; }
    for (const video of document.querySelectorAll('video')) {
      if (video.paused && !video.ended && (video.currentSrc || video.src || video.querySelector('source'))) {
        try {
          const result = video.play();
          if (result && typeof result.catch === 'function') result.catch(() => {});
        } catch (_) { /* Manual Play remains available. */ }
      }
    }
  };
  tryVideos();
  timer = setInterval(tryVideos, 1500);
  window.addEventListener('pagehide', () => { if (timer) clearInterval(timer); }, { once: true });
})();
