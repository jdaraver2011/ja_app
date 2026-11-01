// JA v1.0.12: opt-in JW timeline scrubbing; preserve v1.0.11 menus and verified playback bridge.
(() => {
  'use strict';
  if (globalThis.__jaTvRemote112) return;
  globalThis.__jaTvRemote112 = true;
  let port = null, attempts = 0, controlMode = false, selected = -1;
  let hidden = [], hideTimer = null, perfTimer = null;
  let menuSelection = -1;
  let ccSelectMode = false;
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
  // v1.0.11. Device logs confirm BOTH Settings and CC are .jw-menu.is-open.
  // Quality: .jw-row-label labels (Auto, 360p, 720p, 1080p).
  // CC: Off, search input and language selection. Inactive sibling menu
  // remains in the DOM; never navigate or click the inactive sibling.
  const openJwMenu = () => {
    const menus = Array.from(document.querySelectorAll('.jw-menu.is-open'))
      .filter(menuVisible);
    return menus[menus.length - 1] || null;
  };
  const menuKind = menu => {
    const head = menu?.querySelector('.jw-menu-head, .jw-tabs, .jw-tab');
    const title = (head?.textContent || '').trim().toLowerCase();
    return /subtitles|captions/.test(title) ? 'CC' : /settings|quality/.test(title) ? 'SETTINGS' : 'OTHER';
  };
  const menuLabel = el => {
    if (!el) return '';
    if (el.tagName === 'SELECT') {
      const option = el.options?.[el.selectedIndex];
      return 'Language: ' + (option?.textContent?.trim() || el.getAttribute('aria-label') || 'select');
    }
    if (el.matches('input')) return el.getAttribute('placeholder') || el.getAttribute('aria-label') || 'Search by language';
    return (el.querySelector('.jw-row-label')?.textContent ||
      (el.matches('.jw-row-label') ? el.textContent : '') ||
      el.getAttribute('aria-label') || el.textContent || labelOf(el)).trim().replace(/\s+/g, ' ').slice(0, 80);
  };
  const menuTargetForLabel = (label, root) => {
    const row = label.closest('button, [role="option"], [role="menuitem"], [role="menuitemradio"], .jw-row, li, label');
    return row && root.contains(row) ? row : label.parentElement && root.contains(label.parentElement) ? label.parentElement : label;
  };
  function menuItems() {
    const open = openJwMenu();
    if (open) {
      // Scoped to OPEN menu body, not Settings/CC menus underneath.
      const body = open.querySelector('.jw-menu-body') || open;
      const found = [];
      for (const label of body.querySelectorAll('.jw-row-label')) {
        if (menuVisible(label)) found.push(menuTargetForLabel(label, open));
      }
      // CC uses additional native input/select or custom language selectors.
      // Include those alongside the clickable Off/language rows.
      if (menuKind(open) === 'CC') {
        const extras = body.querySelectorAll('input:not([type="hidden"]), select, [role="combobox"], [role="listbox"] > [role="option"], [role="searchbox"]');
        for (const el of extras) {
          if (!menuVisible(el) || el.disabled) continue;
          if (el.matches('[role="option"]') && el.closest('select')) continue;
          found.push(el);
        }
      }
      const unique = [...new Set(found)].filter(menuVisible);
      // Avoid selecting a wrapper and its child as two separate choices.
      return unique.filter(el => !unique.some(other => other !== el && other.contains(el)))
        .sort((a, b) => {
          const ar = a.getBoundingClientRect(), br = b.getBoundingClientRect();
          return ar.top - br.top || ar.left - br.left;
        }).slice(0, 80);
    }
    // Other player types: preserve the original fallback selection logic.
    const items = Array.from(document.querySelectorAll(MENU_SELECTORS))
      .filter(el => menuVisible(el) && !el.matches('.jw-settings-close, [aria-label*="close" i]'));
    return [...new Set(items)]
      .filter(el => !items.some(other => other !== el && el.contains(other)))
      .sort((a, b) => {
        const ar = a.getBoundingClientRect(), br = b.getBoundingClientRect();
        return ar.top - br.top || ar.left - br.left;
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
  // v1.0.9: bounded diagnostics ONLY; do not read URLs, inputs or private page content.
  // Inspect the video frame and its open shadow roots. Closed roots are not accessible.
  function inspectDeepMenu(reason) {
    const video = currentVideo();
    if (!video) return;
    const frames = [];
    const candidates = [];
    const shadowHosts = [];
    const matchingText = /^(auto|off|none|quality|subtitles?|captions?|settings|(?:[1-9]\d{2,3})p|(?:0|1)(?:\.\d+)?x)$/i;
    const maxNodes = 5000;
    let count = 0, shadowCount = 0, clipped = false;
    const short = value => String(value || '').replace(/\s+/g, ' ').trim().slice(0, 100);
    const describe = (el, depth, reason) => {
      if (candidates.length >= 35 || !el || !el.getBoundingClientRect) return;
      const r = el.getBoundingClientRect();
      let style;
      try { style = getComputedStyle(el); } catch (_) { return; }
      candidates.push({
        tag: el.tagName?.toLowerCase() || '?',
        cls: short(typeof el.className === 'string' ? el.className : el.className?.baseVal).slice(0, 90),
        role: short(el.getAttribute?.('role')),
        aria: short(el.getAttribute?.('aria-label')),
        text: short(el.textContent).slice(0, 70),
        visible: style.display !== 'none' && style.visibility !== 'hidden' && r.width > 1 && r.height > 1,
        rect: [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)],
        depth, match: reason
      });
    };
    const visit = (root, depth) => {
      if (!root || depth > 5) return;
      let els;
      try { els = root.querySelectorAll('*'); } catch (_) { return; }
      for (const el of els) {
        if (++count > maxNodes) { clipped = true; break; }
        if (el.tagName === 'IFRAME' && frames.length < 12) {
          // Do not log frame URLs, only whether the iframe is measurable.
          const r = el.getBoundingClientRect();
          frames.push({visible:r.width>1&&r.height>1,rect:[Math.round(r.x),Math.round(r.y),Math.round(r.width),Math.round(r.height)]});
        }
        if (el.shadowRoot) {
          shadowCount++;
          if (shadowHosts.length < 12) shadowHosts.push({tag:el.tagName?.toLowerCase(),cls:short(el.className)});
          visit(el.shadowRoot,depth+1);
        }
        if (candidates.length >= 35) continue;
        const label = short(el.getAttribute?.('aria-label') || el.getAttribute?.('title'));
        const text = short(el.textContent);
        const ownText = short(Array.from(el.childNodes || []).filter(n=>n.nodeType===3).map(n=>n.textContent).join(' '));
        const classes = short(el.className);
        const isRelevant = matchingText.test(ownText) || matchingText.test(label) ||
          (/jw-(settings|menu|submenu|captions)|quality|popover|dropdown/i.test(classes) &&
           (/button|div|span|li/i.test(el.tagName)) && (el.children.length < 6));
        if (isRelevant) {
          describe(el,depth,matchingText.test(ownText) ? 'text' : matchingText.test(label) ? 'label' : 'class');
          const ancestor = el.closest?.('button, [role="menuitem"], [role="option"], [tabindex], li');
          if (ancestor && ancestor !== el) describe(ancestor,depth,'ancestor');
        }
      }
    };
    visit(document,0);
    send({event:'MENU_DEEP_DIAGNOSTIC',reason,frame:location.hostname,
      nodeCount:count,clipped,openShadowRoots:shadowCount,shadowHosts,iframes:frames,
      candidates:candidates.slice(0,35)});
  }
  function clearMenuFocus() {
    document.querySelectorAll(`[${MENU_FOCUS_TAG}]`).forEach(el => {
      el.removeAttribute(MENU_FOCUS_TAG);
      el.style.removeProperty('outline');
      el.style.removeProperty('outline-offset');
    });
  }
  function selectMenu(delta, first = false) {
    const items = menuItems();
    if (!items.length) { inspectMenus('selectMenu'); inspectDeepMenu('selectMenu'); return send({event:'MENU_NOT_FOUND'}); }
    clearMenuFocus();
    ccSelectMode = false;
    menuSelection = first || menuSelection < 0 ? 0 : (menuSelection + delta + items.length) % items.length;
    const item = items[menuSelection];
    item.setAttribute(MENU_FOCUS_TAG, 'true');
    item.style.setProperty('outline', '3px solid white');
    item.style.setProperty('outline-offset', '2px');
    try { item.scrollIntoView({block:'nearest'}); item.focus({preventScroll:true}); } catch (_) {}
    const menu = openJwMenu();
    send({event:'MENU_SELECTED', kind:menuKind(menu), index:menuSelection,
      total:items.length, label:menuLabel(item), tag:item.tagName.toLowerCase(),
      cls:String(item.className).slice(0,100)});
  }
  // If the CC menu uses a genuine HTML SELECT, change its selected option
  // via standard input/change events instead of trying to keyboard-navigate
  // GeckoView's OS-native dropdown. Do not change unrelated player controls.
  function changeLanguageSelect(delta) {
    const items = menuItems();
    const el = items[menuSelection];
    if (!ccSelectMode || el?.tagName !== 'SELECT' || !el.options?.length) return false;
    const options = Array.from(el.options);
    let index = el.selectedIndex;
    for (let n = 0; n < options.length; n++) {
      index = (index + delta + options.length) % options.length;
      if (!options[index].disabled) break;
    }
    if (index === el.selectedIndex) return true;
    el.selectedIndex = index;
    el.dispatchEvent(new Event('input', {bubbles:true}));
    el.dispatchEvent(new Event('change', {bubbles:true}));
    send({event:'CC_LANGUAGE_CHANGED', label:menuLabel(el), selectedIndex:index});
    return true;
  }
  function activateMenu() {
    const items = menuItems();
    const item = items[menuSelection];
    if (!item) { inspectMenus('activateMenu'); inspectDeepMenu('activateMenu'); return send({event:'MENU_NOT_FOUND'}); }
    const kind = menuKind(openJwMenu());
    const label = menuLabel(item);
    if (item.tagName === 'SELECT') {
      // OK toggles remote navigation within the language dropdown;
      // Up/Down cycle options; another OK exits dropdown mode.
      ccSelectMode = !ccSelectMode;
      try { item.focus({preventScroll:true}); } catch (_) {}
      send({event:ccSelectMode?'CC_SELECT_OPEN':'CC_SELECT_CLOSED', label,
        optionCount:item.options?.length || 0});
      return;
    }
    if (item.tagName === 'INPUT' || item.matches('[role="searchbox"]')) {
      // Give GeckoView focus so a connected keyboard/on-screen IME can type.
      try { item.focus({preventScroll:true}); item.click(); } catch (_) {}
      send({event:'CC_SEARCH_FOCUSED', label});
      return;
    }
    // Click nearest observed clickable row for both CC and Settings.
    try { item.click(); } catch (e) { send({event:'MENU_ACTIVATION_ERROR', kind, label, error:e?.name || String(e)}); return; }
    clearMenuFocus(); menuSelection = -1; ccSelectMode = false;
    send({event:'MENU_ACTIVATED', kind, label});
    // A menu can remain open after a choice, or close and return to toolbar.
    setTimeout(() => {
      const next = menuItems();
      const menu = openJwMenu();
      if (menu && next.length) {
        send({event:'MENU_FOUND', kind:menuKind(menu), count:next.length, labels:next.slice(0,20).map(menuLabel)});
        selectMenu(0,true);
      } else if (!controlMode) hideWhenPlaying();
    }, 120);
  }
  // Optional, preview-only seeking along the real JW Player progress line.
  // This is driven by existing Kotlin actions (controlsUp/Prev/Next/Select/Close)
  // so v1.0.5 playback and autoplay integrations are left intact.
  let timelineMode = false;
  let timelineTarget = null;
  let timelineIndicator = null;
  let timelineTip = null;
  let timelinePosition = 0;
  let timelineMin = 0;
  let timelineMax = 0;

  function timelineTrack() {
    const selectors = [
      '.jw-controlbar .jw-slider-time .jw-slider-container',
      '.jw-controlbar .jw-slider-time .jw-slider-horizontal',
      '.jw-controlbar .jw-slider-time',
      '.jw-slider-time .jw-slider-container',
      '.jw-slider-time'
    ];
    for (const selector of selectors) {
      const elements = document.querySelectorAll(selector);
      for (const el of elements) {
        const r = el.getBoundingClientRect();
        if (!visible(el) || r.width < 100 || r.height < 1) continue;
        return el;
      }
    }
    return null;
  }
  function timelineRange(video) {
    if (!video || !Number.isFinite(video.duration) || video.duration <= 0) return null;
    const seek = video.seekable;
    if (!seek || !seek.length) return null;
    const start = Math.max(0, seek.start(0));
    const end = Math.min(video.duration, seek.end(seek.length - 1));
    return Number.isFinite(start) && Number.isFinite(end) && end > start
      ? [start, end] : null;
  }
  function clampSeekToRanges(video, target) {
    const ranges = video.seekable;
    if (!ranges || !ranges.length) return target;
    let best = target;
    let bestDistance = Infinity;
    for (let i = 0; i < ranges.length; i++) {
      const start = ranges.start(i), end = ranges.end(i);
      const candidate = Math.min(end, Math.max(start, target));
      const distance = Math.abs(candidate - target);
      if (distance < bestDistance) { best = candidate; bestDistance = distance; }
    }
    return best;
  }
  function formatTimelineTime(time) {
    const total = Math.max(0, Math.floor(time));
    const h = Math.floor(total / 3600);
    const m = String(Math.floor(total / 60) % 60).padStart(2, '0');
    const s = String(total % 60).padStart(2, '0');
    return h > 0 ? `${h}:${m}:${s}` : `${Math.floor(total / 60)}:${s}`;
  }
  function removeTimelineIndicator() {
    try { timelineIndicator?.remove(); } catch (_) {}
    timelineIndicator = null;
    timelineTip = null;
  }
  function renderTimelinePreview() {
    if (!timelineMode) return;
    const track = timelineTrack();
    if (!track) {
      // Keep the preview position; JW sometimes animates its controlbar.
      send({event:'TIMELINE_TRACK_MISSING'});
      return;
    }
    timelineTarget = track;
    const rect = track.getBoundingClientRect();
    const proportion = Math.max(0, Math.min(1,
      (timelinePosition - timelineMin) / (timelineMax - timelineMin)));
    const x = rect.left + rect.width * proportion;
    const y = rect.top + rect.height / 2;
    if (!timelineIndicator) {
      timelineIndicator = document.createElement('div');
      timelineIndicator.setAttribute('data-ja-tv-seek-preview','true');
      timelineIndicator.setAttribute('aria-hidden','true');
      timelineIndicator.style.cssText = 'position:fixed;width:16px;height:16px;border:3px solid white;box-sizing:border-box;border-radius:50%;background:#ff9b20;box-shadow:0 0 0 3px #222,0 0 10px #000;transform:translate(-50%,-50%);z-index:2147483647;pointer-events:none;';
      timelineTip = document.createElement('div');
      timelineTip.style.cssText = 'position:absolute;bottom:24px;left:50%;transform:translateX(-50%);font:bold 14px sans-serif;white-space:nowrap;color:#fff;background:#111;padding:4px 8px;border:1px solid #fff;border-radius:4px;text-shadow:0 1px 2px #000;';
      timelineIndicator.appendChild(timelineTip);
      (document.body || document.documentElement).appendChild(timelineIndicator);
    }
    timelineIndicator.style.left = `${x}px`;
    timelineIndicator.style.top = `${y}px`;
    if (timelineTip) timelineTip.textContent = `Seek ${formatTimelineTime(timelinePosition)}`;
  }
  function enterTimelineMode() {
    if (!controlMode || openJwMenu()) return false;
    const video = currentVideo();
    const range = timelineRange(video);
    const track = timelineTrack();
    if (!range || !track) {
      send({event:'TIMELINE_UNAVAILABLE',
        reason:!range ? 'NO_FINITE_SEEKABLE_DURATION' : 'JW_TIMELINE_NOT_VISIBLE',
        duration:Number.isFinite(video?.duration)?Math.round(video.duration):null,
        trackCount:document.querySelectorAll('.jw-slider-time').length});
      return false;
    }
    restore();
    timelineMode=true;
    timelineMin=range[0]; timelineMax=range[1];
    timelinePosition=Math.max(timelineMin,Math.min(timelineMax,video.currentTime));
    timelineTarget=track;
    clearFocus();
    clearMenuFocus();
    renderTimelinePreview();
    send({event:'TIMELINE_OPEN',position:Math.round(timelinePosition),
      duration:Math.round(video.duration),start:Math.round(timelineMin),
      end:Math.round(timelineMax)});
    return true;
  }
  function moveTimeline(direction, large=false) {
    if (!timelineMode) return false;
    const duration = timelineMax - timelineMin;
    // One tap = 1% of available video (at least 10 seconds).
    // Up/Down = 5% of timeline for faster navigation.
    const amount=Math.min(duration,Math.max(10, duration * (large ? 0.05 : 0.01)));
    timelinePosition=Math.max(timelineMin,Math.min(timelineMax,
      timelinePosition + direction * amount));
    renderTimelinePreview();
    send({event:'TIMELINE_PREVIEW',position:Math.round(timelinePosition),
      percent:Math.round(100*(timelinePosition-timelineMin)/duration),
      stepSeconds:Math.round(amount)});
    return true;
  }
  function leaveTimelineMode(commit) {
    if (!timelineMode) return false;
    const preview=timelinePosition;
    const video=currentVideo();
    timelineMode=false;
    timelineTarget=null;
    removeTimelineIndicator();
    if (commit && video) {
      try {
        const target=clampSeekToRanges(video,preview);
        video.currentTime=target;
        send({event:'TIMELINE_SEEK_OK',requested:Math.round(preview),
          position:Math.round(video.currentTime),paused:video.paused});
      } catch(e) {
        send({event:'TIMELINE_SEEK_ERROR',error:e?.name || String(e)});
      }
    } else {
      send({event:'TIMELINE_CANCEL'});
    }
    if (controlMode) {
      const items=controlElements();
      if (items.length) select(items,Math.min(Math.max(0,selected),items.length-1));
    }
    return true;
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
    restore(); controlMode = true; menuSelection=-1; ccSelectMode=false; clearMenuFocus(); updateCenterVisibility();
    const items = controlElements();
    send({event:'CONTROLS_FOUND', count:items.length, labels:items.map(labelOf)});
    select(items, Math.min(Math.max(0, selected), items.length-1));
  }
  function closeMode() {
    if (timelineMode) leaveTimelineMode(false);
    controlMode = false; selected = -1; menuSelection=-1; ccSelectMode=false; clearMenuFocus(); clearFocus(); restore();
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
        if (timelineMode) leaveTimelineMode(false);
        // Return to playback; also close any open JW popover first.
        const close = openJwMenu()?.querySelector('.jw-menu-close, [aria-label*=close i]') || menuRoots().flatMap(root => Array.from(root.querySelectorAll('.jw-settings-close, [aria-label*=close i]'))).find(visible);
        if (close) { try { close.click(); } catch (_) {} }
        closeMode(); return;
      }
      if (action === 'controlsDown' || action === 'controlsUp') {
        if (timelineMode) { moveTimeline(action === 'controlsUp' ? 1 : -1, true); return; }
        if (!controlMode) openMode();
        // If no submenu is open, Up switches from toolbar to progress-line seeking.
        if (action === 'controlsUp' && !openJwMenu() && enterTimelineMode()) return;
        const direction = action === 'controlsDown' ? 1 : -1;
        if (changeLanguageSelect(direction)) return;
        const items=menuItems();
        if (items.length) {
          send({event:'MENU_FOUND',kind:menuKind(openJwMenu()),count:items.length,labels:items.slice(0,20).map(menuLabel)});
          selectMenu(direction, menuSelection < 0);
        } else { inspectMenus(action); inspectDeepMenu(action); send({event:'MENU_NOT_FOUND',action}); }
        return;
      }
      if (action === 'inspect') {
        const items = controlElements();
        send({event:'CONTROLS_FOUND', count:items.length, labels:items.map(labelOf)}); return;
      }
      if (action === 'controlsNext' || action === 'controlsPrev') {
        if (timelineMode) { moveTimeline(action === 'controlsNext' ? 1 : -1); return; }
        if (menuItems().length) {
          const dir = action === 'controlsNext' ? 1 : -1;
          if (!changeLanguageSelect(dir)) selectMenu(dir, menuSelection < 0);
          return;
        }
        if (!controlMode) openMode();
        else select(controlElements(), selected + (action === 'controlsNext' ? 1 : -1));
        return;
      }
      if (action === 'controlsSelect') {
        if (timelineMode) { leaveTimelineMode(true); return; }
        if (menuItems().length) { if (menuSelection < 0) selectMenu(0,true); else activateMenu(); return; }
        if (!controlMode) { openMode(); return; }
        const target = controlElements()[selected];
        if (!target) return send({event:'NO_CONTROLS'});
        // Click may be ignored by fullscreen/user-activation-restricted players.
        target.click();
        send({event:'CONTROL_ACTIVATED', label:labelOf(target)});
        setTimeout(() => inspectDeepMenu('afterControlsSelect'), 300);
        setTimeout(() => { const items=menuItems(); if (items.length) {
          menuSelection=-1; ccSelectMode=false; send({event:'MENU_FOUND',kind:menuKind(openJwMenu()),count:items.length,labels:items.slice(0,20).map(menuLabel)}); selectMenu(0,true);
        } },150);
        return;
      }
      if (action === 'toggle') {
        if (timelineMode) leaveTimelineMode(false);
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
