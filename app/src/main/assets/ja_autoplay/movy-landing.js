// Movy opens its full player inline; the hero preview is not the requested media.
// Installed only with JA's existing auto-start extension.
(() => {
  'use strict';
  if (window !== window.top || window.__jaMovyLandingStart) return;
  if (!/^(www\.)?movy\.sx$/i.test(location.hostname) ||
      !/^\/(movie\/[1-9]\d*|tv\/[1-9]\d*\/\d+\/[1-9]\d*)\/?$/.test(location.pathname)) return;
  window.__jaMovyLandingStart = true;
  const initialPath = location.pathname;
  const selector = '[class*="HomeBanner-"][class*="__ctaRow"] > button.mv-btn--primary';
  let timer = null, ticks = 0, clicks = 0, finished = false, lastClick = -10;
  const log = reason => console.info('[JA_AUTOSTART]', 'Movy landing', reason);
  function stop(reason) {
    if (finished) return;
    finished = true;
    clearInterval(timer);
    for (const name of ['keydown', 'pointerdown', 'touchstart']) {
      removeEventListener(name, manual, true);
    }
    log(reason);
  }
  function manual(event) {
    if (event.isTrusted) stop('Cancelled by user input');
  }
  function visible(button) {
    if (!button || !button.isConnected || button.disabled ||
        button.getAttribute('aria-disabled') === 'true' ||
        button.closest('a,form,[href]')) return false;
    const rect = button.getBoundingClientRect(), style = getComputedStyle(button);
    return rect.width >= 12 && rect.height >= 12 && style.display !== 'none' &&
      style.visibility !== 'hidden' && style.pointerEvents !== 'none';
  }
  function tick() {
    if (finished) return;
    if (location.pathname !== initialPath) return stop('Page changed');
    ticks++;
    const buttons = [...document.querySelectorAll(selector)].filter(visible);
    // A preview video can already be playing behind this button: do not use it
    // as proof that the full player opened or as a reason to skip this step.
    if (!buttons.length && clicks) return stop('Landing Play dismissed; media confirmation is separate');
    if (buttons.length === 1 && ticks - lastClick >= 6 && clicks < 3) {
      clicks++;
      lastClick = ticks;
      log('Activating main Play (' + clicks + '/3)');
      try { buttons[0].click(); } catch (_) { /* retain the bounded retry */ }
    }
    if (ticks >= 40) stop('Timed out; manual Play remains available');
  }
  for (const name of ['keydown', 'pointerdown', 'touchstart']) {
    addEventListener(name, manual, true);
  }
  addEventListener('pagehide', () => stop('Page hidden'), {once: true});
  timer = setInterval(tick, 500);
  tick();
})();
