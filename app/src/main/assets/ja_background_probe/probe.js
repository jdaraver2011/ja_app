/*
 * JA detached-session media probe. This script is read-only about the source
 * URLs and never clicks ads. It requests muted playback to avoid audible
 * background video, and reports only sustained <video> time progression.
 * The native host ignores messages from regular, visible player sessions.
 * A probe signal is tentative: a video advertisement could also advance.
 */
(() => {
  'use strict';
  if (globalThis.__jaBackgroundProbe) return;
  globalThis.__jaBackgroundProbe = true;
  let port;
  let armed = false; // Foreground GeckoSessions never receive ARM_PROBE.
  let sent = false;
  let stopped = false;
  const videoState = new WeakMap();
  const muteElement = (media) => {
    if (!(media instanceof HTMLMediaElement)) return;
    try { media.muted = true; media.volume = 0; media.defaultMuted = true; } catch (_) {}
  };
  // On a headless GeckoSession, geometry can be 0. Use decoded media
  // dimensions instead of visible-frame geometry as foreground JA does.
  const consideredVideo = (v) => v instanceof HTMLVideoElement &&
    (v.videoWidth >= 160 || v.width >= 160) &&
    (v.videoHeight >= 90 || v.height >= 90) &&
    v.readyState >= 2 && !v.ended && !v.paused;
  const report = (event, reason) => {
    if (!armed || stopped || sent || !port) return;
    try {
      port.postMessage({event, reason, frame: location.hostname});
      sent = true;
    } catch (_) {}
  };
  const scan = () => {
    if (!armed || stopped || sent) return;
    const now = performance.now();
    for (const media of document.querySelectorAll('video,audio')) {
      muteElement(media);
      if (!(media instanceof HTMLVideoElement)) continue;
      if (!consideredVideo(media)) continue;
      const before = videoState.get(media);
      if (!before) videoState.set(media, {t: media.currentTime, at: now});
      else if (now - before.at >= 1500 && media.currentTime - before.t >= 1.2) {
        report('VIDEO_STARTED', 'BACKGROUND_TIME_ADVANCING');
        break;
      }
    }
  };
  const connect = () => {
    try {
      const p = browser.runtime.connectNative('ja_background_probe');
      port = p;
      p.onMessage.addListener((message) => {
        if (message && message.action === 'ARM_PROBE') {
          armed = true;
          scan();
        }
      });
      p.onDisconnect.addListener(() => { port = null; stopped = true; });
    } catch (_) { stopped = true; }
  };
  connect();
  const observer = new MutationObserver(() => scan());
  try { observer.observe(document, {childList: true, subtree: true}); } catch (_) {}
  const poll = setInterval(() => {
    scan();
    if (stopped || sent) {
      clearInterval(poll);
      observer.disconnect();
    }
  }, 350);
  // Don't manufacture user clicks or override embed media control logic.
  // The already-installed JA autoplay extension can act when it is permitted.
})();
