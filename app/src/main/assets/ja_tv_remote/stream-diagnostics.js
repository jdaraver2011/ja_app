// Read-only, bounded startup and playback diagnostics for embedded streams.
// Runs separately at document_start so the remote-control content script keeps
// its existing document_idle timing and behavior.
(() => {
  'use strict';
  if (globalThis.__jaStreamDiagnosticsStarted) return;
  globalThis.__jaStreamDiagnosticsStarted = true;

  const START = performance.now();
  const MAX_MS = 10 * 60 * 1000;
  const MAX_EVENTS = 500;
  const EVENT_NAMES = ['loadedmetadata','resize','waiting','stalled','playing','pause',
    'seeking','emptied','ended','error'];
  const JW_EVENTS = ['ready','firstFrame','levels','levelsChanged','visualQuality',
    'adBreakStart','adBreakEnd','error','warning','setupError'];
  const page = window.wrappedJSObject;
  const exported = typeof exportFunction === 'function';
  let port = null, observer = null, discoveryTimer = null, sampleTimer = null, hardStopTimer = null;
  let eventCount = 0, connectAttempts = 0, stopped = false, attachedVideo = null, attachedPlayer = null;
  let frameCallbackId = null, lastProgressAt = 0, stallReported = false;
  let lastFrameCount = null, lastTime = null, retry = 0;
  const knownVideos = new WeakSet(), knownPlayers = new WeakSet(), pending = [];

  function connect() {
    if (stopped || port || connectAttempts++ >= 25) return;
    try {
      const candidate = browser.runtime.connectNative('ja_tv_remote');
      candidate.onDisconnect.addListener(() => { port = null; setTimeout(connect, 500); });
      port = candidate;
      for (const entry of pending.splice(0)) try { port.postMessage(entry); } catch (_) {}
    } catch (_) { setTimeout(connect, 500); }
  }

  function safePageUrl() {
    try { const u = new URL(location.href); return u.origin + u.pathname; }
    catch (_) { return ''; }
  }
  function playerId(player, index) {
    try {
      const container = player.getContainer?.();
      const id = String(container?.id || '');
      return id.replace(/[^a-zA-Z0-9_.:-]/g, '').slice(0, 80) || `player-${index}`;
    } catch (_) { return `player-${index}`; }
  }
  function numeric(value) {
    if (value === null || value === undefined || value === '') return null;
    const n = Number(value);
    return Number.isFinite(n) && n >= 0 && n <= 1e10 ? n : null;
  }
  function copyLevel(level, index) {
    try {
      return {index, height:numeric(level?.height), width:numeric(level?.width),
        bitrate:numeric(level?.bitrate), label:String(level?.label || '').slice(0, 32)};
    } catch (_) { return {index, height:null, width:null, bitrate:null, label:''}; }
  }
  function videoSnapshot(video) {
    const ranges = [];
    try {
      const buffered = video.buffered;
      for (let i = 0; i < Math.min(buffered.length, 8); i++)
        ranges.push([Number(buffered.start(i).toFixed(3)), Number(buffered.end(i).toFixed(3))]);
    } catch (_) {}
    let quality = null;
    try {
      const q = video.getVideoPlaybackQuality?.();
      if (q) quality = {total:numeric(q.totalVideoFrames), dropped:numeric(q.droppedVideoFrames),
        corrupted:numeric(q.corruptedVideoFrames)};
    } catch (_) {}
    return {currentTime:numeric(video.currentTime), readyState:video.readyState,
      networkState:video.networkState, buffered:ranges, width:video.videoWidth,
      height:video.videoHeight, paused:video.paused, ended:video.ended, quality};
  }
  function emit(event, detail = {}) {
    if (stopped) return;
    if (eventCount >= MAX_EVENTS || performance.now() - START >= MAX_MS) { stop(); return; }
    eventCount++;
    const entry = {event, wallMs:Date.now(), monoMs:Number(performance.now().toFixed(2)),
      frameUrl:safePageUrl(), playerId:attachedPlayer?.id || '', ...detail};
    if (port) try { port.postMessage(entry); } catch (_) { pending.push(entry); }
    else pending.push(entry);
    if (pending.length > MAX_EVENTS) pending.shift();
  }
  function inspectPlayer(player, id, video) {
    const state = (() => { try { return String(player.getState?.() || 'unknown').slice(0, 24); } catch (_) { return 'unknown'; } })();
    let current = null, visual = null, levels = [];
    try { current = numeric(player.getCurrentQuality?.()); } catch (_) {}
    try {
      const v = player.getVisualQuality?.();
      if (v) visual = {mode:String(v.mode || '').slice(0, 24), reason:String(v.reason || '').slice(0, 32),
        level:copyLevel(v.level, numeric(v.level?.index))};
    } catch (_) {}
    try {
      const list = player.getQualityLevels?.() || [];
      for (let i = 0; i < Math.min(Number(list.length) || 0, 32); i++) levels.push(copyLevel(list[i], i));
    } catch (_) {}
    emit('JW_INITIAL', {playerId:id, jwState:state, currentQuality:current,
      visualQuality:visual, qualityLevels:levels, video:videoSnapshot(video)});
  }
  function attachPlayer(player, id, video) {
    if (!player || knownPlayers.has(player) || typeof player.on !== 'function') return;
    knownPlayers.add(player);
    attachedPlayer = {api:player, id};
    inspectPlayer(player, id, video);
    if (!exported) return;
    for (const name of JW_EVENTS) {
      try {
        const callback = exportFunction((value) => {
          let detail = {};
          try {
            const level = value?.level;
            detail = {jwState:String(player.getState?.() || 'unknown').slice(0, 24),
              quality:level ? copyLevel(level, numeric(level.index)) : null,
              currentQuality:numeric(player.getCurrentQuality?.()),
              visualQuality:(() => { const v=player.getVisualQuality?.(); return v ? {
                mode:String(v.mode || '').slice(0,24),reason:String(v.reason || '').slice(0,32),
                level:copyLevel(v.level,numeric(v.level?.index))} : null; })()};
            if (value?.message) detail.message = String(value.message).slice(0, 160);
            if (value?.code != null) detail.code = String(value.code).slice(0, 48);
          } catch (_) {}
          emit('JW_' + name, {playerId:id, ...detail, video:videoSnapshot(video)});
        }, window);
        player.on(name, callback);
      } catch (_) {}
    }
  }
  function frameProgress(video, frames, time) {
    if (frames !== lastFrameCount || time !== lastTime) {
      lastFrameCount = frames; lastTime = time; lastProgressAt = performance.now();
      if (stallReported) { stallReported = false; emit('FRAME_RECOVERED', {video:videoSnapshot(video)}); }
    }
  }
  function videoFrame(video, now, metadata) {
    if (stopped || video !== attachedVideo || video.paused || document.hidden) return;
    frameProgress(video, numeric(metadata?.presentedFrames), numeric(video.currentTime));
    try { frameCallbackId = video.requestVideoFrameCallback((t, m) => videoFrame(video, t, m)); }
    catch (_) { frameCallbackId = null; }
  }
  function attachVideo(video) {
    if (!video) return;
    if (attachedVideo && attachedVideo !== video && frameCallbackId != null) {
      try { attachedVideo.cancelVideoFrameCallback?.(frameCallbackId); } catch (_) {}
      frameCallbackId = null;
    }
    attachedVideo = video;
    if (!knownVideos.has(video)) {
      knownVideos.add(video);
      lastProgressAt = performance.now(); lastFrameCount = null; lastTime = null; stallReported = false;
      for (const name of EVENT_NAMES) video.addEventListener(name, () => {
        if (name === 'playing') {
          lastProgressAt = performance.now(); stallReported = false;
          if (!sampleTimer) sampleTimer = setInterval(samplePlayback, 250);
          if (typeof video.requestVideoFrameCallback === 'function' && frameCallbackId == null) {
            try { frameCallbackId = video.requestVideoFrameCallback((t, m) => videoFrame(video, t, m)); } catch (_) {}
          }
        }
        if (name === 'pause' || name === 'ended') {
          clearInterval(sampleTimer); sampleTimer = null;
          if (frameCallbackId != null) try { video.cancelVideoFrameCallback?.(frameCallbackId); } catch (_) {}
          frameCallbackId = null;
        }
        const detail = {mediaEvent:name, video:videoSnapshot(video)};
        if (name === 'error') {
          try { detail.mediaError = {code:video.error?.code || 0, message:String(video.error?.message || '').slice(0, 120)}; } catch (_) {}
        }
        emit('MEDIA_EVENT', detail);
      }, {passive:true});
      emit('MEDIA_ATTACHED', {video:videoSnapshot(video)});
    }
    const factory = page?.jwplayer;
    if (typeof factory === 'function') {
      const candidates = [];
      try {
        for (const el of Array.from(document.querySelectorAll('.jwplayer[id]')).slice(0, 12)) {
          try { candidates.push({player:factory.call(page, el.id), id:el.id}); } catch (_) {}
        }
      } catch (_) {}
      try { candidates.push({player:factory.call(page), id:''}); } catch (_) {}
      let index = 0;
      for (const candidate of candidates) {
        try {
          const container = candidate.player?.getContainer?.();
          if (container?.contains(video)) { attachPlayer(candidate.player, playerId(candidate.player, index), video); break; }
        } catch (_) {}
        index++;
      }
    }
  }
  function scanVideos() {
    try { for (const video of document.querySelectorAll('video')) attachVideo(video); } catch (_) {}
  }
  function samplePlayback() {
    const video = attachedVideo;
    if (!video || video.paused || video.ended || video.readyState < 3) return;
    let frames = null;
    try { frames = numeric(video.getVideoPlaybackQuality?.()?.totalVideoFrames); } catch (_) {}
    frameProgress(video, frames, numeric(video.currentTime));
    if (!stallReported && performance.now() - lastProgressAt >= 500) {
      stallReported = true;
      emit('FRAME_STALL', {stalledMs:Math.round(performance.now() - lastProgressAt), video:videoSnapshot(video)});
    }
  }
  function stop() {
    if (stopped) return;
    stopped = true;
    try { observer?.disconnect(); } catch (_) {}
    clearTimeout(discoveryTimer); clearInterval(sampleTimer);
    clearTimeout(hardStopTimer);
    if (frameCallbackId != null) try { attachedVideo?.cancelVideoFrameCallback?.(frameCallbackId); } catch (_) {}
    try { port?.disconnect(); } catch (_) {}
  }
  function discover() {
    if (stopped || performance.now() - START >= MAX_MS) return stop();
    scanVideos();
    if (attachedPlayer || retry >= 18) { clearInterval(discoveryTimer); discoveryTimer = null; return; }
    const delays = [50,75,100,150,200,250,350,500,650,800,1000,1000,1000,1000,1000,1000,1000,1000];
    clearTimeout(discoveryTimer);
    discoveryTimer = setTimeout(() => { retry++; discover(); }, delays[Math.min(retry, delays.length - 1)]);
  }
  function start() {
    connect();
    try {
      const root = document.documentElement || document;
      observer = new MutationObserver(records => {
        for (const record of records) {
          if (record.type === 'childList') {
            for (const node of record.addedNodes) {
              if (node.nodeType !== 1) continue;
              try { if (node.matches?.('video,.jwplayer') || node.querySelector?.('video,.jwplayer')) { scanVideos(); return; } } catch (_) {}
            }
          } else if (record.target?.nodeType === 1 && record.target.closest?.('video,.jwplayer')) {
            scanVideos(); return;
          }
        }
      });
      observer.observe(root, {childList:true, subtree:true});
    } catch (_) {}
    discover();
    hardStopTimer = setTimeout(stop, MAX_MS);
  }
  window.addEventListener('pagehide', stop, {once:true});
  if (document.documentElement) start(); else document.addEventListener('DOMContentLoaded', start, {once:true});
})();
