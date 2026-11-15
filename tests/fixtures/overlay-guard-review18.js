/* JA v1.1.5u: embedded-player *in-page* scam overlay guard.
 * The GeckoView PromptDelegate separately dismisses JS alerts/confirmations.
 * This content script runs in every frame, changes only high-confidence
 * deceptive overlays, and NEVER changes video elements, player controls,
 * stream URLs, playback state, or remote key handling.
 */
(() => {
  'use strict';
  if (globalThis.__jaOverlayGuard115u) return;
  globalThis.__jaOverlayGuard115u = true;

  // Exact claims seen on user screenshots. Avoid generic terms like "VPN"
  // or "Install" alone, which are too common in legitimate pages.
  const scamText = [
    /(?:\(\d+\)\s*)?virus on your phone/i,
    /protect your connection with vpn/i,
    /vpn must be activated/i,
    /install\s+clearvpn/i,
    /activate\s+clearvpn/i,
    /to access this content securely.{0,80}activate/i,
    /vpn is required to continue/i,
    /streaming now:\s*.{2,90}\bepisode\s*\d+/i
  ];
  const distinctMarker = /virus on your phone|clearvpn|vpn must be activated|protect your connection with vpn|streaming now:/i;
  const isScamText = value => scamText.some(re => re.test(value));
  const host = location.hostname.toLowerCase();
  const sourceIsVidspark = host === 'vidspark.to' || host.endsWith('.vidspark.to');
  const hidden = new WeakSet();
  let port = null;
  let reconnectScheduled = false;
  let lastScan = 0;
  let pendingScan = false;
  let sentFrames = 0;

  function send(kind, detail) {
    const message = { event: kind, host, detail: (detail || '').slice(0, 90) };
    try {
      if (!port) {
        port = browser.runtime.connectNative('ja_overlay_guard');
        port.onDisconnect.addListener(() => { port = null; });
      }
      port.postMessage(message);
    } catch (_) {
      port = null;
      if (!reconnectScheduled) {
        reconnectScheduled = true;
        setTimeout(() => { reconnectScheduled = false; }, 1800);
      }
    }
  }

  function visible(node) {
    if (!node || !node.isConnected || hidden.has(node)) return false;
    let s;
    try { s = getComputedStyle(node); } catch (_) { return false; }
    if (s.display === 'none' || s.visibility === 'hidden' || Number(s.opacity) === 0) return false;
    const r = node.getBoundingClientRect();
    return r.width >= 12 && r.height >= 12;
  }
  function hasVideo(node) {
    return !!(node.querySelector && node.querySelector('video'));
  }
  function hasActiveVideo(node) {
    try {
      return !!node.querySelector('video:not([hidden])') &&
        [...node.querySelectorAll('video')].some(v => !v.paused && v.readyState > 1);
    } catch (_) { return false; }
  }
  function hide(node, kind, detail) {
    if (!node || node === document.body || node === document.documentElement || !visible(node) || hasActiveVideo(node)) return false;
    hidden.add(node);
    node.setAttribute('data-ja-popup-suppressed', 'true');
    node.style.setProperty('display', 'none', 'important');
    node.style.setProperty('pointer-events', 'none', 'important');
    send(kind, detail);
    return true;
  }

  function panelFor(textNode) {
    let node = textNode.parentElement;
    let fallback = null;
    for (let depth = 0; node && depth < 9; depth++, node = node.parentElement) {
      if (node === document.body || node === document.documentElement) break;
      if (!visible(node) || hasActiveVideo(node)) continue;
      const r = node.getBoundingClientRect();
      // Never hide a large app / movie player container. Remove just the
      // dialog card, or an isolated full-screen backdrop containing that card.
      if (r.width > innerWidth * 0.97 && r.height > innerHeight * 0.95) continue;
      const cls = String(node.className || '') + ' ' + String(node.id || '') +
        ' ' + (node.getAttribute('role') || '');
      const marked = /modal|popup|pop-up|dialog|warning|alert|notice|scam|overlay/i.test(cls);
      const style = getComputedStyle(node);
      const floating = /fixed|absolute|sticky/.test(style.position);
      const hasAction = !!node.querySelector('button, a, [role="button"], input[type="button"]');
      const text = (node.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 1100);
      if (!isScamText(text)) continue;
      if (!fallback && hasAction && r.width >= 70 && r.height >= 35 &&
          r.width <= innerWidth * 0.92 && r.height <= innerHeight * 0.85) {
        fallback = node;
      }
      if ((marked || floating) && (hasAction || text.length > 24) &&
          r.width >= 70 && r.height >= 35 && r.height <= innerHeight * 0.93) {
        return node;
      }
    }
    return fallback;
  }

  function removeNearbyBackdrop(panel) {
    if (!panel) return;
    const parent = panel.parentElement;
    if (!parent || parent === document.body || hasActiveVideo(parent)) return;
    const pStyle = getComputedStyle(parent);
    const pRect = parent.getBoundingClientRect();
    // If the card is the only content of a fixed dialog/backdrop shell,
    // remove the shell as well; this restores touch access to the player.
    if (pStyle.position === 'fixed' &&
        pRect.width >= innerWidth * 0.85 && pRect.height >= innerHeight * 0.80 &&
        !hasVideo(parent) && parent.querySelectorAll('iframe').length === 0 &&
        parent.children.length <= 4) {
      hide(parent, 'OVERLAY_BACKDROP_BLOCKED', 'backdrop-shell');
      return;
    }
    for (const sibling of Array.from(parent.children).slice(0, 12)) {
      if (sibling === panel || !visible(sibling) || hasVideo(sibling)) continue;
      const cls = String(sibling.className || '') + ' ' + (sibling.id || '');
      const style = getComputedStyle(sibling);
      const r = sibling.getBoundingClientRect();
      if (/backdrop|scrim|dim(?:mer)?|modal-mask|overlay-mask/i.test(cls) &&
          /fixed|absolute/.test(style.position) &&
          r.width >= innerWidth * 0.80 && r.height >= innerHeight * 0.75) {
        hide(sibling, 'OVERLAY_BACKDROP_BLOCKED', 'backdrop-sibling');
      }
    }
  }

  // If a separate floating iframe serves only a scam page, ask the parent
  // content script to hide THAT iframe. Never remove a full-sized embedded
  // player iframe: it may contain video in a more deeply nested frame.
  function notifyParent(detail) {
    if (window === window.parent || sentFrames >= 2 || hasVideo(document)) return;
    sentFrames += 1;
    try { window.parent.postMessage({ __jaGuard115u: true, kind: 'SCAM_FRAME', detail, host }, '*'); }
    catch (_) {}
  }

  function handleChildFrame(event) {
    const data = event.data;
    if (!data || data.__jaGuard115u !== true || data.kind !== 'SCAM_FRAME') return;
    if (!data.detail || !distinctMarker.test(data.detail)) return;
    const childHost = String(data.host || '').toLowerCase();
    if (childHost !== 'vidspark.to' && !childHost.endsWith('.vidspark.to')) return;
    for (const frame of document.querySelectorAll('iframe')) {
      if (frame.contentWindow !== event.source || !visible(frame)) continue;
      const src = frame.getAttribute('src') || '';
      let srcHost = '';
      try { srcHost = new URL(src, document.baseURI).hostname.toLowerCase(); } catch (_) {}
      // The iframe can redirect after insertion, so its src attribute may
      // still name the original provider. event.origin reflects the actual
      // child document and cannot be chosen by a different frame.
      const originMatches = event.origin === `https://${childHost}` ||
        event.origin === `http://${childHost}`;
      if (srcHost !== childHost && !originMatches) continue;
      const style = getComputedStyle(frame);
      const rect = frame.getBoundingClientRect();
      const zIndex = Number.parseInt(style.zIndex, 10) || 0;
      const floating = /fixed|absolute/.test(style.position) || zIndex >= 100;
      const small = rect.width < innerWidth * 0.70 || rect.height < innerHeight * 0.65;
      if (floating && small) hide(frame, 'AD_FRAME_BLOCKED', childHost);
    }
  }
  window.addEventListener('message', handleChildFrame, false);

  function scan() {
    if (!document.body) return;
    lastScan = Date.now();
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    let textNode;
    let inspected = 0;
    let removed = 0;
    while ((textNode = walker.nextNode()) && inspected++ < 3200 && removed < 7) {
      const content = (textNode.textContent || '').replace(/\s+/g, ' ').trim();
      if (!content || content.length > 240) continue;
      if (!isScamText(content)) continue;
      const parent = textNode.parentElement;
      if (!parent || !visible(parent)) continue;
      // "Streaming now" can be ordinary site text. Only flag that exact
      // misleading prompt on its observed host, not on a video details page.
      if (/streaming now:/i.test(content) && !sourceIsVidspark) continue;
      const panel = panelFor(textNode);
      if (panel && hide(panel, 'OVERLAY_BLOCKED', content)) {
        removed++;
        removeNearbyBackdrop(panel);
        if (sourceIsVidspark) notifyParent(content);
      }
    }
  }

  function scheduleScan() {
    if (pendingScan) return;
    pendingScan = true;
    setTimeout(() => {
      pendingScan = false;
      if (Date.now() - lastScan < 250) return;
      scan();
    }, 220);
  }
  const observer = new MutationObserver(scheduleScan);
  observer.observe(document, { childList: true, subtree: true, characterData: true });
  document.addEventListener('DOMContentLoaded', scheduleScan, { once: true });
  scheduleScan();
  setInterval(scheduleScan, 1700);
})();
