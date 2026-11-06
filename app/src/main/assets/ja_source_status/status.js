/*
 * JA v1.1.5h source availability reporting. Read-only; no DOM mutations,
 * autoplay clicks, URL scraping or control remapping.
 *
 * A page-load response is not evidence of a playable video. The monitor
 * reports confirmed media start or explicit provider/media errors only.
 * Lack of media within a fixed time is never treated as confirmed failure.
 */
(() => {
  'use strict';
  if (globalThis.__jaSourceMonitor118) return;
  globalThis.__jaSourceMonitor118 = true;

  let port = null;
  let reportedStarted = false;
  let reportedUnavailable = false;
  let attempts = 0;
  const progress = new WeakMap();
  const fatalTimers = new WeakMap();
  let visibleErrorSince = 0;
  let lastErrorSignature = '';
  let scanNumber = 0;

  // A source may place the real player in an iframe. Do not trust arbitrary
  // ad/tracker frames to declare a title unavailable.
  const confirmedPlayerContext = () => {
    if (window === window.top) return true;
    const host = location.hostname.toLowerCase();
    let parentHost = '';
    try { parentHost = new URL(document.referrer).hostname.toLowerCase(); } catch (_) {}
    return host === 'stellarconductornexus.com' ||
      host === 'vidcore.org' || host.endsWith('.vidcore.org') ||
      host === 'player.videasy.ws' || host === 'vidspark.to' || host.endsWith('.vidspark.to') ||
      /(^|\.)(vidsrc\.sh|vidsrc\.mov|vidfast\.vc|vidfast\.pro|vidcore\.org|vidspark\.to)$/.test(parentHost) ||
      parentHost === 'player.videasy.ws';
  };

  const visibleFatalText = () => {
    if (!confirmedPlayerContext()) return '';
    const body = document.body;
    if (!body || videos().some(v => !v.paused && v.readyState >= 2)) return '';
    const value = (body.innerText || '').replace(/\s+/g, ' ').trim();
    // Real error screens are typically short. A lengthy page mentioning a
    // movie's unavailability is not proof that the player failed.
    if (value.length < 8 || value.length > 1200) return '';
    // Gecko may render a TLS/reset error in the embedded player document.
    // Only classify this explicit error on an actual player frame, not in an
    // unrelated advertising or analytics iframe. Firefox internal about:
    // error pages may not run content scripts; native onLoadError is primary.
    const host = location.hostname.toLowerCase();
    const playerFrame = window === window.top ||
      host === 'vidspark.to' || host.endsWith('.vidspark.to');
    if (playerFrame && /\bPR_CONNECT_RESET_ERROR\b|\bSecure Connection Failed\b/i.test(value)) {
      return 'PLAYER_CONNECTION_RESET';
    }
    // VidCore and other hosts may show a definitive compatibility error
    // instead of a network failure. The iframe belongs to the provider and
    // is not sandboxed by JA's top-level GeckoSession. Do not try to remove
    // sandbox restrictions from third-party frames. Report the visible error
    // so the existing explicit-failure fallback can select another server.
    // Require both the actual restricted-frame explanation and its sandbox
    // wording: generic mentions of 'sandbox' are not playback failures.
    if ((/\b(?:this )?player cannot be loaded inside a restricted\b/i.test(value) &&
         /\bsandbox(?:ed)?\b/i.test(value) && /\bframe\b/i.test(value)) ||
        (/\bPlayback blocked\b/i.test(value) &&
         /\brestricted\s*\(?sandbox(?:ed)?\)?\s*frame\b/i.test(value))) {
      return 'PLAYER_SANDBOX_RESTRICTED';
    }
    const patterns = [
      /\bthis media is unavailable\b/i,
      /\bmedia is unavailable\b/i,
      /\bmedia not found\b/i,
      /\bvideo is unavailable\b/i,
      /\bno playable (?:video )?sources? (?:were )?found\b/i,
      /\bno available servers?\b/i
    ];
    return patterns.some(re => re.test(value)) ? value.slice(0, 100) : '';
  };

  const checkVisibleFailure = () => {
    if (reportedStarted || reportedUnavailable) return;
    const error = visibleFatalText();
    const now = performance.now();
    if (!error) { visibleErrorSince = 0; lastErrorSignature = ''; return; }
    if (error !== lastErrorSignature) {
      visibleErrorSince = now;
      lastErrorSignature = error;
      return;
    }
    // Confirm that the same explicit error persists across two scans. This
    // is *not* an arbitrary timeout for an otherwise loading player.
    if (now - visibleErrorSince >= 350) {
      reportedUnavailable = true;
      send('MEDIA_UNAVAILABLE', error === 'PLAYER_SANDBOX_RESTRICTED'
        ? 'PLAYER_SANDBOX_RESTRICTED' : 'VISIBLE_MEDIA_UNAVAILABLE');
    }
  };

  // The first page load can begin media playback while nativeMessaging is
  // establishing its port. Retain the terminal status until the port exists;
  // otherwise the original code marked "reportedStarted" and permanently
  // discarded VIDEO_STARTED. This only forwards events and never clicks media.
  let pendingStatus = null;
  let retryTimer = null;
  const scheduleReconnect = () => {
    if (retryTimer || attempts >= 12) return;
    attempts += 1;
    retryTimer = setTimeout(() => {
      retryTimer = null;
      connect();
    }, 1200);
  };
  const flushPending = () => {
    if (!port || !pendingStatus) return;
    try {
      port.postMessage(pendingStatus);
      pendingStatus = null;
    } catch (_) {
      port = null;
      scheduleReconnect();
    }
  };
  const send = (event, reason) => {
    // Each frame reports at most one terminal outcome. The native bridge
    // independently ignores duplicates and events from disposed sessions.
    pendingStatus = { event, reason, frame: location.hostname };
    flushPending();
  };
  function connect() {
    try {
      const newPort = browser.runtime.connectNative('ja_source_status');
      port = newPort;
      newPort.onDisconnect.addListener(() => {
        if (port !== newPort) return;
        port = null;
        scheduleReconnect();
      });
      flushPending();
    } catch (_) {
      port = null;
      scheduleReconnect();
    }
  }
  connect();

  const visibleVideo = (v) => {
    if (!(v instanceof HTMLVideoElement) || !v.isConnected) return false;
    const r = v.getBoundingClientRect();
    if (r.width < 160 || r.height < 90 || r.width < innerWidth * 0.25) return false;
    const css = getComputedStyle(v);
    return css.display !== 'none' && css.visibility !== 'hidden';
  };
  const videos = () => Array.from(document.querySelectorAll('video')).filter(visibleVideo);

  const reportStarted = (v) => {
    if (reportedStarted || !visibleVideo(v) || v.paused || v.ended || v.readyState < 2) return;
    const now = performance.now();
    const earlier = progress.get(v);
    if (!earlier) {
      progress.set(v, { t: v.currentTime, at: now });
    } else if (now - earlier.at >= 1000 && v.currentTime - earlier.t >= 0.5) {
      reportedStarted = true;
      send('VIDEO_STARTED', 'TIME_ADVANCING');
    }
  };

  const mediaError = (v) => {
    if (reportedStarted || reportedUnavailable || !visibleVideo(v)) return;
    const code = v.error?.code;
    // 4 = MEDIA_ERR_SRC_NOT_SUPPORTED. 3 = MEDIA_ERR_DECODE.
    // Network stalls and transient request errors are NOT hard failures.
    if (code !== 4 && code !== 3) return;
    if (fatalTimers.has(v)) return;
    const t = setTimeout(() => {
      fatalTimers.delete(v);
      if (reportedStarted || reportedUnavailable || !visibleVideo(v)) return;
      if (v.error?.code !== code || videos().some(other => other !== v && !other.paused && other.readyState >= 2)) return;
      reportedUnavailable = true;
      send('MEDIA_UNAVAILABLE', `VIDEO_ERROR_${code}`);
    }, code === 4 ? 1600 : 2800);
    fatalTimers.set(v, t);
  };

  document.addEventListener('playing', e => {
    const v = e.target;
    if (v instanceof HTMLVideoElement) progress.set(v, { t: v.currentTime, at: performance.now() });
  }, true);
  document.addEventListener('timeupdate', e => {
    if (e.target instanceof HTMLVideoElement) reportStarted(e.target);
  }, true);
  document.addEventListener('error', e => {
    if (e.target instanceof HTMLVideoElement) mediaError(e.target);
  }, true);

  // Fast, low-cost checks for the first 18 seconds. Once the primary has
  // either started or failed, monitoring stops. Otherwise continue sampling
  // periodically without declaring an unavailable title merely from time.
  const timer = setInterval(() => {
    if (reportedStarted || reportedUnavailable || ++scanNumber > 150) {
      clearInterval(timer); return;
    }
    const found = videos();
    found.forEach(v => { reportStarted(v); if (v.error) mediaError(v); });
    checkVisibleFailure();
  }, 450);
})();
