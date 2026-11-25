(() => {
  'use strict';
  if (globalThis.__jaNtvChromeTrim) return;
  globalThis.__jaNtvChromeTrim = true;

  const promoText = /want\b.{0,90}\b(?:clean|ad|ad[-\s]?free|free)\b.{0,70}\b(?:experience|interruptions?)\b/i;
  const hidden = new WeakSet();
  let scanTimer = 0;
  let scanCount = 0;
  let promoRemoved = false;
  let bottomNavigationHidden = false;
  let observer = null;

  const textOf = node => `${node.innerText || node.textContent || ''} ${node.getAttribute?.('aria-label') || ''} ${node.title || ''}`
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

  function hidePromo() {
    const detailed = [];
    const fallback = [];
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    while (walker.nextNode()) {
      const textNode = walker.currentNode;
      if (!promoText.test((textNode.nodeValue || '').replace(/\s+/g, ' ').trim())) continue;
      let node = textNode.parentElement;
      for (let depth = 0; node && depth < 7; depth++, node = node.parentElement) {
        if (node === document.body || node === document.documentElement || hidden.has(node) || containsMedia(node)) continue;
        const rect = visibleRect(node);
        if (!rect || rect.top > innerHeight * 0.24 || rect.width < innerWidth * 0.42 ||
            rect.height > innerHeight * 0.30) continue;
        const match = {node, area: rect.width * rect.height};
        fallback.push(match);
        if (/cryptotiptv\.pro|\btry\s+it\b/i.test(textOf(node))) detailed.push(match);
      }
    }
    const matches = detailed.length ? detailed : fallback;
    matches.sort((a, b) => b.area - a.area);
    return matches.length ? hide(matches[0].node, 'promo') : false;
  }

  function hideBottomNavigation(nodes) {
    const terms = ['channels', 'discord', 'favorites', 'menu'];
    const matches = [];
    for (const node of nodes) {
      if (hidden.has(node)) continue;
      const rect = visibleRect(node);
      if (!rect || rect.width < innerWidth * 0.35 || rect.height > innerHeight * 0.36 ||
          rect.top < innerHeight * 0.55) continue;
      const text = textOf(node);
      if (!terms.every(term => text.includes(term))) continue;
      matches.push({node, area: rect.width * rect.height});
    }
    matches.sort((a, b) => a.area - b.area);
    const node = matches[0]?.node;
    const didHide = hide(node, 'navigation');
    if (didHide) document.documentElement.setAttribute('data-ja-ntv-nav-hidden', 'true');
    return didHide;
  }

  function scan() {
    scanTimer = 0;
    if (!document.body || document.hidden) return;
    scanCount++;
    const nodes = candidates();
    promoRemoved = hidePromo() || promoRemoved;
    bottomNavigationHidden = hideBottomNavigation(nodes) || bottomNavigationHidden;
    if (promoRemoved) document.documentElement.setAttribute('data-ja-ntv-chrome-trimmed', 'true');
    if ((promoRemoved && bottomNavigationHidden) || scanCount >= 120) {
      observer?.disconnect();
      observer = null;
    }
  }

  function schedule() {
    if (scanTimer || document.hidden || scanCount >= 120 || (promoRemoved && bottomNavigationHidden)) return;
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
