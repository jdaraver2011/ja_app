// JA v2.9.0: hide the floating NTV chat/support bubble. Phone viewport sizing
// is handled natively so the browser and embedded player share the same bounds.
// This script is deliberately scoped to NTV pages/frames and never targets
// video, playback controls, captions, or menus.
(() => {
  'use strict';
  if (globalThis.__jaNtvArtifactFix) return;
  globalThis.__jaNtvArtifactFix = true;

  const ntvDomains = ['ntv.cx', 'ntx.cx', 'ntv.st', 'ntvs.cx', 'ntvstream.cx'];
  const matchesNtv = value => {
    try {
      const host = new URL(value, location.href).hostname.toLowerCase();
      return ntvDomains.some(domain => host === domain || host.endsWith('.' + domain));
    } catch (_) { return false; }
  };
  let ntvContext = matchesNtv(location.href);
  if (!ntvContext) {
    try { ntvContext = matchesNtv(document.referrer); } catch (_) {}
  }
  if (!ntvContext) return;

  const fixedChatSelectors = [
    'iframe[src*="chat" i]', 'iframe[title*="chat" i]',
    'iframe[src*="messenger" i]', 'iframe[src*="intercom" i]',
    '[aria-label*="chat" i]', '[title*="chat" i]',
    '[class*="chat" i]', '[id*="chat" i]',
    '[class*="messenger" i]', '[id*="messenger" i]',
    '[class*="intercom" i]', '[id*="intercom" i]',
    '[class*="support-widget" i]', '[id*="support-widget" i]'
  ].join(',');

  function hideFloatingChat() {
    const vw = innerWidth || document.documentElement.clientWidth;
    const vh = window.visualViewport?.height || innerHeight || document.documentElement.clientHeight;
    for (const candidate of document.querySelectorAll(fixedChatSelectors)) {
      if (!candidate.isConnected || candidate.closest('video,.jwplayer,.jw-controlbar,.jw-menu')) continue;
      let target = candidate;
      let fixed = false;
      for (let node = candidate, depth = 0; node && depth < 5; node = node.parentElement, depth++) {
        if (node.matches('video,.jwplayer,.jw-controlbar,.jw-menu')) break;
        if (getComputedStyle(node).position === 'fixed') { target = node; fixed = true; break; }
      }
      if (!fixed) continue;
      const rect = target.getBoundingClientRect();
      if (rect.width < 22 || rect.height < 22 || rect.width > 190 || rect.height > 190) continue;
      if (rect.right < vw * 0.78 || rect.left > vw || rect.bottom < vh * 0.12 || rect.top > vh * 0.9) continue;
      target.style.setProperty('display', 'none', 'important');
      target.setAttribute('data-ja-ntv-chat-hidden', 'true');
    }
  }

  let queued = false;
  const refresh = () => {
    if (queued) return;
    queued = true;
    setTimeout(() => {
      queued = false;
      hideFloatingChat();
    }, 180);
  };
  refresh();
  window.addEventListener('load', refresh, {once: true, passive: true});

  // NTV may inject its floating support bubble after the player page loads.
  // Observe only briefly, then disconnect to avoid ongoing DOM work.
  if (document.documentElement) {
    const observer = new MutationObserver(refresh);
    observer.observe(document.documentElement, {childList: true, subtree: true});
    setTimeout(() => observer.disconnect(), 30000);
  }
})();
