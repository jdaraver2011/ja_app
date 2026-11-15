/* API-only JW quality selection. No clicks, page reloads, seeks, or HLS internals. */
var JaOutputQuality = (() => {
  'use strict';
  function choices(levels) {
    const result = [];
    for (let index = 0; index < Math.min(Number(levels?.length) || 0, 100); index++) {
      const level = levels[index], label = String(level?.label || '').trim().slice(0, 40);
      if (/auto/i.test(label)) continue;
      const explicit = /\b(\d{3,4})\s*p\b/i.exec(label);
      const width = Number(level?.width), height = Number(level?.height);
      // Respect declared labels; otherwise classify cropped widescreen by both dimensions.
      const resolution = explicit ? Number(explicit[1]) : /\b4k\b/i.test(label) ? 2160 :
        width > 1920 || height > 1080 ? 2160 : width > 1280 || height > 720 ? 1080 :
        width > 854 || height > 480 ? 720 : height;
      if (Number.isFinite(resolution) && resolution >= 144 && resolution <= 4320)
        result.push({index, resolution, label: label || `${resolution}p`});
    }
    return result;
  }
  function select(levels, target) {
    return choices(levels).filter(x => x.resolution <= target)
      .sort((a, b) => b.resolution - a.resolution || a.index - b.index)[0] || null;
  }
  function create({video, api, report, userBusy = () => false,
    now = () => Date.now(), schedule = (fn, ms) => setTimeout(fn, ms),
    unschedule = id => clearTimeout(id)}) {
    let timer = null, target = 0, started = 0, requested = null, done = true;
    let lastPosition = null, advancing = 0, verified = 0, boundVideo = null, mutations = 0, lastMutation = 0;
    const emit = (event, extra = {}) => report({event, outputHeight: target, ...extra});
    function cancel() { if (timer !== null) unschedule(timer); timer = null; done = true; }
    function finish(event, extra) { cancel(); emit(event, extra); }
    function tick() {
      timer = null;
      if (done) return;
      try {
        if (userBusy()) { finish('QUALITY_MATCH_USER_CONTROL'); return; }
        if (now() - started >= 30000) {
          finish(requested ? 'QUALITY_MATCH_UNVERIFIED' : 'QUALITY_MATCH_UNAVAILABLE',
            {reason: requested ? 'SELECTION_NOT_CONFIRMED' : 'API_OR_LEVELS_NOT_READY'}); return;
        }
        const v = video();
        if (boundVideo && v !== boundVideo) { finish('QUALITY_MATCH_VIDEO_CHANGED'); return; }
        if (!v || v.paused || v.ended || v.readyState < 3 || !v.videoWidth) { later(); return; }
        const position = Number(v.currentTime);
        advancing = lastPosition !== null && position > lastPosition && position-lastPosition < 3 ? advancing+1 : 0;
        lastPosition = position;
        if (advancing < 2) { later(); return; }
        const player = api(v);
        if (!player || typeof player.getQualityLevels !== 'function' ||
            typeof player.getCurrentQuality !== 'function' || typeof player.setCurrentQuality !== 'function') {
          later(); return;
        }
        const levels = player.getQualityLevels();
        const selected = Number(player.getCurrentQuality());
        if (!requested) {
          const choice = select(levels, target);
          if (!choice) {
            if (choices(levels).length) { finish('QUALITY_MATCH_UNAVAILABLE', {reason:'NO_LEVEL_AT_OR_BELOW_OUTPUT'}); return; }
            later(); return;
          }
          boundVideo = v;
          requested = {...choice, previous: selected};
          emit('QUALITY_MATCH_REQUESTED', {quality:choice.label, index:choice.index,
            exact:choice.resolution === target, alreadySelected:selected === choice.index});
          // At most two requests per target; retry only after a bounded settling interval.
          if (selected !== choice.index || player.forceQualityCommit === true) { player.setCurrentQuality(choice.index); mutations++; lastMutation=now(); }
        } else {
          const currentChoice = choices(levels).find(x => x.index === requested.index);
          if (!currentChoice || currentChoice.resolution !== requested.resolution) {
            finish('QUALITY_MATCH_LEVELS_CHANGED'); return;
          }
          if (selected === requested.index) {
            if (++verified >= 2) {
              finish('QUALITY_MATCH_VERIFIED', {quality:requested.label, index:selected,
                evidence:player.qualityEvidence || 'JW_API_SELECTION', videoWidth:v.videoWidth, videoHeight:v.videoHeight}); return;
            }
          } else {
            verified = 0;
            if (selected !== requested.previous) { finish('QUALITY_MATCH_USER_OR_PLAYER_OVERRIDE'); return; }
            if (mutations < 2 && now()-lastMutation >= 5000) {
              player.setCurrentQuality(requested.index);mutations++;lastMutation=now();
              emit('QUALITY_MATCH_RETRY',{quality:requested.label,attempt:mutations});
            }
          }
        }
      } catch (error) { finish('QUALITY_MATCH_API_ERROR', {error:String(error?.name || 'Error').slice(0,40)}); return; }
      later();
    }
    function later() { if (!done) timer = schedule(tick, 1000); }
    function request(height) {
      height = Number(height);
      if (!Number.isInteger(height) || height < 360 || height > 4320 || height === target) return;
      cancel(); target = height; started = now(); requested = null; boundVideo = null;
      lastPosition = null; advancing = 0; verified = 0; mutations = 0; lastMutation = 0; done = false;
      emit('QUALITY_MATCH_ARMED'); later();
    }
    return {request, cancel};
  }
  return {select, create};
})();
if (typeof module !== 'undefined') module.exports = JaOutputQuality;
