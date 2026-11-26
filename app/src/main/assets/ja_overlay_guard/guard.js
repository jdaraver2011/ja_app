/* JA v1.1.6: embedded-player *in-page* scam overlay guard.
 * The GeckoView PromptDelegate separately dismisses JS alerts/confirmations.
 * This content script runs in every frame, changes only high-confidence
 * deceptive overlays, and NEVER changes video elements, player controls,
 * stream URLs, playback state, or remote key handling.
 */
(() => {
  'use strict';
  if (globalThis.__jaOverlayGuard116) return;
  globalThis.__jaOverlayGuard116 = true;

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
  let live = true;
  let reconnectTimer = null;
  let sentFrames = 0;

  function send(kind, detail, metrics = {}) {
    if (!live) return;
    const message = { event: kind, host, detail: (detail || '').slice(0, 90), ...metrics };
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
        reconnectTimer = setTimeout(() => { reconnectScheduled = false; }, 1800);
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
    if (!live || document.hidden) return;
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

  // Review19: changed subtrees first; bounded full sweeps remain a safety net.
  // A time budget is cooperative: a single browser DOM operation cannot be preempted.
  const SLICE_NODES = 160, SLICE_MS = 4, MAX_ROOTS = 64;
  let roots = [], active = null, workTimer = null, sweepTimer = null, metricsTimer = null;
  let overflow = false;
  const metrics = {slices:0, nodes:0, fullScans:0, playbackSkips:0, mutationRoots:0, scanMs:0, maxSliceMs:0};

  // Full-document sweeps are a safety net for missed mutations. During active
  // media they compete with decoding and rendering, so rely on the observer
  // until playback pauses or ends. Mutation-driven overlay checks stay active.
  function videoActive() {
    try { return [...document.querySelectorAll('video')].some(video => !video.paused && !video.ended); }
    catch (_) { return false; }
  }

  function inspectText(textNode) {
    const content = (textNode.textContent || '').replace(/\s+/g, ' ').trim();
    if (!content || content.length > 240 || !isScamText(content)) return;
    const parent = textNode.parentElement;
    if (!parent || !visible(parent)) return;
    if (/streaming now:/i.test(content) && !sourceIsVidspark) return;
    const panel = panelFor(textNode);
    if (panel && hide(panel, 'OVERLAY_BLOCKED', content)) {
      removeNearbyBackdrop(panel);
      if (sourceIsVidspark) notifyParent(content);
    }
  }
  function arm(delay = 220) {
    if (!live || document.hidden || workTimer !== null) return;
    workTimer = setTimeout(runSlice, delay);
  }
  function enqueue(root, mutation = false) {
    if (!live || document.hidden || !root || !root.isConnected) return;
    if (root.nodeType !== 1 && root.nodeType !== 3) return;
    // A pending ancestor already includes the new work. Requeue mutations to
    // the active root, since the changed node may be behind its walker cursor.
    if (roots.some(old => old === root || old.contains?.(root))) return;
    roots = roots.filter(old => !root.contains?.(old));
    if (roots.length >= MAX_ROOTS) { overflow = true; arm(); return; }
    roots.push(root);
    if (mutation) metrics.mutationRoots++;
    arm();
  }
  function sweep() {
    if (!live || document.hidden || !document.body || active || roots.length) return;
    if (videoActive()) { metrics.playbackSkips++; return; }
    metrics.fullScans++;
    enqueue(document.body);
  }
  function runSlice() {
    workTimer = null;
    if (!live || document.hidden) return;
    const began = performance.now();
    let inspected = 0;
    while (inspected < SLICE_NODES && performance.now() - began < SLICE_MS) {
      if (!active) {
        const root = roots.shift();
        if (!root) break;
        if (!root.isConnected) continue;
        active = {root, text:root.nodeType === 3,
          walker:root.nodeType === 3 ? null : document.createTreeWalker(root, NodeFilter.SHOW_TEXT)};
      }
      if (!active.root.isConnected) { active = null; continue; }
      const node = active.text ? active.root : active.walker.nextNode();
      if (active.text || !node) active = null;
      if (!node) continue;
      inspected++; metrics.nodes++;
      if (node.isConnected) inspectText(node);
    }
    const elapsed = performance.now() - began;
    metrics.slices++; metrics.scanMs += elapsed;
    metrics.maxSliceMs = Math.max(metrics.maxSliceMs, elapsed);
    if (!active && !roots.length && overflow) {
      overflow = false; sweep();
    }
    if (active || roots.length) arm(16);
  }
  const observer = new MutationObserver(records => {
    if (!live || document.hidden) return;
    let handled = 0;
    for (const record of records) {
      if (++handled > SLICE_NODES) { overflow = true; arm(); break; }
      if (record.type === 'characterData') enqueue(record.target, true);
      else if (record.type === 'attributes') {
        // Player controls animate class/style attributes constantly. Only
        // rescan attribute changes in nodes named like dialog/overlay chrome;
        // inserted text and the bounded safety sweep still catch generic popups.
        let node = record.target?.nodeType === 1 ? record.target : record.target?.parentElement;
        let relevant = false;
        for (let depth=0; node && depth<6; depth++, node=node.parentElement) {
          const identity = `${node.id || ''} ${typeof node.className === 'string' ? node.className : ''} ${node.getAttribute?.('role') || ''}`;
          if (/modal|popup|pop-up|dialog|warning|alert|notice|scam|overlay|backdrop/i.test(identity)) {
            relevant = true;
            break;
          }
        }
        if (relevant) enqueue(record.target, true);
      }
      else for (const node of record.addedNodes || []) {
        if (++handled > SLICE_NODES) { overflow = true; arm(); break; }
        enqueue(node, true);
        if (overflow) break;
      }
      if (overflow) break;
    }
  });
  function reportMetrics() {
    if (live && !document.hidden && metrics.slices) send('GUARD_PERF', 'cumulative', {
      ...metrics, scanMs:Math.round(metrics.scanMs), maxSliceMs:Math.round(metrics.maxSliceMs * 100) / 100
    });
  }
  function pause() {
    observer.disconnect();
    clearTimeout(workTimer); clearTimeout(reconnectTimer);
    clearTimeout(mediaStateTimer);
    clearInterval(sweepTimer); clearInterval(metricsTimer);
    workTimer = sweepTimer = metricsTimer = reconnectTimer = mediaStateTimer = null;
    active = null; roots = []; overflow = false; reconnectScheduled = false;
    try { port?.disconnect(); } catch (_) {}
    port = null;
  }
  function resume() {
    if (!live || document.hidden || sweepTimer !== null) return;
    observer.observe(document, {childList:true, subtree:true, characterData:true,
      attributes:true, attributeFilter:['class','style','hidden']});
    sweep();
    sweepTimer = setInterval(sweep, 10000);
    metricsTimer = setInterval(reportMetrics, 30000);
  }
  let mediaStateTimer = null;
  function onMediaStateChange() {
    clearTimeout(mediaStateTimer);
    mediaStateTimer = setTimeout(() => {
      mediaStateTimer = null;
      if (!videoActive()) sweep();
    }, 700);
  }
  for (const event of ['play', 'playing', 'waiting', 'stalled', 'pause', 'ended', 'emptied']) {
    document.addEventListener(event, onMediaStateChange, true);
  }
  document.addEventListener('DOMContentLoaded', sweep, {once:true});
  document.addEventListener('visibilitychange', () => document.hidden ? pause() : resume());
  window.addEventListener('pagehide', () => { reportMetrics(); live = false; pause(); });
  window.addEventListener('pageshow', () => { live = true; resume(); });
  resume();
})();
