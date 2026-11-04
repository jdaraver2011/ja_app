/*
 * JA v1.1.5e source availability reporting. Read-only; no DOM mutations,
 * autoplay clicks, URL scraping or control remapping.
 *
 * If the remote video page loads but never exposes a playable video or an
 * explicit error, the app offers a manual fallback after 40 seconds.
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
      /(^|\.)vidsrc\.sh$/.test(parentHost) ||
      /(^|\.)vidfast\.vc$/.test(parentHost);
  };

  const visibleFatalText = () => {
    if (!confirmedPlayerContext()) return '';
    const body = document.body;
    if (!body || videos().some(v => !v.paused && v.readyState >= 2)) return '';
    const value = (body.innerText || '').replace(/\s+/g, ' ').trim();
    // Real error screens are typically short. A lengthy page mentioning a
    // movie's unavailability is not proof that the player failed.
    if (value.length < 8 || value.length > 1200) return '';
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
      send('MEDIA_UNAVAILABLE', 'VISIBLE_MEDIA_UNAVAILABLE');
    }
  };

  const send = (event, reason) => {
    if (!port) return;
    try {
      port.postMessage({ event, reason, frame: location.hostname });
    } catch (_) {}
  };
  const connect = () => {
    try {
      port = browser.runtime.connectNative('ja_source_status');
      port.onDisconnect.addListener(() => {
        port = null;
        if (++attempts < 8) setTimeout(connect, 1200);
      });
    } catch (_) {
      port = null;
      if (++attempts < 8) setTimeout(connect, 1200);
    }
  };
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
