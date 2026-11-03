// JA v1.0.19 TEST: native JW Player timeline navigation without a separate seek overlay; preserve v1.0.18 startup defaults.
(() => {
  'use strict';
  if (globalThis.__jaTvRemote119) return;
  globalThis.__jaTvRemote119 = true;
  let port = null, attempts = 0, controlMode = false, selected = -1;
  let hidden = [], hideTimer = null, perfTimer = null;
  let menuSelection = -1;
  let ccSelectMode = false;
  let lastCcMenuId = "";
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
    // JA 1.0.16: ALSO expose the real JW speaker/mute button at bottom LEFT.
    // Previously controlElements() only searched the right half of the screen.
    // Keep all existing CC / Settings / Fullscreen candidates unchanged.
    const speakerButtons = [...document.querySelectorAll(
      'button, [role="button"], .jw-controlbar .jw-icon-volume, .jw-controlbar .jw-btn'
    )].filter(el => {
      if (!visible(el)) return false;
      const r=el.getBoundingClientRect();
      if (r.top < vh * .55 || r.bottom > vh * 1.02 || r.width < 12 || r.height < 12) return false;
      const name=labelOf(el);
      return /(?:^|\W)(?:mute|unmute|volume|speaker|sound)(?:$|\W)/i.test(name) ||
        /jw-icon-volume/i.test(String(el.className || ''));
    });
    return [...new Set([...speakerButtons, ...candidates])].sort((a,b) => {
      const ar=a.getBoundingClientRect(), br=b.getBoundingClientRect();
      return ar.left - br.left || ar.top - br.top;
    }).slice(0, 17);
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
    if (el.matches('.jw-tab, [role="tab"]')) return (el.textContent || el.getAttribute('aria-label') || 'Tab').trim().replace(/\s+/g,' ').slice(0,80);
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
  // JA v1.0.14: CC player layout uses .jw-menu.is-open, not the
  // old JW .jw-settings-menu. The language picker can be a SELECT,
  // a combobox, or a custom scrollable list of rows; none may use
  // .jw-row-label. Only inspect the currently OPEN menu.
  function ccItems(open) {
    const body = open.querySelector('.jw-menu-body') || open;
    const candidates = [];
    const add = el => {
      if (!el || !open.contains(el) || !menuVisible(el)) return;
      if (el.closest('.jw-menu-close, [aria-label="Close"]')) return;
      if (el.matches('input') && el.type === 'hidden') return;
      const r = el.getBoundingClientRect();
      // Ignore huge menu wrappers and tiny decorations, not offscreen rows
      // belonging to the menu's scroll area.
      if (r.width < 3 || r.height < 3 || r.height > 110) return;
      if (!candidates.includes(el)) candidates.push(el);
    };
    // Tabs at the top: 'Subtitles' / 'Options'. They are usable with
    // Up/Down as well as Left/Right, not merely decoration.
    for (const el of open.querySelectorAll('.jw-tab, [role="tab"], .jw-tabs button')) add(el);
    // The 'Off' row and track choices when present.
    for (const label of body.querySelectorAll('.jw-row-label')) add(menuTargetForLabel(label, open));
    for (const el of body.querySelectorAll('.jw-row, [role="option"], [role="menuitem"], [role="menuitemradio"], [role="radio"]')) add(el);
    // Search and native/custom language picker. Native OPTIONs have zero
    // rectangles; the corresponding SELECT is what we navigate.
    for (const el of body.querySelectorAll('input:not([type="hidden"]), select, [role="combobox"], [role="searchbox"], button, [aria-haspopup="listbox"], [class*="dropdown" i], [class*="language-select" i]')) add(el);
    // Some custom subtitle lists don't expose button/role/row classes.
    // Include compact, visibly clickable leaf elements inside the open CC
    // menu; clicking them is deferred until OK (never done during scan).
    for (const el of body.querySelectorAll('div, span, li, label')) {
      if (!menuVisible(el)) continue;
      const r = el.getBoundingClientRect();
      if (r.height > 60 || r.height < 10 || r.width < 25) continue;
      const label = (el.innerText || el.textContent || '').trim().replace(/\s+/g, ' ');
      if (!label || label.length > 65) continue;
      const cls = String(el.className || '');
      const st = getComputedStyle(el);
      if (st.cursor !== 'pointer' && !/(lang|subtitle|caption|track|option|item|entry|row|select|choice)/i.test(cls)) continue;
      const target = el.closest('button, [role="option"], [role="menuitem"], .jw-row, li') || el;
      add(target);
    }
    // Avoid wrapper + child duplicate entries. Prefer the smallest
    // direct interactive target where the same row is matched twice.
    const unique = [...new Set(candidates)];
    const filtered = unique.filter(el => !unique.some(other => {
      if (el === other || !el.contains(other)) return false;
      // Preserve a SELECT/INPUT even if it contains decoration children.
      if (el.matches('select, input, button, [role="tab"]')) return false;
      const a = el.getBoundingClientRect(), b = other.getBoundingClientRect();
      return Math.abs(a.top - b.top) <= 5 && Math.abs(a.height - b.height) <= 5;
    }));
    return filtered.sort((a,b) => {
      const ar = a.getBoundingClientRect(), br = b.getBoundingClientRect();
      return ar.top - br.top || ar.left - br.left;
    }).slice(0, 100);
  }
  function ccMenuMap(reason) {
    const open = openJwMenu();
    if (!open || menuKind(open) !== 'CC') return;
    const items = ccItems(open);
    const tabs = [...open.querySelectorAll('.jw-tab, [role="tab"], .jw-tabs button')]
      .filter(menuVisible).map(el => ({label: menuLabel(el), active: el.classList.contains('active') || el.getAttribute('aria-selected') === 'true'}));
    const selects = [...open.querySelectorAll('select')].slice(0,6).map(el => ({
      visible: menuVisible(el), selected: el.selectedIndex, count: el.options.length,
      options: [...el.options].slice(0,35).map(opt => (opt.textContent || '').trim().slice(0,45))
    }));
    const parts = [...open.querySelectorAll('input, [role="combobox"], [aria-haspopup="listbox"]')]
      .slice(0,10).map(el => ({tag:el.tagName.toLowerCase(), cls:String(el.className).slice(0,70),
        role:el.getAttribute('role')||'', visible:menuVisible(el),
        placeholder:el.getAttribute('placeholder')||''}));
    const key = JSON.stringify([tabs, selects, items.map(menuLabel)]);
    if (key === lastCcMenuId && reason === 'navigate') return;
    lastCcMenuId = key;
    send({event:'CC_MENU_MAP', reason, tabs, selects, inputs:parts,
      count:items.length, items:items.slice(0,35).map(el => ({label:menuLabel(el),tag:el.tagName.toLowerCase(),cls:String(el.className).slice(0,65)}))});
  }
  function switchCcTab(delta) {
    const open = openJwMenu();
    if (!open || menuKind(open) !== 'CC') return false;
    const tabs = [...open.querySelectorAll('.jw-tab, [role="tab"], .jw-tabs button')].filter(menuVisible);
    if (tabs.length < 2) return false;
    const active = tabs.findIndex(el => el.classList.contains('active') || el.getAttribute('aria-selected') === 'true');
    const next = (Math.max(0,active) + delta + tabs.length) % tabs.length;
    try { tabs[next].click(); } catch (e) { send({event:'CC_TAB_ERROR',error:e?.name||String(e)}); return true; }
    menuSelection = -1; ccSelectMode = false; clearMenuFocus();
    send({event:'CC_TAB_SELECTED', index:next, total:tabs.length, label:menuLabel(tabs[next])});
    setTimeout(() => { ccMenuMap('tabChange'); selectMenu(0,true); }, 120);
    return true;
  }
  function menuItems() {
    const open = openJwMenu();
    if (open) {
      if (menuKind(open) === 'CC') return ccItems(open);
      // Leave verified Settings quality navigation behavior unchanged.
      const body = open.querySelector('.jw-menu-body') || open;
      const found = [];
      for (const label of body.querySelectorAll('.jw-row-label')) {
        if (menuVisible(label)) found.push(menuTargetForLabel(label, open));
      }
      const unique = [...new Set(found)].filter(menuVisible);
      return unique.filter(el => !unique.some(other => other !== el && other.contains(el)))
        .sort((a,b) => { const ar=a.getBoundingClientRect(),br=b.getBoundingClientRect();return ar.top-br.top||ar.left-br.left; }).slice(0,80);
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
    if (menuKind(openJwMenu()) === 'CC') ccMenuMap('navigate');
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
    send({event:'CC_LANGUAGE_FILTER_SELECTED', label:menuLabel(el), selectedIndex:index, optionCount:options.length});
    setTimeout(() => ccMenuMap('selectChanged'), 160);
    return true;
  }
  function activateMenu() {
    const items = menuItems();
    const item = items[menuSelection];
    if (!item) { inspectMenus('activateMenu'); inspectDeepMenu('activateMenu'); return send({event:'MENU_NOT_FOUND'}); }
    const kind = menuKind(openJwMenu());
    const label = menuLabel(item);
    if (kind === 'CC' && item.matches('.jw-tab, [role="tab"]')) {
      try { item.click(); } catch(e) { send({event:'CC_TAB_ERROR',error:e?.name||String(e)}); return; }
      menuSelection=-1; clearMenuFocus();
      send({event:'CC_TAB_ACTIVATED',label});
      setTimeout(()=> { ccMenuMap('tabActivated'); selectMenu(0,true); },120);
      return;
    }
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
    if (kind === 'CC') send({event:'CC_TRACK_ACTIVATED', label});
    // A menu can remain open after a choice, or close and return to toolbar.
    setTimeout(() => {
      const next = menuItems();
      const menu = openJwMenu();
      if (menu && next.length) {
        send({event:'MENU_FOUND', kind:menuKind(menu), count:next.length, labels:next.slice(0,20).map(menuLabel)});
        if (menuKind(menu) === 'CC') ccMenuMap('afterSelection');
        selectMenu(0,true);
      } else if (!controlMode) hideWhenPlaying();
    }, 120);
  }
  // JA v1.0.19: use ONLY the existing JW Player progress rail.
  // The earlier custom fallback seek bar is deliberately removed.
  // Arrow keys LIVE-SEEK the HTML5 video, so the player's real progress
  // indicator updates naturally without injecting a second timeline.
  let timelineMode = false;
  let timelineTarget = null;
  let timelineOriginalOutline = null;
  let timelinePosition = 0;
  let timelineMin = 0;
  let timelineMax = 0;
  const TIMELINE_TAG = 'data-ja-tv-native-timeline-focus';

  function describeTimeline(el) {
    const r = el.getBoundingClientRect();
    return {tag:el.tagName.toLowerCase(), cls:String(el.className?.baseVal || el.className || '').slice(0,90),
      role:el.getAttribute('role') || '', aria:el.getAttribute('aria-label') || '',
      rect:[Math.round(r.left),Math.round(r.top),Math.round(r.width),Math.round(r.height)]};
  }

  function timelineCandidates() {
    const video = currentVideo();
    if (!video) return [];
    const root = video.closest('.jwplayer, .jw-wrapper, [class*="jw-player" i]') || document;
    const vw = innerWidth || document.documentElement.clientWidth;
    const vh = innerHeight || document.documentElement.clientHeight;
    const selectors = [
      '.jw-slider-time', '.jw-slider-time .jw-slider-container',
      '.jw-slider-horizontal', '.jw-slider-container',
      '.jw-progressbar', '.jw-progress', '.jw-rail',
      '[role="slider"]', 'input[type="range"]',
      '[class*="seek" i]', '[class*="timeline" i]',
      '[class*="progress" i]', '[class*="scrub" i]',
      '[class*="timebar" i]', '[class*="track" i]',
      '[class*="buffer" i]', '[class*="rail" i]'
    ].join(',');
    const all = [...root.querySelectorAll(selectors)];
    // Custom JW skins may draw the long progress rail with plain div/span
    // elements. Inspect those only inside the actual video-player container.
    if (root !== document) {
      for (const el of root.querySelectorAll('div, span')) {
        const r=el.getBoundingClientRect();
        if (r.height >= 1 && r.height <= 18 && r.width >= vw*.55 &&
            r.top >= vh*.52 && r.bottom <= vh*.98) all.push(el);
      }
    }
    // When the video is nested in an unusual host, also search the frame.
    if (root !== document) all.push(...document.querySelectorAll(selectors));
    const result = [];
    for (const el of new Set(all)) {
      if (el.tagName === 'VIDEO' || el.contains(video) || !visible(el)) continue;
      if (el.closest('.jw-menu, [role="dialog"], [data-ja-tv-timeline-fallback]')) continue;
      const r = el.getBoundingClientRect();
      // This is the actual long horizontal rail seen below the picture,
      // not one of the four small control-bar buttons.
      if (r.width < Math.max(130, vw * .38) || r.height < 1 || r.height > 65 ||
          r.top < vh * .48 || r.bottom > vh * 1.02) continue;
      const cls = String(el.className?.baseVal || el.className || '');
      const aria = (el.getAttribute('aria-label') || '').toLowerCase();
      const exact = /jw-slider-time|jw-slider-container|jw-progressbar|jw-rail|jw-progress/i.test(cls);
      const semantic = /seek|progress|timeline|timebar|scrub|slider/i.test(cls + ' ' + aria);
      const withinPlayer = Boolean(el.closest('.jwplayer, .jw-controlbar, [class*="player" i]'));
      // Penalize large unrelated page decorations while favoring a genuine
      // slider or a long rail immediately above the bottom controls.
      const score = (exact ? 100 : 0) + (semantic ? 25 : 0) + (withinPlayer ? 20 : 0) +
        (el.matches('[role="slider"], input[type="range"]') ? 30 : 0) +
        (r.width / Math.max(1,vw)) * 10 - r.height * .09 + (r.top/vh) * 5;
      result.push({el,score});
    }
    result.sort((a,b) => b.score - a.score);
    return result.slice(0,20);
  }

  function nativeTimeline() {
    // Do not pretend an overlay line is JW Player's own progress bar.
    return timelineCandidates()[0]?.el || null;
  }

  function timelineRange(video) {
    if (!video || !Number.isFinite(video.duration) || video.duration <= 0) return null;
    const seek = video.seekable;
    if (!seek || !seek.length) return null;
    const start = Math.max(0, seek.start(0));
    const end = Math.min(video.duration, seek.end(seek.length - 1));
    return Number.isFinite(start) && Number.isFinite(end) && end > start
      ? [start,end] : null;
  }

  function clampSeekToRanges(video, target) {
    const ranges=video.seekable;
    if (!ranges || !ranges.length) return target;
    let closest=target, distance=Infinity;
    for (let i=0;i<ranges.length;i++) {
      const point=Math.min(ranges.end(i),Math.max(ranges.start(i),target));
      if (Math.abs(point-target)<distance) {closest=point; distance=Math.abs(point-target);}
    }
    return closest;
  }

  function clearTimelineFocus() {
    if (timelineTarget) {
      try {
        timelineTarget.removeAttribute(TIMELINE_TAG);
        if (timelineOriginalOutline) {
          timelineTarget.style.setProperty('outline',timelineOriginalOutline.outline.value,timelineOriginalOutline.outline.priority);
          timelineTarget.style.setProperty('outline-offset',timelineOriginalOutline.offset.value,timelineOriginalOutline.offset.priority);
        } else {
          timelineTarget.style.removeProperty('outline');
          timelineTarget.style.removeProperty('outline-offset');
        }
      } catch (_) {}
    }
    timelineTarget=null;
    timelineOriginalOutline=null;
  }

  function showNativeTimelineFocus(el) {
    if (!el) return;
    if (timelineTarget !== el) {
      clearTimelineFocus();
      timelineTarget=el;
      timelineOriginalOutline={
        outline:{value:el.style.getPropertyValue('outline'),priority:el.style.getPropertyPriority('outline')},
        offset:{value:el.style.getPropertyValue('outline-offset'),priority:el.style.getPropertyPriority('outline-offset')}
      };
      el.setAttribute(TIMELINE_TAG,'true');
      el.style.setProperty('outline','3px solid white','important');
      el.style.setProperty('outline-offset','5px','important');
    }
  }

  function enterTimelineMode() {
    if (!controlMode || openJwMenu()) return false;
    const video=currentVideo();
    if (!video) return false;
    restore();
    revealToolbar('timelineOpen');
    const range=timelineRange(video);
    const candidates=timelineCandidates();
    const track=candidates[0]?.el || null;
    send({event:'NATIVE_TIMELINE_SCAN',matches:candidates.length,
      candidates:candidates.slice(0,8).map(x=>describeTimeline(x.el))});
    if (!range || !track) {
      send({event:'TIMELINE_NATIVE_NOT_FOUND',
        reason:!range ? 'NO_SEEKABLE_VIDEO' : 'NO_NATIVE_PROGRESS_RAIL',
        duration:Number.isFinite(video.duration) ? Math.round(video.duration) : null,
        seekableRanges:video.seekable?.length || 0});
      return false; // No synthetic/fallback progress bar is created.
    }
    timelineMode=true;
    timelineMin=range[0]; timelineMax=range[1];
    timelinePosition=Math.max(timelineMin,Math.min(timelineMax,video.currentTime));
    clearFocus();clearMenuFocus();
    showNativeTimelineFocus(track);
    updateCenterVisibility();
    send({event:'TIMELINE_OPEN',display:'NATIVE_PLAYER_BAR',
      position:Math.round(timelinePosition),end:Math.round(timelineMax),track:describeTimeline(track)});
    return true;
  }

  function moveTimeline(direction,large=false) {
    if (!timelineMode) return false;
    const video=currentVideo();
    if (!video) {leaveTimelineMode(false);return false;}
    const duration=timelineMax-timelineMin;
    const amount=Math.min(duration,Math.max(10,duration*(large ? .05 : .01)));
    const target=clampSeekToRanges(video,Math.min(timelineMax,Math.max(timelineMin,video.currentTime+direction*amount)));
    try {
      video.currentTime=target; // JW Player's OWN timeline advances with the video.
      timelinePosition=video.currentTime;
      const nextTrack=nativeTimeline();
      if (nextTrack) showNativeTimelineFocus(nextTrack);
      else send({event:'TIMELINE_RAIL_LOST'});
      send({event:'TIMELINE_NATIVE_SEEK',position:Math.round(timelinePosition),
        percent:Math.round(100*(timelinePosition-timelineMin)/duration),stepSeconds:Math.round(amount)});
    } catch(e) {send({event:'TIMELINE_SEEK_ERROR',error:e?.name||String(e)});}
    return true;
  }

  function leaveTimelineMode(commit) {
    if (!timelineMode) return false;
    timelineMode=false;
    clearTimelineFocus();
    // Live seeking is already applied. OK/Back both exit timeline mode;
    // unlike the old preview overlay there is no separate commit step.
    send({event:'TIMELINE_EXIT',position:Math.round(timelinePosition),action:commit?'OK':'BACK'});
    if (controlMode) {
      const items=controlElements();
      if (items.length) select(items,Math.min(Math.max(0,selected),items.length-1));
    }
    return true;
  }

  // Keep center display controls hidden while playing. A stylesheet survives
  // JW Player rerendering the controls (unlike one-time inline visibility).
  const CENTER_HIDE_ATTR='data-ja-tv-hide-center';
  const CONTROLS_SHOW_ATTR='data-ja-tv-show-toolbar';
  // In control mode, explicitly reveal JW Player's real toolbar. This is
  // temporary and does not change autoplay, volume, quality, or subtitles.
  const toolbarStyle=document.createElement('style');
  toolbarStyle.textContent=`html[${CONTROLS_SHOW_ATTR}="true"] .jw-controlbar,
    html[${CONTROLS_SHOW_ATTR}="true"] .jwplayer .jw-controls,
    html[${CONTROLS_SHOW_ATTR}="true"] .jw-bottom,
    html[${CONTROLS_SHOW_ATTR}="true"] .jw-controlbar .jw-button-container {
      opacity: 1 !important;
      visibility: visible !important;
      pointer-events: auto !important;
      transform: none !important;
    }`;
  (document.head || document.documentElement).appendChild(toolbarStyle);
  function revealToolbar(reason) {
    document.documentElement.setAttribute(CONTROLS_SHOW_ATTR,'true');
    const bars=[...document.querySelectorAll('.jw-controlbar, .jw-bottom')];
    send({event:'CONTROL_BAR_REVEAL_REQUESTED',reason,found:bars.length,
      candidates:controlElements().length});
  }
  const centerStyle=document.createElement('style');
  centerStyle.textContent=`html[${CENTER_HIDE_ATTR}="true"] .jw-display-controls,
    html[${CENTER_HIDE_ATTR}="true"] .jw-display-icon-container {
      opacity: 0 !important; visibility: hidden !important;
      pointer-events: none !important;
    }`;
  (document.head || document.documentElement).appendChild(centerStyle);
  function updateCenterVisibility() {
    const v=currentVideo();
    // The central play/skip graphics should stay hidden during playback,
    // including while navigating the separate bottom toolbar.
    if (v && !v.paused) document.documentElement.setAttribute(CENTER_HIDE_ATTR,'true');
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
    restore(); controlMode = true; menuSelection=-1; ccSelectMode=false; clearMenuFocus();
    revealToolbar('controlsOpen'); updateCenterVisibility();
    const items = controlElements();
    send({event:'CONTROLS_FOUND', count:items.length, labels:items.map(labelOf)});
    // v1.0.16 added the left-hand speaker as the first item. Don't focus
    // Mute automatically on entering controls; start on CC if available.
    const preferred=items.findIndex(el => /subtitles|captions/i.test(labelOf(el)));
    selected=-1;
    select(items, preferred >= 0 ? preferred : 0);
  }
  function closeMode() {
    if (timelineMode) leaveTimelineMode(false);
    controlMode = false; selected = -1; menuSelection=-1; ccSelectMode=false;
    document.documentElement.removeAttribute(CONTROLS_SHOW_ATTR);
    clearMenuFocus(); clearFocus(); restore();
    send({event:'CONTROL_MODE_CLOSED'});
    hideWhenPlaying();
    updateCenterVisibility();
  }
  async function handle(message) {
    const action = message?.action;
    if (action && /^controls(?:Next|Prev|Select|Down|Up|Close)$/.test(action)) defaultsUserMenuTouched = true;
    if (!['toggle','back10','forward10','controlsOpen','controlsNext','controlsPrev','controlsSelect','controlsClose','controlsUp','controlsDown','inspect'].includes(action)) return;
    const video = currentVideo();
    if (!video) { send({event:'NO_VIDEO', action, count:videos().length}); return; }
    try {
      if (action === 'controlsOpen') { defaultsUserMenuTouched = true; openMode(); return; }
      if (action === 'controlsClose') {
        if (timelineMode) leaveTimelineMode(false);
        // Return to playback; also close any open JW popover first.
        const close = openJwMenu()?.querySelector('.jw-menu-close, [aria-label*=close i]') || menuRoots().flatMap(root => Array.from(root.querySelectorAll('.jw-settings-close, [aria-label*=close i]'))).find(visible);
        if (close) { try { close.click(); } catch (_) {} }
        closeMode(); return;
      }
      if (action === 'controlsDown' || action === 'controlsUp') {
        if (timelineMode) { moveTimeline(action === 'controlsUp' ? 1 : -1, true); return; }
        if (!controlMode) { openMode(); return; }
        // Down always restores the toolbar while no submenu is open. Without
        // this, the browser may fade controls but native mode stays active.
        if (action === 'controlsDown' && !openJwMenu()) {
          restore();
          revealToolbar('controlsDown');
          updateCenterVisibility();
          return;
        }
        // If no submenu is open, Up switches from toolbar to progress-line seeking.
        if (action === 'controlsUp' && !openJwMenu()) { enterTimelineMode(); return; }
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
          if (!changeLanguageSelect(dir) && !switchCcTab(dir)) selectMenu(dir, menuSelection < 0);
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
          menuSelection=-1; ccSelectMode=false; send({event:'MENU_FOUND',kind:menuKind(openJwMenu()),count:items.length,labels:items.slice(0,20).map(menuLabel)}); if (menuKind(openJwMenu()) === 'CC') ccMenuMap('opened'); selectMenu(0,true);
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
  // JA v1.0.16: sequential startup configuration, NOT another autoplay engine.
  // The previous script clicked a quality option in a closed menu, then stopped
  // all work whenever that click opened Settings. CC Off therefore never ran.
  // Work in order: quality -> close Settings -> open CC -> Off -> close CC -> audio.
  // Do not touch playback, change video source, or change user-selected values
  // after the startup sequence finishes.
  let defaultsUserMenuTouched = false;
  let defaultsTimer = null;
  let defaultsChecks = 0;
  let startupPhase = 'quality';
  let startupSince = Date.now();
  let phaseSince = 0;
  let qualityRequested = false;
  // A click is a request, not proof that JW Player changed quality.
  let qualityVerified = false;
  let qualityTarget = '';
  let qualityAttempts = 0;
  const MAX_QUALITY_ATTEMPTS = 3;
  let ccRequested = false;
  let audioChecked = false;
  let startupCcOpenAttempts = 0;
  let startupClosedMenus = 0;

  function startupLog(event, extra={}) { send({event,phase:startupPhase,...extra}); }
  const startupMenu = kind => [...document.querySelectorAll('.jw-menu')]
    .find(menu => menuKind(menu) === kind) || null;

  function startupCloseMenu(kind) {
    const menu = openJwMenu();
    if (!menu) return true;
    if (kind && menuKind(menu) !== kind) {
      startupLog('DEFAULT_MENU_MISMATCH', {expected:kind, actual:menuKind(menu)});
      return false;
    }
    const close = menu.querySelector('.jw-menu-close, .jw-tabs-close, button[aria-label*="close" i]');
    if (!close) {
      startupLog('DEFAULT_MENU_CLOSE_MISSING',{kind:menuKind(menu)});
      return false;
    }
    close.click();
    startupClosedMenus++;
    startupLog('DEFAULT_MENU_CLOSE_REQUESTED',{kind:menuKind(menu)});
    return true;
  }

  // Inspect the actual quality-selection state, not the remote's highlight.
  // JW builds differ; if no selected marker exists, report UNVERIFIED rather
  // than falsely claiming that the highest resolution was applied.
  function startupQualityState() {
    const menu = startupMenu('SETTINGS');
    if (!menu) return {selected:'', evidence:'NO_SETTINGS_MENU', available:[]};
    const choices = [...menu.querySelectorAll('.jw-row-label')].map(labelEl => {
      const label=(labelEl.textContent||'').trim();
      if (!/^(?:auto|\d{3,4}\s*p)$/i.test(label)) return null;
      const row=menuTargetForLabel(labelEl,menu);
      if (!row) return null;
      const markers=[];
      // Selection is not the white JA remote-focus outline.
      if (/(?:^|\s)(?:active|selected|checked|current|jw-active)(?:\s|$)/i.test(String(row.className||''))) markers.push('ROW_CLASS');
      if (row.getAttribute('aria-selected')==='true') markers.push('ARIA_SELECTED');
      if (row.getAttribute('aria-checked')==='true') markers.push('ARIA_CHECKED');
      if (row.querySelector('input:checked, [aria-selected="true"], [aria-checked="true"]')) markers.push('CHECKED_CHILD');
      return {label, row, markers};
    }).filter(Boolean);
    const active=choices.filter(c=>c.markers.length);
    return {
      selected: active.length===1 ? active[0].label : '',
      evidence: active.length===1 ? active[0].markers.join('+') :
        active.length>1 ? 'AMBIGUOUS_SELECTION' : 'NO_SELECTED_MARKER',
      available:choices.map(c=>c.label)
    };
  }

  function startupRequestHighestQuality() {
    const menu = startupMenu('SETTINGS');
    if (!menu) return false;
    const choices = [...menu.querySelectorAll('.jw-row-label')].map(row => {
      const label=(row.textContent||'').trim();
      const match=/^(\d{3,4})\s*p$/i.exec(label);
      return match ? {label,height:Number(match[1]),row} : null;
    }).filter(Boolean).sort((a,b) => b.height - a.height);
    if (!choices.length) return false;
    const choice=choices[0];
    qualityTarget=choice.label;
    const before=startupQualityState();
    if (before.selected===qualityTarget) {
      qualityVerified=true;
      qualityRequested=true;
      startupLog('DEFAULT_QUALITY_ALREADY_SELECTED', {
        quality:qualityTarget,evidence:before.evidence,attempt:qualityAttempts
      });
      return true;
    }
    if (qualityAttempts>=MAX_QUALITY_ATTEMPTS) return false;
    qualityAttempts++;
    try {
      menuTargetForLabel(choice.row,menu).click();
      qualityRequested=true;
      startupLog('DEFAULT_QUALITY_REQUESTED', {
        quality:choice.label,available:choices.map(c=>c.label),
        previousSelection:before.selected||'UNKNOWN',previousEvidence:before.evidence,
        attempt:qualityAttempts,maxAttempts:MAX_QUALITY_ATTEMPTS,
        menuOpen:!!openJwMenu()
      });
      return true;
    } catch(e) {
      startupLog('DEFAULT_QUALITY_ERROR',{attempt:qualityAttempts,error:e?.name||String(e)});
      return false;
    }
  }

  function startupCheckQuality() {
    const state=startupQualityState();
    if (qualityTarget && state.selected===qualityTarget) {
      // Must remain selected across a second observation before verification.
      return {ok:true,state};
    }
    startupLog(state.selected ? 'DEFAULT_QUALITY_RETRY' : 'DEFAULT_QUALITY_UNVERIFIED', {
      target:qualityTarget,selected:state.selected||'UNKNOWN',
      evidence:state.evidence,attempt:qualityAttempts,maxAttempts:MAX_QUALITY_ATTEMPTS
    });
    return {ok:false,state};
  }

  function disableTextTracks(video) {
    let count = 0;
    try {
      for (const track of Array.from(video?.textTracks || [])) {
        if (track.mode !== 'disabled') {
          track.mode = 'disabled';
          count++;
        }
      }
    } catch (_) {}
    return count;
  }

  function startupRequestCcOff(video) {
    const menu = openJwMenu();
    if (!menu || menuKind(menu) !== 'CC') return false;
    const row = [...menu.querySelectorAll('.jw-row-label')]
      .find(el => /^off$/i.test((el.textContent || '').trim()));
    if (!row) {
      startupLog('DEFAULT_CC_OFF_NOT_FOUND');
      return false;
    }
    try {
      // This is the same actionable .jw-row target verified by MANUAL CC Off.
      menuTargetForLabel(row,menu).click();
      ccRequested=true;
      const tracksDisabled=disableTextTracks(video);
      startupLog('DEFAULT_SUBTITLES_OFF_REQUESTED',{tracksDisabled});
      return true;
    } catch(e) {
      startupLog('DEFAULT_SUBTITLES_ERROR',{error:e?.name||String(e)});
      return false;
    }
  }

  function startupRequestUnmute(video) {
    // Only unmute once the video actually starts; do not interfere with muted
    // Auto-Start before GeckoView has begun playback. Never toggle blindly.
    if (!video || video.paused || video.readyState < 2) return false;
    const wasMuted = video.muted || video.volume === 0;
    if (!wasMuted) {
      startupLog('DEFAULT_AUDIO_ALREADY_UNMUTED');
      return true;
    }
    const previousMuted=video.muted, previousVolume=video.volume;
    try {
      if (video.volume === 0) video.volume = 1;
      video.muted = false;
      startupLog('DEFAULT_AUDIO_UNMUTE_REQUESTED',{
        wasMuted:previousMuted, previousVolume,
        nowMuted:video.muted, nowVolume:video.volume
      });
      // Some browser autoplay policies pause a movie when it is unmuted.
      // Protect the already-working Auto-Start rather than leaving a frozen
      // player: if that happens, restore mute and attempt normal muted resume.
      setTimeout(() => {
        if (!video.isConnected || defaultsUserMenuTouched) return;
        if (video.paused && !video.ended) {
          video.muted = previousMuted;
          video.volume = previousVolume;
          try { video.play()?.catch?.(() => {}); } catch (_) {}
          startupLog('DEFAULT_AUDIO_UNMUTE_ROLLED_BACK',{reason:'PLAYBACK_PAUSED'});
        }
      }, 1100);
      return true;
    } catch(e) {
      startupLog('DEFAULT_AUDIO_ERROR',{error:e?.name||String(e)});
      return true;
    }
  }

  function finishStartupPreferences(complete, reason='') {
    if (defaultsTimer) clearInterval(defaultsTimer);
    defaultsTimer = null;
    startupLog(complete ? 'STARTUP_DEFAULTS_COMPLETE' : 'STARTUP_DEFAULTS_INCOMPLETE',{
      reason, qualityRequested, qualityVerified, qualityTarget,
      qualityAttempts, ccRequested, audioChecked, startupClosedMenus
    });
  }

  function advanceStartup(phase) {
    startupPhase=phase;
    phaseSince=Date.now();
  }

  function checkStartupPreferences() {
    if (defaultsUserMenuTouched || controlMode) {
      finishStartupPreferences(false,'REMOTE_MENU_USED');
      return;
    }
    if (++defaultsChecks > 100) {
      // Avoid leaving an automatically opened popup after a timeout.
      const open = openJwMenu();
      if (open && ['SETTINGS','CC'].includes(menuKind(open))) startupCloseMenu(menuKind(open));
      finishStartupPreferences(false,'TIMEOUT');
      return;
    }
    const video=currentVideo();
    if (!video || video.readyState < 1) return;
    const open = openJwMenu();
    const now=Date.now();
    switch(startupPhase) {
      case 'quality':
        // Never interfere with a user-opened menu. The startup routine only
        // handles the initial automatic selection, not later manual choices.
        if (open) return;
        if (startupRequestHighestQuality()) {
          advanceStartup(qualityVerified ? 'qualityClose' : 'qualitySettle');
          return;
        }
        if (now-startupSince > 12000) {
          startupLog('DEFAULT_QUALITY_FAILED',{reason:'NO_FIXED_QUALITY_OPTIONS'});
          advanceStartup('ccOpen');
        }
        return;
      case 'qualitySettle': {
        if (now-phaseSince < 1200) return;
        const check=startupCheckQuality();
        if (check.ok) {advanceStartup('qualityConfirm');return;}
        if (qualityAttempts<MAX_QUALITY_ATTEMPTS) {
          advanceStartup('qualityRetry');
        } else {
          startupLog('DEFAULT_QUALITY_FAILED', {
            reason:check.state.selected?'SELECTION_NOT_APPLIED':'SELECTION_UNVERIFIABLE',
            target:qualityTarget,selected:check.state.selected||'UNKNOWN',
            attempts:qualityAttempts
          });
          advanceStartup('qualityClose');
        }
        return;
      }
      case 'qualityConfirm': {
        // Prevent a transient active marker from being treated as success.
        if (now-phaseSince < 900) return;
        const check=startupCheckQuality();
        if (check.ok) {
          qualityVerified=true;
          startupLog('DEFAULT_QUALITY_VERIFIED',{
            quality:qualityTarget,evidence:check.state.evidence,
            attempts:qualityAttempts
          });
          advanceStartup('qualityClose');
        } else if (qualityAttempts<MAX_QUALITY_ATTEMPTS) {
          advanceStartup('qualityRetry');
        } else {
          startupLog('DEFAULT_QUALITY_FAILED', {
            reason:check.state.selected?'SELECTION_REVERTED':'SELECTION_UNVERIFIABLE',
            target:qualityTarget,selected:check.state.selected||'UNKNOWN',
            attempts:qualityAttempts
          });
          advanceStartup('qualityClose');
        }
        return;
      }
      case 'qualityRetry':
        if (now-phaseSince < 850) return;
        if (startupRequestHighestQuality()) {
          advanceStartup(qualityVerified ? 'qualityClose' : 'qualitySettle');
        } else {
          startupLog('DEFAULT_QUALITY_FAILED',{reason:'RETRY_UNAVAILABLE',target:qualityTarget,attempts:qualityAttempts});
          advanceStartup('qualityClose');
        }
        return;
      case 'qualityClose':
        if (open && menuKind(open)==='SETTINGS') {
          startupCloseMenu('SETTINGS');
          return;
        }
        if (open && now-phaseSince < 2800) return;
        advanceStartup('ccOpen');
        return;
      case 'ccOpen': {
        if (open && menuKind(open)==='CC') {advanceStartup('ccOff');return;}
        if (open) { startupCloseMenu(menuKind(open)); return; }
        const cc = [...document.querySelectorAll('button, [role="button"]')]
          .find(el => visible(el) && /subtitles|captions/i.test(
            el.getAttribute('aria-label') || el.getAttribute('title') || ''));
        if (!cc) {
          if (now-phaseSince > 5000) advanceStartup('audio');
          return;
        }
        if (startupCcOpenAttempts >= 3) {advanceStartup('audio');return;}
        cc.click();
        startupCcOpenAttempts++;
        startupLog('DEFAULT_CC_MENU_OPEN_REQUESTED',{attempt:startupCcOpenAttempts});
        advanceStartup('ccWait');
        return;
      }
      case 'ccWait':
        if (open && menuKind(open)==='CC') {advanceStartup('ccOff');return;}
        if (now-phaseSince > 1800) advanceStartup('ccOpen');
        return;
      case 'ccOff':
        if (startupRequestCcOff(video)) {advanceStartup('ccSettle');return;}
        if (now-phaseSince > 2200) advanceStartup('ccClose');
        return;
      case 'ccSettle':
        if (now-phaseSince < 400) return;
        // JW can replace caption tracks after row activation; reapply Off to
        // currently exposed HTML5 tracks once after the click has settled.
        disableTextTracks(video);
        advanceStartup('ccClose');
        return;
      case 'ccClose':
        if (open && menuKind(open)==='CC') { startupCloseMenu('CC'); return; }
        if (open && now-phaseSince < 2500) return;
        advanceStartup('audio');
        if (!controlMode && !video.paused) hideWhenPlaying(400);
        return;
      case 'audio':
        if (startupRequestUnmute(video)) {
          audioChecked=true;
          advanceStartup('done');
          return;
        }
        if (now-phaseSince > 5500) {
          startupLog('DEFAULT_AUDIO_SKIPPED',{reason:'VIDEO_NOT_PLAYING'});
          advanceStartup('done');
        }
        return;
      case 'done':
        finishStartupPreferences(qualityVerified && ccRequested,
          qualityVerified && ccRequested ? '' : 'SOME_PREFERENCES_UNAVAILABLE_OR_UNVERIFIED');
        return;
    }
  }

  function startPlaybackDefaults() {
    if (defaultsTimer) return;
    startupSince=Date.now();
    phaseSince=startupSince;
    defaultsTimer=setInterval(checkStartupPreferences,500);
    checkStartupPreferences();
  }

  function connect() {
    if (port || attempts++ >= 25) return;
    try {
      const candidate = browser.runtime.connectNative('ja_tv_remote');
      candidate.onMessage.addListener(handle);
      candidate.onDisconnect.addListener(() => { port=null; if (attempts < 25) setTimeout(connect,1000); });
      port=candidate;
      send({event:'FRAME_READY', count:videos().length, playing:!!currentVideo() && !currentVideo().paused});
      startPlaybackDefaults();
    } catch (_) { setTimeout(connect,1000); }
  }
  connect();
})();
