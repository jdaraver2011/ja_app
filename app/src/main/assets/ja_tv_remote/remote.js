// JA v1.1.5ax: preserve VidFast playback UI and VidSrc/JW remote controls.
// Change only native subtitle defaults on new videos and read-only playback
// frame statistics. Never click VidFast's custom controls or alter playback.
(() => {
  'use strict';
  if (globalThis.__jaTvRemote122) return;
  globalThis.__jaTvRemote122 = true;
  let port = null, attempts = 0, controlMode = false, selected = -1;
  let hidden = [], hideTimer = null, perfTimer = null;
  // Apply subtitle OFF to each new HTML5 video at startup. Stop enforcing
  // shortly after playback begins so the viewer can turn subtitles on again.
  // Native text tracks are independent of JW/VidFast player control layouts.
  const configuredSubtitleVideos = new WeakSet();
  function initializeSubtitleDefaults() {
    let videos;
    try { videos = document.querySelectorAll('video'); } catch (_) { return; }
    for (const video of videos) {
      if (configuredSubtitleVideos.has(video)) continue;
      configuredSubtitleVideos.add(video);
      let active = true;
      const turnOff = () => {
        if (!active) return;
        try {
          for (const track of Array.from(video.textTracks || [])) {
            if (track.mode !== 'disabled') track.mode = 'disabled';
          }
        } catch (_) {}
      };
      turnOff();
      video.addEventListener?.('loadedmetadata', turnOff, {once:true});
      video.addEventListener?.('loadeddata', turnOff, {once:true});
      video.addEventListener?.('playing', () => {
        turnOff();
        // Default only: do not override an explicit later subtitle choice.
        setTimeout(() => { active = false; }, 1800);
      }, {once:true});
      try { video.textTracks?.addEventListener?.('addtrack', turnOff); } catch (_) {}
      setTimeout(() => { active = false; }, 18000);
    }
  }
  // Content scripts may initialize before <video> exists in an iframe.
  initializeSubtitleDefaults();
  let subtitleDiscoveryChecks = 0;
  const subtitleDiscoveryTimer = setInterval(() => {
    initializeSubtitleDefaults();
    if (++subtitleDiscoveryChecks >= 90) clearInterval(subtitleDiscoveryTimer);
  }, 500);
  // Android TV remote navigation and phone touch input need different hiding
  // behavior. Device capability is only a hint; a real touch event also opts in.
  let touchPlaybackMode = (navigator.maxTouchPoints || 0) > 0;
  let touchUiVisible = false;
  let touchIdleTimer = null;
  let lastTouchWake = 0;
  let touchStartupTimer = null;
  // JW Player auto-hide and menu manipulation is verified for JA's VidSrc
  // player. Other providers may have completely different controls. Do NOT
  // apply JW-centric CSS or center-button discovery to those players.
  const isVidFastContext = () => {
    const host = location.hostname.toLowerCase();
    let parent = '';
    try { parent = new URL(document.referrer).hostname.toLowerCase(); } catch (_) {}
    return /(^|\.)vidfast\.vc$/.test(host) ||
      /(^|\.)vidfast\.vc$/.test(parent);
  };
  // v1.1.5f: VidFast must own its control DOM and all touch handling. The
  // primary JA extension is designed for VidSrc's JW Player. Even its benign
  // document-level listeners and injected styles are not installed here.
  // We retain read-only touch hit-testing and basic HTML5 remote commands.
  // This path is intentionally limited to the VidFast host or direct child.
  if (isVidFastContext()) {
    let diagnosticPort = null;
    let reconnectAttempts = 0;
    let touchReports = 0;
    const report = event => {
      try { diagnosticPort?.postMessage({event, frame:location.hostname}); } catch (_) {}
    };
    const sendDetail = detail => {
      try { diagnosticPort?.postMessage({frame:location.hostname, ...detail}); } catch (_) {}
    };
    const activeVideo = () => {
      const vids = Array.from(document.querySelectorAll('video'));
      return vids.find(v => !v.paused && !v.ended) || vids[0] || null;
    };
    // VidFast previously returned before the JW performance sampler. Give its
    // native HTML5 video the SAME 1-second VIDEO_STATS path without touching
    // provider playback UI, remote key mapping, quality, or autoplay.
    setInterval(() => {
      const video = activeVideo();
      if (!video || video.paused || video.ended) return;
      try {
        const quality = video.getVideoPlaybackQuality?.();
        if (!quality || !video.videoWidth || !video.videoHeight) return;
        sendDetail({event:'VIDEO_STATS', width:video.videoWidth,
          height:video.videoHeight, total:quality.totalVideoFrames,
          dropped:quality.droppedVideoFrames,
          position:Math.round(video.currentTime*1000)/1000});
      } catch (_) {}
    }, 1000);
    const elementLabel = el => {
      if (!el || el.nodeType !== 1) return '';
      const cls = typeof el.className === 'string' ? el.className : (el.className?.baseVal || '');
      return `${el.tagName.toLowerCase()}${el.getAttribute('role') ? '[role='+el.getAttribute('role')+']' : ''}`+
        ` .${String(cls).trim().split(/\s+/).slice(0,2).join('.')}`.slice(0,84);
    };
    const controlLabel = el => (el?.getAttribute?.('aria-label') ||
      el?.getAttribute?.('title') || el?.getAttribute?.('data-testid') || '').slice(0,60);
    const clickable = el => el?.closest?.(
      'button,[role="button"],a[href],input[type="button"],input[type="image"],'+
      '.vjs-control,.plyr__control,[class*="-button" i],[class*="-btn" i]') || null;
    const nearbyControl = (x,y) => {
      let chosen = null, score = Infinity;
      const nodes = document.querySelectorAll(
        'button,[role="button"],[aria-label],[title],.vjs-control,.plyr__control,'+
        '[class*="-button" i],[class*="-btn" i]');
      for (const el of nodes) {
        if (!el.isConnected || el.disabled || el.getAttribute('aria-disabled') === 'true') continue;
        const r = el.getBoundingClientRect();
        if (r.width < 12 || r.height < 12 || r.width > 240 || r.height > 160) continue;
        const style = getComputedStyle(el);
        if (style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) < .05) continue;
        const dx = Math.max(0,r.left-x,x-r.right), dy = Math.max(0,r.top-y,y-r.bottom);
        if (dx > 14 || dy > 14) continue;
        const size = Math.hypot(dx,dy) + Math.min(r.width*r.height/5000,10);
        if (size < score) { score = size; chosen = el; }
      }
      if (!chosen) return null;
      const r=chosen.getBoundingClientRect();
      return {element:elementLabel(chosen),label:controlLabel(chosen),
        rect:[Math.round(r.x),Math.round(r.y),Math.round(r.width),Math.round(r.height)],
        cssPointerEvents:getComputedStyle(chosen).pointerEvents};
    };
    const inspectTouch = (kind, e) => {
      if (touchReports >= 28) return;
      const x = Math.round(e.clientX), y = Math.round(e.clientY);
      if (!Number.isFinite(x) || !Number.isFinite(y)) return;
      // Nothing sensitive, such as text typed into a search/login field, is
      // captured. Report only taps where a video or player controls exist.
      if (!activeVideo() && !document.querySelector('button,[role="button"],.vjs-control,.plyr__control')) return;
      const hit=document.elementFromPoint(x,y);
      const target=e.target?.nodeType === 1 ? e.target : null;
      const action=clickable(hit);
      const nearest=nearbyControl(x,y);
      const r=hit?.getBoundingClientRect?.();
      touchReports++;
      sendDetail({event:'VIDFAST_TOUCH_HIT',kind,
        x,y,view:[innerWidth,innerHeight],
        target:elementLabel(target),hit:elementLabel(hit),
        action:elementLabel(action),actionLabel:controlLabel(action),
        nearest,hitRect:r?[Math.round(r.x),Math.round(r.y),Math.round(r.width),Math.round(r.height)]:null,
        videoCount:document.querySelectorAll('video').length});
    };
    // Passive capture only. We deliberately do NOT synthesize clicks, change
    // pointer-events, preventDefault, stopPropagation, or hide any buttons.
    window.addEventListener('pointerdown', e => {
      if (e.pointerType === 'touch' || e.pointerType === 'pen') inspectTouch('down',e);
    }, {capture:true,passive:true});
    window.addEventListener('click', e => {
      if (touchReports && e.isTrusted) inspectTouch('click',e);
    }, {capture:true,passive:true});
    const onCommand = async message => {
      const action=message?.action;
      if (!['toggle','back10','forward10','controlsOpen','controlsClose'].includes(action)) return;
      const video=activeVideo();
      if (action === 'controlsOpen' || action === 'controlsClose') {
        // Avoid making Android think a JW toolbar remains selected on VidFast.
        sendDetail({event:'ACTION_OK',action,provider:'vidfast',supported:false});
        report('CONTROL_MODE_CLOSED');
        return;
      }
      if (!video) {sendDetail({event:'NO_VIDEO',action,provider:'vidfast'});return;}
      try {
        if (action === 'toggle') {
          if (video.paused) await video.play(); else video.pause();
        } else if (video.seekable?.length) {
          const current=video.currentTime || 0;
          const end=video.seekable.end(video.seekable.length-1);
          const start=video.seekable.start(0);
          video.currentTime=Math.max(start,Math.min(end,current+(action==='back10'?-10:10)));
        } else {
          sendDetail({event:'NOT_SEEKABLE',action,provider:'vidfast'}); return;
        }
        sendDetail({event:'ACTION_OK',action,provider:'vidfast',paused:video.paused,
          position:Math.round(video.currentTime)});
      } catch(error) {sendDetail({event:'ACTION_ERROR',action,provider:'vidfast',error:error?.name||String(error)});}
    };
    const connect = () => {
      try {
        const port=browser.runtime.connectNative('ja_tv_remote');
        diagnosticPort=port;
        port.onMessage.addListener(onCommand);
        port.onDisconnect.addListener(() => {
          diagnosticPort=null;
          if (++reconnectAttempts < 5) setTimeout(connect,1200);
        });
        sendDetail({event:'VIDFAST_TOUCH_ISOLATION_ENABLED',referrerHost:(() => {
          try {return new URL(document.referrer).hostname;} catch(_) {return '';}
        })(),hasVideo:!!activeVideo(),touchPoints:navigator.maxTouchPoints||0});
      } catch (_) {
        if (++reconnectAttempts < 5) setTimeout(connect,1200);
      }
    };
    connect();
    return;
  }
  const isVerifiedJwContext = () => !isVidFastContext() &&
    !!document.querySelector('.jwplayer, .jw-seek, .jw-btn, .jw-menu');
  let fallbackUiReported = false;
  const TOUCH_IDLE_MS = 3200;
  let menuSelection = -1;
  let ccSelectMode = false;
  let lastCcMenuId = "";
  let controlIdleTimer = null;
  const CONTROL_MODE_IDLE_MS = 8000;
  const MENU_FOCUS_TAG = 'data-ja-tv-menu-focus';
  const TAG = 'data-ja-tv-focus';

  const send = (o) => { try { port?.postMessage({frame: location.hostname, ...o}); } catch (_) {} };
  const visible = (el) => {
    if (!el || !el.isConnected) return false;
    const s = getComputedStyle(el), r = el.getBoundingClientRect();
    return s.display !== 'none' && s.visibility !== 'hidden' && r.width > 4 && r.height > 4;
  };
  const videos = () => Array.from(document.querySelectorAll('video'));
  // One lookup per synchronous task. Several control helpers ask for the same
  // video back-to-back; avoid repeated DOM queries and layout/style reads.
  // Clear at the microtask checkpoint, so the next event/stat sample always
  // discovers a replaced, hidden, paused or newly playing video normally.
  let videoLookupCached = false;
  let videoLookupResult = null;
  const currentVideo = () => {
    if (videoLookupCached && (!videoLookupResult || videoLookupResult.isConnected)) return videoLookupResult;
    const list = videos().filter(visible);
    videoLookupResult = list.find(v => !v.paused && !v.ended) || list[0] || null;
    videoLookupCached = true;
    queueMicrotask(() => { videoLookupCached = false; videoLookupResult = null; });
    return videoLookupResult;
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

  // JA v1.0.21: focus the PLAY button that is already rendered in the
  // video's center. Never manufacture an overlay and never seek on Up.
  let centerPlayMode = false;
  let centerPlayTarget = null;
  let centerPlayOriginal = null;
  const CENTER_PLAY_FOCUS_TAG = 'data-ja-tv-center-play-focus';

  function clearCenterPlayFocus() {
    if (centerPlayTarget?.isConnected) {
      try {
        centerPlayTarget.removeAttribute(CENTER_PLAY_FOCUS_TAG);
        if (centerPlayOriginal) {
          for (const [property, before] of Object.entries(centerPlayOriginal)) {
            if (before.value) centerPlayTarget.style.setProperty(property, before.value, before.priority);
            else centerPlayTarget.style.removeProperty(property);
          }
        }
      } catch (_) {}
    }
    centerPlayTarget = null;
    centerPlayOriginal = null;
  }

  function leaveCenterPlayMode() {
    centerPlayMode = false;
    clearCenterPlayFocus();
  }

  function centerPlayCandidates() {
    const video = currentVideo();
    if (!video) return [];
    const root = video.closest('.jwplayer, .jw-wrapper, [class*="jw-player" i]') || document;
    const vr = video.getBoundingClientRect();
    const midX = vr.left + vr.width / 2;
    const midY = vr.top + vr.height / 2;
    const selectors = [
      '.jw-display-icon-display',
      '.jw-display-controls .jw-display-icon-container',
      '.jw-display-icon-container .jw-icon-display',
      '.jw-display-controls [class*="play" i]',
      '.jw-display-controls [class*="pause" i]',
      '.jw-icon-playback',
      'button[aria-label*="play" i]', 'button[aria-label*="pause" i]',
      'button[title*="play" i]', 'button[title*="pause" i]',
      '[role="button"][aria-label*="play" i]',
      '[role="button"][aria-label*="pause" i]'
    ].join(',');
    const elements = Array.from(root.querySelectorAll(selectors));
    if (root !== document) elements.push(...document.querySelectorAll(selectors));
    const result = [];
    for (const el of new Set(elements)) {
      if (!el.isConnected || el.contains(video) || el.closest('.jw-menu')) continue;
      if (el.closest('.jw-controlbar, .jw-bottom')) continue;
      const r = el.getBoundingClientRect();
      if (r.width < 12 || r.height < 12 || r.width > vr.width * .38 || r.height > vr.height * .45) continue;
      const dx = (r.left + r.width / 2 - midX) / Math.max(1, vr.width);
      const dy = (r.top + r.height / 2 - midY) / Math.max(1, vr.height);
      if (Math.abs(dx) > .35 || Math.abs(dy) > .35) continue;
      // JW Player hides many elements through their ancestor's opacity.
      // Avoid focusing a hidden play icon or an inaccessible DOM placeholder.
      let hidden = false;
      for (let n=el, i=0; n && i<12; n=n.parentElement, i++) {
        const s=getComputedStyle(n);
        if (s.display==='none' || s.visibility==='hidden' || Number(s.opacity)<.05 ||
            n.hasAttribute('hidden') || n.getAttribute('aria-hidden')==='true') { hidden=true; break; }
        if (n === root) break;
      }
      if (hidden) continue;
      const cls=String(el.className?.baseVal || el.className || '');
      const label=labelOf(el);
      const exact=/jw-display-icon-display/i.test(cls);
      const center=/jw-display-controls|jw-display-icon/i.test(cls) || Boolean(el.closest('.jw-display-controls, .jw-display-icon-container'));
      const semantic=/\b(play|pause|playback)\b/i.test(label);
      if (!exact && !center && !semantic) continue;
      const score=(exact?120:0)+(center?75:0)+(semantic?25:0)-
        (Math.abs(dx)+Math.abs(dy))*100;
      result.push({el,score,cls,label,rect:[Math.round(r.left),Math.round(r.top),Math.round(r.width),Math.round(r.height)]});
    }
    return result.sort((a,b) => b.score-a.score).slice(0, 8);
  }

  function focusCenterPlayFromTimeline() {
    const candidates=centerPlayCandidates();
    send({event:'CENTER_PLAY_SCAN',count:candidates.length,
      candidates:candidates.slice(0,4).map(c=>({cls:c.cls,label:c.label,rect:c.rect}))});
    const target=candidates[0]?.el;
    if (!target) {
      // Retain progress focus. In particular, NEVER convert Up into a
      // large forward seek when no center Play button is present.
      send({event:'CENTER_PLAY_NOT_VISIBLE',kept:'NATIVE_PLAYER_BAR'});
      return false;
    }
    leaveTimelineMode(false);
    clearFocus();
    clearCenterPlayFocus();
    centerPlayMode=true;
    centerPlayTarget=target;
    centerPlayOriginal={
      outline:{value:target.style.getPropertyValue('outline'),priority:target.style.getPropertyPriority('outline')},
      'outline-offset':{value:target.style.getPropertyValue('outline-offset'),priority:target.style.getPropertyPriority('outline-offset')}
    };
    target.setAttribute(CENTER_PLAY_FOCUS_TAG,'true');
    target.style.setProperty('outline','3px solid white','important');
    target.style.setProperty('outline-offset','4px','important');
    try { if (typeof target.focus==='function') target.focus({preventScroll:true}); } catch (_) {}
    send({event:'CENTER_PLAY_FOCUSED',label:candidates[0].label,cls:candidates[0].cls});
    return true;
  }

  // Keep center display controls hidden while playing. A stylesheet survives
  // JW Player rerendering the controls (unlike one-time inline visibility).
  const CENTER_HIDE_ATTR='data-ja-tv-hide-center';
  const CONTROLS_SHOW_ATTR='data-ja-tv-show-toolbar';
  // An iframe can leave the movie name pinned at its upper edge even
  // after the *separate* JW control bar has hidden. Hide title chrome only
  // after Android TV has confirmed video playback. No center or bottom
  // controls are targeted; touch/phone sessions never receive this action.
  const TITLE_HIDE_ATTR='data-ja-tv-hide-title';
  const TITLE_NODE_ATTR='data-ja-tv-hidden-title';
  const titleStyle=document.createElement('style');
  titleStyle.textContent=`html[${TITLE_HIDE_ATTR}="true"] .jw-title,
    html[${TITLE_HIDE_ATTR}="true"] .jw-title-primary,
    html[${TITLE_HIDE_ATTR}="true"] .jw-title-secondary,
    html[${TITLE_HIDE_ATTR}="true"] .vjs-title,
    html[${TITLE_HIDE_ATTR}="true"] .player-title,
    html[${TITLE_HIDE_ATTR}="true"] .video-title,
    html[${TITLE_HIDE_ATTR}="true"] .movie-title,
    html[${TITLE_HIDE_ATTR}="true"] [data-testid="player-title"],
    html[${TITLE_HIDE_ATTR}="true"] [${TITLE_NODE_ATTR}="true"] {
      opacity:0 !important;visibility:hidden !important;pointer-events:none !important;
    }`;
  (document.head || document.documentElement).appendChild(titleStyle);
  function hidePlayerTitleChrome() {
    // Ignore ad/tracker frames: only pages containing a video, JW shell, or
    // embedded player iframe can have the JA player's title banner.
    if (!document.querySelector('video,.jwplayer,iframe')) return;
    document.documentElement.setAttribute(TITLE_HIDE_ATTR,'true');
    const vh=window.innerHeight || document.documentElement.clientHeight || 720;
    let hiddenCount=0;
    const labels=[];
    // Broad enough for a simple TITLE (YEAR) label, but don't remove a
    // container, any video ancestor, menus or interactive controls.
    const candidates=document.querySelectorAll(
      '.jw-title,.jw-title-primary,.jw-title-secondary,.vjs-title,'+
      '.player-title,.video-title,.movie-title,[data-testid="player-title"],'+
      'h1,h2,h3,[class*="title" i],[id*="title" i]');
    let checked=0;
    for (const el of candidates) {
      if (++checked > 350) break;
      if (!el.isConnected || el.closest('.jw-menu,.jw-controlbar,.jw-bottom,.jw-seek,.jw-toolbar')) continue;
      if (el.querySelector('video,iframe,button,input,[role="button"]')) continue;
      const rect=el.getBoundingClientRect();
      if (rect.width < 15 || rect.height < 5 || rect.height > 100 ||
          rect.top > vh*.35 || rect.bottom < 0) continue;
      const value=(el.innerText||el.textContent||'').trim().replace(/\s+/g,' ');
      if (value.length < 3 || value.length > 100) continue;
      const cls=String(el.className?.baseVal||el.className||'');
      const id=el.id||'';
      const explicitlyNamed=/title/i.test(cls+' '+id+' '+(el.getAttribute('data-testid')||''));
      const titleWithYear=/^(?!.*(?:install|vpn|virus|advert))(?:.{3,85})\s(?:19|20)\d{2}$/i.test(value);
      if (!explicitlyNamed && !titleWithYear) continue;
      if (el.getAttribute(TITLE_NODE_ATTR)==='true') continue;
      el.setAttribute(TITLE_NODE_ATTR,'true');
      hiddenCount++;
      if (labels.length<2) labels.push(value.slice(0,48));
    }
    if (hiddenCount) send({event:'TITLE_CHROME_HIDDEN',count:hiddenCount,labels});
    else if (currentVideo() || /(^|\.)vidsrc\.mov$/.test(location.hostname))
      send({event:'TITLE_CHROME_RULE_ARMED',frame:location.hostname,checked});
  }
  // In control mode, explicitly reveal JW Player's real toolbar. This is
  // temporary and does not change autoplay, volume, quality, or subtitles.
  const toolbarForced = new Map();
  const toolbarProperties = ['opacity','visibility','pointer-events'];
  function restoreToolbarForced() {
    for (const [el, saved] of toolbarForced) {
      if (!el.isConnected) continue;
      for (const [name, before] of Object.entries(saved)) {
        if (before.value) el.style.setProperty(name, before.value, before.priority);
        else el.style.removeProperty(name);
      }
    }
    toolbarForced.clear();
  }
  const toolbarStyle=document.createElement('style');
  toolbarStyle.textContent=`html[${CONTROLS_SHOW_ATTR}="true"] .jw-btn,
    html[${CONTROLS_SHOW_ATTR}="true"] .jw-seek,
    html[${CONTROLS_SHOW_ATTR}="true"] .jw-controlbar,
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
    // This player has .jw-btn and .jw-seek, but logs show NO .jw-controlbar
    // or .jw-bottom. Unhide only the actual button/rail ancestors, not the
    // entire player, because that would expose the center playback icons.
    const parts=[...document.querySelectorAll('.jw-btn, .jw-seek')]
      .filter(el=>el.isConnected && !el.closest('.jw-menu'));
    let patched=0;
    for (const element of parts) {
      let el=element;
      for (let depth=0;el && depth<6;depth++,el=el.parentElement) {
        if (el===document.body || el===document.documentElement ||
            el.querySelector('video') || el.closest('.jw-menu')) break;
        if (!toolbarForced.has(el)) {
          const saved={};
          for (const name of toolbarProperties) saved[name]={
            value:el.style.getPropertyValue(name), priority:el.style.getPropertyPriority(name)};
          toolbarForced.set(el,saved);
          patched++;
        }
        for (const name of toolbarProperties)
          el.style.setProperty(name, name==='opacity'?'1':name==='visibility'?'visible':'auto','important');
      }
    }
    send({event:'CONTROL_BAR_REVEAL_REQUESTED',reason,found:bars.length,
      candidates:controlElements().length,patched});
  }
  const centerStyle=document.createElement('style');
  const CENTER_NODE_TAG='data-ja-tv-hide-center-node';
  centerStyle.textContent=`html[${CENTER_HIDE_ATTR}="true"] .jw-display-controls,
    html[${CENTER_HIDE_ATTR}="true"] .jw-display-icon-container,
    html[${CENTER_HIDE_ATTR}="true"] [${CENTER_NODE_TAG}="true"] {
      opacity: 0 !important; visibility: hidden !important;
      pointer-events: none !important;
    }`;
  (document.head || document.documentElement).appendChild(centerStyle);
  function updateCenterVisibility() {
    if (!isVerifiedJwContext()) {
      document.documentElement.removeAttribute(CENTER_HIDE_ATTR);
      return;
    }
    const v=currentVideo();
    // The central play/skip graphics should stay hidden during playback,
    // including while navigating the separate bottom toolbar.
    if (v && !v.paused && !(touchPlaybackMode && touchUiVisible))
      document.documentElement.setAttribute(CENTER_HIDE_ATTR,'true');
    else document.documentElement.removeAttribute(CENTER_HIDE_ATTR);
  }
  // The verified embedded player has shown 0 standard JW display nodes in
  // earlier diagnostics. Discover individual central action nodes instead of
  // hiding its main video/toolbar container. Never hide subtitle or settings UI.
  let lastCenterDiscovery='';
  function discoverCenterButtons() {
    if (!isVerifiedJwContext()) return 0;
    const video=currentVideo();
    if (!video) return 0;
    const vr=video.getBoundingClientRect();
    if (vr.width<100 || vr.height<80) return 0;
    const found=[];
    const all=document.querySelectorAll('button, [role="button"], [class*="jw-"], svg');
    for (const el of all) {
      if (!el.isConnected || el.contains(video) ||
          el.closest('.jw-menu, .jw-controlbar, .jw-bottom, .jw-seek, .jw-toolbar')) continue;
      const r=el.getBoundingClientRect();
      if (r.width<14 || r.height<14 || r.width>vr.width*.26 || r.height>vr.height*.30) continue;
      const cx=r.left+r.width/2, cy=r.top+r.height/2;
      if (cx < vr.left+vr.width*.22 || cx > vr.right-vr.width*.22 ||
          cy < vr.top+vr.height*.25 || cy > vr.top+vr.height*.73) continue;
      const cls=String(el.className?.baseVal||el.className||'');
      const desc=(cls+' '+(el.getAttribute('aria-label')||'')+' '+(el.getAttribute('title')||'')).toLowerCase();
      const semantic=/play|pause|rewind|forward|backward|skip|center|display|seek|overlay|control/.test(desc);
      const isButton=el.matches('button,[role="button"]');
      if (!semantic && !isButton) continue;
      // Choose the actionable element; never mark a container with video.
      const target=el.closest('button,[role="button"]')||el;
      if (target.contains(video) || target.closest('.jw-menu,.jw-controlbar,.jw-bottom,.jw-seek')) continue;
      if (!found.includes(target)) found.push(target);
    }
    for (const el of found) el.setAttribute(CENTER_NODE_TAG,'true');
    const signature=found.map(e=>String(e.className?.baseVal||e.className||'').slice(0,64)).join('|');
    if (signature!==lastCenterDiscovery) {
      lastCenterDiscovery=signature;
      send({event:'CENTER_BUTTON_DISCOVERY',count:found.length,
        nodes:found.slice(0,9).map(e=>({tag:e.tagName.toLowerCase(),
          cls:String(e.className?.baseVal||e.className||'').slice(0,90),
          label:(e.getAttribute('aria-label')||'').slice(0,60)}))});
    }
    updateCenterVisibility();
    return found.length + document.querySelectorAll('.jw-display-controls, .jw-display-icon-container').length;
  }
  function hideCentralPlaybackIcons() {
    return discoverCenterButtons();
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
    if (!isVerifiedJwContext()) {
      document.documentElement.removeAttribute(CONTROLS_SHOW_ATTR);
      restoreToolbarForced();
      if (!fallbackUiReported && currentVideo()) {
        fallbackUiReported = true;
        const ctrls = [...document.querySelectorAll('button, [role="button"], [aria-label]')]
          .filter(visible).slice(0, 20).map(e => ({
            tag:e.tagName.toLowerCase(), label:labelOf(e).slice(0, 65),
            cls:String(e.className?.baseVal || e.className || '').slice(0, 65)
          }));
        send({event:'NON_JW_CONTROLS_PRESERVED',
          vidfast:isVidFastContext(), controls:ctrls});
      }
      return;
    }
    if (controlMode) return;
    hideTimer = setTimeout(() => {
      hideTimer = null;
      const video = currentVideo();
      if (!video || video.paused || controlMode) return;
      if (!touchPlaybackMode) {
        // TV-only behavior: restore the verified remote-first auto-hide path.
        // On phones, inline visibility:hidden prevents a later user tap from
        // re-showing the player's buttons, even if JW Player wants them visible.
        try { document.activeElement?.blur?.(); } catch (_) {}
        const root = video.closest('[class*="player" i]') || video.parentElement;
        if (root) {
          try { root.dispatchEvent(new MouseEvent('mouseleave', {bubbles:true})); } catch (_) {}
        }
        const bars = bottomBarCandidates().filter(el =>
          /control|toolbar|player-bar|progress/i.test(String(el.className)) &&
          !el.querySelector('video') && !el.matches('video')
        );
        const targets = bars.filter(el => !bars.some(parent => parent !== el && el.contains(parent)));
        for (const el of targets.slice(0, 15)) {
          hidden.push([el, el.style.getPropertyValue('visibility') || null]);
          el.style.setProperty('visibility', 'hidden');
        }
      }
      // The dedicated center-icon rule remains independent of JW's toolbar;
      // it is temporarily relaxed while the user is interacting by touch.
      const centerCount = hideCentralPlaybackIcons();
      send({event:'CONTROLS_HIDDEN', count:hidden.length,
        centerCandidates:centerCount, touchMode:touchPlaybackMode});
    }, delay);
  }
  // Touch controls should wake from a real user tap without reloading the
  // movie. Listen in capture phase and never preventDefault/stopPropagation:
  // the same gesture must still reach JW Player's normal touch handlers.
  function onActualTouch(source) {
    const video = currentVideo();
    if (!video) return;
    const now = Date.now();
    if (now - lastTouchWake < 120) return; // pointerdown + touchstart pair
    lastTouchWake = now;
    const wasTouch = touchPlaybackMode;
    touchPlaybackMode = true;
    // If the device switched from remote to a touch surface, release stale
    // remote focus and toolbar forcing without triggering a player click.
    if (controlMode) closeMode('TOUCH_INPUT');
    restore(); // Undo any old inline visibility:hidden on bottom controls.
    touchUiVisible = true;
    updateCenterVisibility();
    if (touchIdleTimer) clearTimeout(touchIdleTimer);
    touchIdleTimer = setTimeout(() => {
      touchIdleTimer = null;
      touchUiVisible = false;
      // Hide *only* center playback icons after touch inactivity; JW Player
      // remains solely responsible for hiding/revealing its own touch toolbar.
      discoverCenterButtons();
      updateCenterVisibility();
      send({event:'TOUCH_IDLE_CENTER_HIDDEN',paused:video.paused});
    }, TOUCH_IDLE_MS);
    send({event:wasTouch?'TOUCH_CONTROLS_WAKE':'TOUCH_MODE_ENABLED',source,
      touchPoints:navigator.maxTouchPoints||0,restored:true});
  }
  document.addEventListener('touchstart', () => onActualTouch('touchstart'),
    {capture:true,passive:true});
  document.addEventListener('pointerdown', event => {
    if (event.pointerType==='touch' || event.pointerType==='pen')
      onActualTouch(event.pointerType);
  }, {capture:true,passive:true});

  // On a phone the center button can be inserted *after* the initial play
  // event (e.g. after a source/quality change). Retry discovery for a short,
  // bounded startup window. No long-running DOM observer or performance cost.
  function probeTouchCenterAtStartup() {
    if (!touchPlaybackMode || touchStartupTimer) return;
    let tries = 0;
    touchStartupTimer = setInterval(() => {
      if (++tries >= 14) { clearInterval(touchStartupTimer); touchStartupTimer=null; }
      const video = currentVideo();
      if (video && !video.paused) discoverCenterButtons();
    }, 650);
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
  function armControlIdle() {
    if (controlIdleTimer) clearTimeout(controlIdleTimer);
    controlIdleTimer = null;
    if (!controlMode) return;
    controlIdleTimer = setTimeout(() => {
      controlIdleTimer = null;
      // Never interrupt an open CC/Settings menu or active timeline navigation.
      if (openJwMenu() || timelineMode) { armControlIdle(); return; }
      if (!controlMode) return;
      closeMode('IDLE_TIMEOUT');
    }, CONTROL_MODE_IDLE_MS);
  }
  function openMode() {
    if (!currentVideo()) return send({event:'NO_VIDEO', action:'controlsOpen'});
    leaveCenterPlayMode();
    restore(); controlMode = true; menuSelection=-1; ccSelectMode=false; clearMenuFocus();
    revealToolbar('controlsOpen'); updateCenterVisibility();
    const items = controlElements();
    send({event:'CONTROLS_FOUND', count:items.length, labels:items.map(labelOf)});
    // v1.0.16 added the left-hand speaker as the first item. Don't focus
    // Mute automatically on entering controls; start on CC if available.
    const preferred=items.findIndex(el => /subtitles|captions/i.test(labelOf(el)));
    selected=-1;
    select(items, preferred >= 0 ? preferred : 0);
    armControlIdle();
  }
  function closeMode(reason = 'REMOTE_BACK') {
    leaveCenterPlayMode();
    if (timelineMode) leaveTimelineMode(false);
    if (controlIdleTimer) { clearTimeout(controlIdleTimer); controlIdleTimer = null; }
    controlMode = false; selected = -1; menuSelection=-1; ccSelectMode=false;
    document.documentElement.removeAttribute(CONTROLS_SHOW_ATTR);
    restoreToolbarForced();
    clearMenuFocus(); clearFocus(); restore();
    // Remove browser focus from a stale CC/Settings button when controls hide.
    try { if (document.activeElement?.matches?.('.jw-btn, button, [role="button"]')) document.activeElement.blur(); } catch (_) {}
    send({event:'CONTROL_MODE_CLOSED', reason});
    hideWhenPlaying();
    updateCenterVisibility();
  }
  async function handle(message) {
    const action = message?.action;
    if (action === 'hideTitleChrome') { hidePlayerTitleChrome(); return; }
    if (action && /^controls(?:Open|Next|Prev|Select|Down|Up|Close)$/.test(action)) {
      defaultsUserMenuTouched = true;
      document.documentElement.removeAttribute(BACKGROUND_STARTUP_ATTR);
    }
    if (!['toggle','back10','forward10','controlsOpen','controlsNext','controlsPrev','controlsSelect','controlsClose','controlsUp','controlsDown','inspect'].includes(action)) return;
    const video = currentVideo();
    if (!video) { send({event:'NO_VIDEO', action, count:videos().length}); return; }
    if (controlMode) armControlIdle();
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
        if (centerPlayMode) {
          if (action === 'controlsDown') {
            leaveCenterPlayMode();
            if (enterTimelineMode()) send({event:'CENTER_PLAY_TO_TIMELINE'});
            else { openMode(); send({event:'CENTER_PLAY_TO_TOOLBAR'}); }
          } else send({event:'CENTER_PLAY_ALREADY_FOCUSED'});
          return;
        }
        if (timelineMode) {
          if (action === 'controlsDown') {
            // Down means go from the progress rail to the toolbar, NOT seek backwards.
            leaveTimelineMode(false);
            openMode();
            send({event:'TIMELINE_TO_TOOLBAR'});
          } else {
            // Up selects the on-screen center Play/Pause control if available;
            // do not jump forward by 5% of the video duration.
            focusCenterPlayFromTimeline();
          }
          return;
        }
        if (!controlMode) { openMode(); return; }
        // Down also recovers the real controls if JW auto-hid them while
        // Android was still in navigation mode. Re-establish valid focus.
        if (action === 'controlsDown' && !openJwMenu()) {
          openMode();
          send({event:'CONTROL_BAR_REOPENED'});
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
        if (centerPlayMode) {
          // Leave the center control selected rather than accidentally moving
          // focus to a hidden toolbar item. Down returns to the timeline.
          send({event:'CENTER_PLAY_FOCUSED',hint:'DOWN_TO_TIMELINE_OR_OK_TO_TOGGLE'});
          return;
        }
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
        if (centerPlayMode) {
          // The HTML5 video methods are already proven on this device; use
          // them instead of synthetic clicks on a potentially inert SVG icon.
          leaveCenterPlayMode();
          try {
            if (video.paused) await video.play(); else video.pause();
            send({event:'CENTER_PLAY_ACTION_OK',paused:video.paused,position:Math.round(video.currentTime)});
          } catch (e) {
            send({event:'CENTER_PLAY_ACTION_ERROR',error:e?.name || String(e)});
          }
          closeMode('CENTER_PLAY_SELECTED');
          return;
        }
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
  document.addEventListener('play', () => { if (!controlMode) hideWhenPlaying(1200); updateCenterVisibility(); }, true);
  // Read-only frame samples once per second. Three valid post-start samples
  // can reach JaTvFrameRateMatcher within ~3-5 seconds instead of ~15 seconds.
  // The matcher still requires valid data and may reject unsupported modes.
  perfTimer = setInterval(() => {
    const video = currentVideo();
    if (!video || video.paused) return;
    try {
      const q = video.getVideoPlaybackQuality?.();
      if (q) send({event:'VIDEO_STATS', width:video.videoWidth, height:video.videoHeight,
        total:q.totalVideoFrames, dropped:q.droppedVideoFrames,
        position:Math.round(video.currentTime * 1000) / 1000});
    } catch (_) {}
  }, 1000);
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
  // User-visible playback may pause while JW swaps video renditions.
  // Keep native Auto/adaptive quality until the viewer explicitly changes it.
  let qualitySkipped = false;
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
  // During unattended startup the menu is deliberately visually hidden;
  // openJwMenu() (used for user navigation) rejects invisible elements.
  const startupOpenJwMenu = () => {
    const menus=[...document.querySelectorAll('.jw-menu.is-open')];
    return menus[menus.length-1] || null;
  };

  function startupCloseMenu(kind) {
    const menu = startupOpenJwMenu();
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

  // JW Player's documented JS API can set quality directly, without
  // bringing up its Settings popup. Firefox content scripts use Xray wrappers;
  // the page's own jwplayer() global is only available through wrappedJSObject.
  // Treat everything returned by the page as untrusted and use it ONLY to
  // call these public playback-quality methods, never to pass privileged data.
  function backgroundJwQuality() {
    try {
      const page = window.wrappedJSObject;
      if (!page || typeof page.jwplayer !== 'function') return null;
      const host = document.querySelector('.jwplayer[id], [id].jwplayer');
      const api = host ? page.jwplayer(host.id) : page.jwplayer();
      if (!api || typeof api.getQualityLevels !== 'function' ||
          typeof api.getCurrentQuality !== 'function' ||
          typeof api.setCurrentQuality !== 'function') return null;
      const levels = Array.from(api.getQualityLevels() || []);
      const choices = levels.map((level,index) => {
        const label = String(level?.label || '').trim().slice(0,25);
        const height = Number(level?.height) || Number(/^(\d{3,4})\s*p$/i.exec(label)?.[1]) || 0;
        return {index, label:label || (height ? `${height}p` : ''), height};
      }).filter(x => x.height > 0 && x.height < 10000).sort((a,b) => b.height-a.height);
      if (!choices.length) return null;
      const index = Number(api.getCurrentQuality());
      return {api,levels,choices,index, selected:levels[index] ?
        String(levels[index].label || `${levels[index].height}p`).trim() : ''};
    } catch (_) { return null; }
  }
  let qualityApiMissingLogged = false;
  const BACKGROUND_STARTUP_ATTR = 'data-ja-tv-background-startup';
  const backgroundStartupStyle=document.createElement('style');
  // DOM fallback may need to open a menu internally to select quality.
  // Clip it only during unattended startup (DOM .click() still works).
  // The rule is removed before the user can navigate player controls.
  backgroundStartupStyle.textContent=`html[${BACKGROUND_STARTUP_ATTR}="true"] .jw-menu {
    opacity: 0 !important; visibility: hidden !important;
    pointer-events: none !important; clip-path: inset(100%) !important;
  }`;
  (document.head || document.documentElement).appendChild(backgroundStartupStyle);
  // Inspect the actual quality-selection state, not the remote's highlight.
  // JW builds differ; if no selected marker exists, report UNVERIFIED rather
  // than falsely claiming that the highest resolution was applied.
  function startupQualityState() {
    const api=backgroundJwQuality();
    if (api) return {selected:api.selected, evidence:'JW_API',
      available:api.choices.map(x => x.label)};
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
    const api=backgroundJwQuality();
    if (api) {
      const best=api.choices[0];
      qualityTarget=best.label;
      if (api.index===best.index) {
        qualityRequested=true; qualityVerified=true;
        startupLog('DEFAULT_QUALITY_ALREADY_SELECTED', {quality:qualityTarget,
          evidence:'JW_API', attempt:qualityAttempts});
        return true;
      }
      if (qualityAttempts>=MAX_QUALITY_ATTEMPTS) return false;
      qualityAttempts++;
      try {
        api.api.setCurrentQuality(best.index);
        qualityRequested=true;
        startupLog('DEFAULT_QUALITY_REQUESTED', {quality:best.label,
          available:api.choices.map(x=>x.label),method:'JW_API',
          previousSelection:api.selected||'UNKNOWN',
          attempt:qualityAttempts,maxAttempts:MAX_QUALITY_ATTEMPTS,menuOpen:!!openJwMenu()});
        return true;
      } catch(e) {
        startupLog('DEFAULT_QUALITY_API_ERROR',{error:e?.name||String(e),attempt:qualityAttempts});
        // The known-working JW menu DOM is still available as a fallback.
      }
    }
    if (!qualityApiMissingLogged) {
      startupLog('DEFAULT_QUALITY_API_UNAVAILABLE', {fallback:'SUPPRESSED_MENU_DOM'});
      qualityApiMissingLogged=true;
    }
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
        menuOpen:!!openJwMenu(),method:'SUPPRESSED_MENU_DOM'
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
    const menu = startupOpenJwMenu();
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
    // Finish without flashing the unattended Settings/CC popup back onto
    // the screen. Keep it suppressed until the close handler has settled.
    let closes=0;
    const settleMenus=() => {
      const open=startupOpenJwMenu();
      if (open && closes++ < 10) {
        try { open.querySelector('.jw-menu-close,.jw-tabs-close')?.click(); } catch (_) {}
        setTimeout(settleMenus,180);
      } else {
        if (open) startupLog('DEFAULT_MENU_STILL_OPEN',{kind:menuKind(open)});
        document.documentElement.removeAttribute(BACKGROUND_STARTUP_ATTR);
        if (currentVideo() && !currentVideo().paused && !controlMode) hideWhenPlaying(600);
      }
    };
    settleMenus();
    startupLog(complete ? 'STARTUP_DEFAULTS_COMPLETE' : 'STARTUP_DEFAULTS_INCOMPLETE',{
      reason, qualityRequested, qualityVerified, qualitySkipped, qualityTarget,
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
      const open = startupOpenJwMenu();
      if (open && ['SETTINGS','CC'].includes(menuKind(open))) startupCloseMenu(menuKind(open));
      finishStartupPreferences(false,'TIMEOUT');
      return;
    }
    const video=currentVideo();
    if (!video || video.readyState < 1) return;
    const open = startupOpenJwMenu();
    const now=Date.now();
    switch(startupPhase) {
      case 'quality':
        // Never change the video's quality while it is buffering/starting.
        // Selecting a fixed 1080p rendition made VidSrc briefly interrupt
        // playback in the Nov 9 test. Let the provider's native Auto mode
        // choose resolution; the viewer can still select quality manually.
        // Retain subtitle-off and audio setup, and NEVER open Settings here.
        qualitySkipped=true;
        qualityTarget='Player default (unchanged)';
        startupLog('DEFAULT_QUALITY_SKIPPED', {
          reason:'PRESERVE_NATIVE_AUTO', qualityChanges:0
        });
        advanceStartup('ccOpen');
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
      case 'done': {
        // Intentionally leaving Auto unchanged is success, not a failed
        // attempt to enforce a fixed quality value.
        const defaultsComplete=(qualitySkipped || qualityVerified) && ccRequested;
        finishStartupPreferences(defaultsComplete,
          defaultsComplete ? '' : 'SOME_PREFERENCES_UNAVAILABLE_OR_UNVERIFIED');
        return;
      }
    }
  }

  let startupProbeTimer = null;
  function startPlaybackDefaults() {
    if (defaultsTimer || startupProbeTimer) return;
    let probes=0;
    const tryStart = () => {
      // Extension also runs in ad/tracker/outer frames. Only the real JW
      // video frame should run preferences or generate timeout reports.
      const video=currentVideo();
      if (!video || !isVerifiedJwContext()) return false;
      if (startupProbeTimer) { clearInterval(startupProbeTimer); startupProbeTimer=null; }
      startupSince=Date.now(); phaseSince=startupSince; defaultsChecks=0;
      document.documentElement.setAttribute(BACKGROUND_STARTUP_ATTR,'true');
      startupLog('STARTUP_VIDEO_FRAME_READY');
      if (touchPlaybackMode) {
        send({event:'TOUCH_MODE_DETECTED',touchPoints:navigator.maxTouchPoints||0});
        probeTouchCenterAtStartup();
      }
      defaultsTimer=setInterval(checkStartupPreferences,500);
      checkStartupPreferences();
      return true;
    };
    if (tryStart()) return;
    startupProbeTimer=setInterval(() => {
      if (tryStart()) return;
      if (++probes>=100) { clearInterval(startupProbeTimer); startupProbeTimer=null; }
    },500);
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
      if (currentVideo() && !currentVideo().paused) hideWhenPlaying(2200);
    } catch (_) { setTimeout(connect,1000); }
  }
  connect();
})();
