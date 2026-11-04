/*
 * JA 1.1.8 source availability reporting. Read-only; no DOM mutations,
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
    }, 7000);
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

  let scans = 0;
  const timer = setInterval(() => {
    if (reportedStarted || reportedUnavailable || scans++ > 45) { clearInterval(timer); return; }
    const found = videos();
    found.forEach(v => {
      reportStarted(v);
      if (v.error) mediaError(v);
    });
    if (reportedStarted) return;
    // Explicit no-media messages from the TOP-LEVEL player only. Generic
    // text or unrelated iframe failures must never cause automatic switching.
    if (scans > 8 && window.top === window && found.length === 0) {
      const bodyText = (document.body?.innerText || '').slice(0, 3500);
      if (/(?:\bno\s+(?:playable\s+)?(?:video\s+)?sources?\s+(?:were\s+)?found\b|\bno\s+available\s+servers?\b|\bvideo\s+(?:is\s+)?unavailable\b|\bmedia\s+not\s+found\b)/i.test(bodyText)) {
        reportedUnavailable = true;
        send('MEDIA_UNAVAILABLE', 'PLAYER_REPORTED_NO_SOURCE');
      }
    }
  }, 2000);
})();
