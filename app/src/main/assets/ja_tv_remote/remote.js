// Independent from ja_autoplay/autoplay.js. Diagnostic results go to Android logcat.
(() => {
  'use strict';
  if (globalThis.__jaTvRemote105) return;
  globalThis.__jaTvRemote105 = true;
  let port;
  let attempts = 0;

  function videoInfo() {
    const videos = Array.from(document.querySelectorAll('video'));
    const visible = videos.filter(v => {
      const r = v.getBoundingClientRect();
      const style = getComputedStyle(v);
      return r.width > 0 && r.height > 0 && style.visibility !== 'hidden' && style.display !== 'none';
    });
    const playing = visible.find(v => !v.paused && !v.ended);
    return { count: videos.length, video: playing || visible[0] || null, playing: !!playing };
  }

  function report(obj) {
    try { port?.postMessage({frame: location.hostname, ...obj}); } catch (_) {}
  }

  async function handle(message) {
    const action = message && message.action;
    if (!['toggle', 'back10', 'forward10'].includes(action)) return;
    const info = videoInfo();
    const video = info.video;
    if (!video) {
      report({event: 'NO_VIDEO', action, count: info.count});
      return;
    }
    // Only control a frame with a playing video; a paused video can be targeted
    // for Play, but only when it is visible. Other iframe reports remain diagnostic.
    try {
      if (action === 'toggle') {
        if (video.paused) await video.play(); else video.pause();
      } else if (Number.isFinite(video.duration) && video.seekable.length > 0) {
        const delta = action === 'back10' ? -10 : 10;
        const end = video.seekable.end(video.seekable.length - 1);
        const start = video.seekable.start(0);
        video.currentTime = Math.min(end, Math.max(start, video.currentTime + delta));
      } else {
        report({event: 'NOT_SEEKABLE', action, readyState: video.readyState});
        return;
      }
      report({event: 'ACTION_OK', action, paused: video.paused,
        position: Math.round(video.currentTime), readyState: video.readyState});
    } catch (e) {
      report({event: 'ACTION_ERROR', action, error: e?.name || String(e)});
    }
  }

  function connect() {
    if (port || attempts++ >= 25) return;
    try {
      const candidate = browser.runtime.connectNative('ja_tv_remote');
      candidate.onMessage.addListener(handle);
      candidate.onDisconnect.addListener(() => {
        port = null;
        if (attempts < 25) setTimeout(connect, 1000);
      });
      port = candidate;
      const info = videoInfo();
      report({event: 'FRAME_READY', count: info.count, playing: info.playing});
    } catch (_) {
      setTimeout(connect, 1000);
    }
  }
  connect();
})();
