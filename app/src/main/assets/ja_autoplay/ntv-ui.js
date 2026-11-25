(() => {
  'use strict';
  if (globalThis.__jaNtvChromeTrim) return;
  globalThis.__jaNtvChromeTrim = true;

  const promoText = /want a clean ad experience/i;
  const promoDomain = /cryptotiptv\.pro/i;
  const hidden = new WeakSet();
  let scanTimer = 0;
  let scanCount = 0;
  let observer = null;

  const textOf = node => (node.innerText || node.textContent || '')
    .replace(/\s+/g, ' ').trim().toLowerCase();
  const visibleRect = node => {
    if (!node || !node.isConnected || hidden.has(node)) return null;
    let style;
    try { style = getComputedStyle(node); } catch (_) { return null; }
    if (style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) === 0) return null;
    const rect = node.getBoundingClientRect();
    if (rect.width < 30 || rect.height < 20) return null;
    return rect;
  };
  const containsMedia = node => !!node.querySelector('video, iframe, audio');
  const hide = (node, kind) => {
    if (!node || node === document.body || node === document.documentElement ||
        hidden.has(node) || containsMedia(node)) return false;
    hidden.add(node);
    node.setAttribute('data-ja-ntv-chrome', kind);
    node.setAttribute('aria-hidden', 'true');
    node.style.setProperty('display', 'none', 'important');
    node.style.setProperty('pointer-events', 'none', 'important');
    return true;
  };

  function candidates() {
    return Array.from(document.querySelectorAll('header, nav, [role="banner"], [role="navigation"], section, footer, div'));
  }

  function hidePromo(nodes) {
    const matches = [];
    for (const node of nodes) {
      if (hidden.has(node) || containsMedia(node)) continue;
      const text = textOf(node);
      if (!promoText.test(text) || !promoDomain.test(text)) continue;
      const rect = visibleRect(node);
      if (!rect || rect.top > innerHeight * 0.24 || rect.width < innerWidth * 0.58 ||
          rect.height > innerHeight * 0.30) continue;
      matches.push({node, area: rect.width * rect.height});
    }
    matches.sort((a, b) => a.area - b.area);
    return matches.length ? hide(matches[0].node, 'promo') : false;
  }

  function scan() {
    scanTimer = 0;
    if (!document.body || document.hidden) return;
    scanCount++;
    const nodes = candidates();
    const changed = hidePromo(nodes);
    if (changed) {
      document.documentElement.setAttribute('data-ja-ntv-chrome-trimmed', 'true');
    }
    if (scanCount >= 24) {
      observer?.disconnect();
      observer = null;
    }
  }

  function schedule() {
    if (scanTimer || document.hidden || scanCount >= 24) return;
    scanTimer = setTimeout(scan, 250);
  }

  schedule();
  observer = new MutationObserver(records => {
    let relevant = false;
    for (const record of records) {
      if (record.type === 'childList' && (record.addedNodes.length || record.removedNodes.length)) {
        relevant = true;
        break;
      }
      const target = record.target.nodeType === Node.TEXT_NODE ? record.target.parentElement : record.target;
      if (target?.closest?.('header, nav, [role="banner"], [role="navigation"], footer')) {
        relevant = true;
        break;
      }
    }
    if (relevant) schedule();
  });
  observer.observe(document.documentElement, {
    childList: true,
    subtree: true,
    characterData: true
  });
  window.addEventListener('pagehide', () => {
    clearTimeout(scanTimer);
    observer?.disconnect();
    observer = null;
  }, {once: true});
})();
