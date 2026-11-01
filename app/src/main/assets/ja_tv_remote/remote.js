// JA v1.0.8: isolated TV control bridge. Do not alter ja_autoplay.
(() => {
  'use strict';
  if (globalThis.__jaTvRemote108) return;
  globalThis.__jaTvRemote108 = true;
  let port = null, attempts = 0, controlMode = false, selected = -1;
  let hidden = [], hideTimer = null, perfTimer = null;
  let menuSelection = -1;
  const MENU_FOCUS_TAG = 'data-ja-tv-menu-focus';
  const TAG = 'data-ja-tv-focus';

  const send = (o) => { try { port?.postMessage({frame: location.hostname, ...o}); } catch (_) {} };
  const visible = (el) => {
    if (!el || !el.isConnected) return false;
    const s = getComputedStyle(el), r = el.getBoundingClientRect();
    return s.display !== 'none' && s.visibility !== 'hidden' && r.width > 4 && r.height > 4;
  };
  const videos = () => Array.from(document.querySelectorAll('video'));
  const currentVideo = () => {
    const list = videos().filter(visible);
    return list.find(v => !v.paused && !v.ended) || list[0] || null;
  };
  const bottomBarCandidates = () => Array.from(document.querySelectorAll(
    '[class*="control" i], [class*="toolbar" i], [class*="player-bar" i], [class*="progress" i]'
  )).filter(el => {
    if (!visible(el) || el.tagName === 'VIDEO') return false;
    const r = el.getBoundingClientRect();
    const vw = innerWidth || document.documentElement.clientWidth;
    const vh = innerHeight || document.documentElement.clientHeight;
    return r.width > 40 && r.height < vh * .26 && r.bottom >= vh * .68 && r.top <= vh && r.width <= vw * 1.02;
  });
  const labelOf = (e) => [e.getAttribute('aria-label'), e.getAttribute('title'), e.getAttribute('data-tooltip'),
    e.getAttribute('data-title'), e.innerText, e.className?.baseVal || e.className, e.parentElement?.getAttribute('title')]
    .filter(x => typeof x === 'string').join(' ').toLowerCase().slice(0, 200);
  const controlElements = () => {
    const selectors = 'button, [role="button"], [tabindex], [class*="button" i], [class*="btn" i]';
    const vh = innerHeight || document.documentElement.clientHeight;
    const vw = innerWidth || document.documentElement.clientWidth;
    const candidates = Array.from(document.querySelectorAll(selectors)).filter(el => {
      if (!visible(el) || el.tagName === 'VIDEO') return false;
      const r = el.getBoundingClientRect();
      if (r.top < vh * .55 || r.left < vw * .44 || r.width > vw * .35 || r.height > vh * .22) return false;
      return r.width >= 12 && r.height >= 12;
    });
    // Prefer named CC, settings, fullscreen controls; include other small buttons on bottom right.
    return [...new Set(candidates)].sort((a,b) => {
      const ar=a.getBoundingClientRect(), br=b.getBoundingClientRect();
      return ar.left - br.left || ar.top - br.top;
    }).slice(0, 16);
  };
  // JW Player menus vary between versions. Inspect selectable rows rather
  // than rejecting a whole parent because it has a zero-sized rectangle.
  const MENU_SELECTORS = [
    '.jw-settings-content-item', '.jw-settings-submenu-button',
    '.jw-settings-menu button', '.jw-settings-submenu button',
    '.jw-settings-content button', '.jw-settings-topbar button',
    '.jw-captions-menu button', '.jw-settings-menu [role="menuitem"]',
    '.jw-settings-menu [role="menuitemradio"]',
    '[class*="jw-settings"] [role="button"]',
    '[class*="jw-settings"] [tabindex]'
  ].join(', ');
  const menuRoots = () => Array.from(document.querySelectorAll(
    '.jw-settings-menu, .jw-settings-content, .jw-settings-submenu, .jw-settings-topbar, .jw-captions-menu, [class*="jw-settings"]'
  ));
  const menuVisible = el => {
    if (!el?.isConnected) return false;
    const style=getComputedStyle(el);
    if (style.display==='none' || style.visibility==='hidden' || Number(style.opacity)===0) return false;
    if (el.closest('[hidden], [inert], [aria-hidden="true"]')) return false;
    const r=el.getBoundingClientRect();
    if (r.width>2 && r.height>2) return true;
    // Some JW menu controls use zero-sized wrappers around text nodes.
    return Array.from(el.children).some(child => {
      const c=child.getBoundingClientRect();
      return c.width>2 && c.height>2;
    });
  };
  function menuItems() {
    const items=Array.from(document.querySelectorAll(MENU_SELECTORS))
      .filter(el => menuVisible(el) && !el.matches('.jw-settings-close, [aria-label*="close" i]'));
    return [...new Set(items)]
      .filter(el => !items.some(other => other!==el && el.contains(other)))
      .sort((a,b) => {
        const ar=a.getBoundingClientRect(),br=b.getBoundingClientRect();
        return ar.top-br.top || ar.left-br.left;
      });
  }
  function inspectMenus(reason) {
    const roots=menuRoots();
    const selectors=Array.from(document.querySelectorAll(MENU_SELECTORS));
    const samples=roots.slice(0,12).map(el => {
      const r=el.getBoundingClientRect(), s=getComputedStyle(el);
      return {tag:el.tagName, cls:String(el.className).slice(0,120),
        width:Math.round(r.width),height:Math.round(r.height),
        display:s.display,visibility:s.visibility,
        text:(el.innerText||el.textContent||'').trim().replace(/\s+/g,' ').slice(0,120)};
    });
    send({event:'MENU_DOM_DIAGNOSTIC',reason,roots:roots.length,
      selectorMatches:selectors.length,samples});
  }
  function clearMenuFocus() {
    document.querySelectorAll(`[${MENU_FOCUS_TAG}]`).forEach(el => {
      el.removeAttribute(MENU_FOCUS_TAG);
      el.style.removeProperty('outline');
      el.style.removeProperty('outline-offset');
    });
  }
  function selectMenu(delta, first = false) {
    const items=menuItems();
    if (!items.length) { inspectMenus('selectMenu'); return send({event:'MENU_NOT_FOUND'}); }
    clearMenuFocus();
    menuSelection = first || menuSelection < 0 ? 0 : (menuSelection + delta + items.length) % items.length;
    const item=items[menuSelection];
    item.setAttribute(MENU_FOCUS_TAG,'true');
    item.style.setProperty('outline','3px solid white');
    item.style.setProperty('outline-offset','2px');
    try { item.scrollIntoView({block:'nearest'}); item.focus({preventScroll:true}); } catch (_) {}
    send({event:'MENU_SELECTED', index:menuSelection, total:items.length, label:labelOf(item)});
  }
  function activateMenu() {
    const items=menuItems();
    const item=items[menuSelection];
    if (!item) { inspectMenus('activateMenu'); return send({event:'MENU_NOT_FOUND'}); }
    const label=labelOf(item);
    item.click();
    clearMenuFocus(); menuSelection=-1;
    send({event:'MENU_ACTIVATED',label});
    // Some JW Player menus remain open to select more options.
    setTimeout(() => { if (menuItems().length) {
      send({event:'MENU_FOUND',count:menuItems().length,labels:menuItems().map(labelOf)});
      selectMenu(0,true);
    } else if (!controlMode) hideWhenPlaying(); },120);
  }
  // Keep center display controls hidden while playing. A stylesheet survives
  // JW Player rerendering the controls (unlike one-time inline visibility).
  const CENTER_HIDE_ATTR='data-ja-tv-hide-center';
  const centerStyle=document.createElement('style');
  centerStyle.textContent=`html[${CENTER_HIDE_ATTR}="true"] .jw-display-controls,
    html[${CENTER_HIDE_ATTR}="true"] .jw-display-icon-container {
      opacity: 0 !important; visibility: hidden !important;
      pointer-events: none !important;
    }`;
  (document.head || document.documentElement).appendChild(centerStyle);
  function updateCenterVisibility() {
    const v=currentVideo();
    if (v && !v.paused && !controlMode) document.documentElement.setAttribute(CENTER_HIDE_ATTR,'true');
    else document.documentElement.removeAttribute(CENTER_HIDE_ATTR);
  }
  function hideCentralPlaybackIcons() {
    updateCenterVisibility();
    return document.querySelectorAll('.jw-display-controls, .jw-display-icon-container').length;
  }
  function restore() {
    document.documentElement.removeAttribute(CENTER_HIDE_ATTR);
    if (hideTimer) { clearTimeout(hideTimer); hideTimer = null; }
    for (const [el, previous] of hidden) {
      try { if (el.isConnected) {
        if (previous === null) el.style.removeProperty('visibility');
        else el.style.setProperty('visibility', previous);
      }} catch (_) {}
    }
    hidden = [];
  }
  function clearFocus() {
    document.querySelectorAll(`[${TAG}]`).forEach(el => {
      el.removeAttribute(TAG);
      el.style.removeProperty('outline');
      el.style.removeProperty('outline-offset');
    });
  }
  function hideWhenPlaying(delay = 2400) {
    restore();
    if (controlMode) return;
    hideTimer = setTimeout(() => {
      hideTimer = null;
      const video = currentVideo();
      if (!video || video.paused || controlMode) return;
      // Try to clear hover without clicking; don't force-focus or click video.
      try { document.activeElement?.blur?.(); } catch (_) {}
      const root = video.closest('[class*="player" i]') || video.parentElement;
      if (root) {
        try { root.dispatchEvent(new MouseEvent('mouseleave', {bubbles: true})); } catch (_) {}
      }
      // Conservative fallback: only named bottom controls/progress bars,
      // never the video, player root, page, or menu dialogs.
      const bars = bottomBarCandidates().filter(el =>
        /control|toolbar|player-bar|progress/i.test(String(el.className)) &&
        !el.querySelector('video') && !el.matches('video')
      );
      const targets = bars.filter(el => !bars.some(parent => parent !== el && el.contains(parent)));
      for (const el of targets.slice(0, 15)) {
        hidden.push([el, el.style.getPropertyValue('visibility') || null]);
        el.style.setProperty('visibility', 'hidden');
      }
      const centerCount = hideCentralPlaybackIcons();
      send({event:'CONTROLS_HIDDEN', count:hidden.length, centerCandidates:centerCount});
    }, delay);
  }
  function select(items, n) {
    clearFocus();
    if (!items.length) { send({event:'NO_CONTROLS'}); return; }
    selected = ((n % items.length) + items.length) % items.length;
    const target = items[selected];
    target.setAttribute(TAG, 'true');
    target.style.setProperty('outline', '3px solid white');
    target.style.setProperty('outline-offset', '3px');
    try { target.focus({preventScroll:true}); } catch (_) {}
    send({event:'CONTROL_SELECTED', index:selected, total:items.length, label:labelOf(target)});
  }
  function openMode() {
    if (!currentVideo()) return send({event:'NO_VIDEO', action:'controlsOpen'});
    restore(); controlMode = true; menuSelection=-1; clearMenuFocus(); updateCenterVisibility();
    const items = controlElements();
    send({event:'CONTROLS_FOUND', count:items.length, labels:items.map(labelOf)});
    select(items, Math.min(Math.max(0, selected), items.length-1));
  }
  function closeMode() {
    controlMode = false; selected = -1; menuSelection=-1; clearMenuFocus(); clearFocus(); restore();
    send({event:'CONTROL_MODE_CLOSED'});
    hideWhenPlaying();
    updateCenterVisibility();
  }
  async function handle(message) {
    const action = message?.action;
    if (!['toggle','back10','forward10','controlsOpen','controlsNext','controlsPrev','controlsSelect','controlsClose','controlsUp','controlsDown','inspect'].includes(action)) return;
    const video = currentVideo();
    if (!video) { send({event:'NO_VIDEO', action, count:videos().length}); return; }
    try {
      if (action === 'controlsOpen') { openMode(); return; }
      if (action === 'controlsClose') {
        // Return to playback; also close any open JW popover first.
        const close = menuRoots().flatMap(root => Array.from(root.querySelectorAll('.jw-settings-close, [aria-label*=close i]'))).find(visible);
        if (close) { try { close.click(); } catch (_) {} }
        closeMode(); return;
      }
      if (action === 'controlsDown' || action === 'controlsUp') {
        if (!controlMode) openMode();
        const items=menuItems();
        if (items.length) {
          send({event:'MENU_FOUND',count:items.length,labels:items.map(labelOf)});
          selectMenu(action === 'controlsDown' ? 1 : -1, menuSelection < 0);
        } else { inspectMenus(action); send({event:'MENU_NOT_FOUND',action}); }
        return;
      }
      if (action === 'inspect') {
        const items = controlElements();
        send({event:'CONTROLS_FOUND', count:items.length, labels:items.map(labelOf)}); return;
      }
      if (action === 'controlsNext' || action === 'controlsPrev') {
        if (menuItems().length) { selectMenu(action === 'controlsNext' ? 1 : -1, menuSelection < 0); return; }
        if (!controlMode) openMode();
        else select(controlElements(), selected + (action === 'controlsNext' ? 1 : -1));
        return;
      }
      if (action === 'controlsSelect') {
        if (menuItems().length) { if (menuSelection < 0) selectMenu(0,true); else activateMenu(); return; }
        if (!controlMode) { openMode(); return; }
        const target = controlElements()[selected];
        if (!target) return send({event:'NO_CONTROLS'});
        // Click may be ignored by fullscreen/user-activation-restricted players.
        target.click();
        send({event:'CONTROL_ACTIVATED', label:labelOf(target)});
        setTimeout(() => { const items=menuItems(); if (items.length) {
          menuSelection=-1; send({event:'MENU_FOUND',count:items.length,labels:items.map(labelOf)}); selectMenu(0,true);
        } },150);
        return;
      }
      if (action === 'toggle') {
        restore();
        if (video.paused) await video.play(); else video.pause();
        if (video.paused) restore(); else hideWhenPlaying();
      } else if (Number.isFinite(video.duration) && video.seekable.length > 0) {
        const delta = action === 'back10' ? -10 : 10;
        video.currentTime = Math.min(video.seekable.end(video.seekable.length-1),
          Math.max(video.seekable.start(0), video.currentTime + delta));
        if (!video.paused) hideWhenPlaying();
      } else return send({event:'NOT_SEEKABLE', action, readyState:video.readyState});
      send({event:'ACTION_OK', action, paused:video.paused, position:Math.round(video.currentTime), readyState:video.readyState});
    } catch(e) { send({event:'ACTION_ERROR', action, error:e?.name || String(e)}); }
  }
  // Never alter autoplay, only watch status changes to handle show/hide.
  document.addEventListener('pause', () => { if (!controlMode) restore(); updateCenterVisibility(); }, true);
  document.addEventListener('play', () => { if (!controlMode) hideWhenPlaying(); updateCenterVisibility(); }, true);
  // Occasional diagnostics, not a per-frame timer.
  perfTimer = setInterval(() => {
    const video = currentVideo();
    if (!video || video.paused) return;
    try {
      const q = video.getVideoPlaybackQuality?.();
      if (q) send({event:'VIDEO_STATS', width:video.videoWidth, height:video.videoHeight,
        total:q.totalVideoFrames, dropped:q.droppedVideoFrames, position:Math.round(video.currentTime)});
    } catch (_) {}
  }, 15000);
  function connect() {
    if (port || attempts++ >= 25) return;
    try {
      const candidate = browser.runtime.connectNative('ja_tv_remote');
      candidate.onMessage.addListener(handle);
      candidate.onDisconnect.addListener(() => { port=null; if (attempts < 25) setTimeout(connect,1000); });
      port=candidate;
      send({event:'FRAME_READY', count:videos().length, playing:!!currentVideo() && !currentVideo().paused});
    } catch (_) { setTimeout(connect,1000); }
  }
  connect();
})();
