/* Shared discovery and bounded subtitle defaults for the confirmed video document. */
var JaJwPreferences = (() => {
  'use strict';
  function discover(video, page, doc, report) {
    if (!video) return null;
    if (typeof page?.jwplayer === 'function') {
      const ids = [video.closest?.('[id]')?.id,
        ...Array.from(doc.querySelectorAll('.jwplayer[id]')).map(x => x.id)];
      for (const id of [...new Set(ids.filter(Boolean)), 0, 1, 2, 3]) {
        try {
          const player = page.jwplayer(id);
          const container = player?.getContainer?.();
          if (container?.contains(video)) {
            report('PLAYER_ADAPTER', {adapter:'JW_API'}); return player;
          }
        } catch (_) {}
      }
    }
    // Provider-specific JW-style controls are not necessarily the JW library.
    // Accept only one video in this confirmed document; never open either menu.
    if (doc.querySelectorAll('video').length !== 1 || !doc.querySelector('.jw-btn, .jw-seek')) {
      report('PLAYER_API_UNAVAILABLE', {reason:'NO_MATCHING_JW_OR_CUSTOM_PLAYER'}); return null;
    }
    const menuRows = kind => {
      const menus = Array.from(doc.querySelectorAll('.jw-menu'));
      const menu = menus.find(m => kind === 'quality' ? /\bQuality\b/i.test(m.textContent || '') :
        /\bSubtitles?\b/i.test(m.textContent || '') && !!m.querySelector('.jw-tab'));
      if (!menu) return [];
      return Array.from(menu.querySelectorAll('.jw-row-label')).filter(x => kind === 'quality' ?
        /^(Auto|\d{3,4}p)$/i.test((x.textContent || '').trim()) : /^Off$/i.test((x.textContent || '').trim()))
        .map(x => ({label:(x.textContent || '').trim(), row:x.closest('.jw-row')})).filter(x => x.row);
    };
    const selected = row => row.classList.contains('active') || row.getAttribute('aria-checked') === 'true' ||
      row.getAttribute('aria-selected') === 'true';
    report('PLAYER_ADAPTER', {adapter:'CUSTOM_JW_ROWS'});
    return {
      getQualityLevels: () => menuRows('quality').map(x => ({label:x.label})),
      getCurrentQuality: () => menuRows('quality').findIndex(x => selected(x.row)),
      setCurrentQuality: index => { const item=menuRows('quality')[index]; if(item)item.row.click(); },
      getCaptionsList: () => menuRows('captions').length ? [{id:'off'},{id:'custom'}] : [],
      getCurrentCaptions: () => {const item=menuRows('captions')[0];return item && selected(item.row) ? 0 : -1;},
      setCurrentCaptions: index => {if(index===0)menuRows('captions')[0]?.row.click();}
    };
  }
  function captions({video, api, report, userBusy, now=()=>Date.now(),
    schedule=(fn,ms)=>setTimeout(fn,ms), unschedule=id=>clearTimeout(id)}) {
    let timer=null, active=false, started=0, bound=null, attempts=0, stable=0, lastRequest=0, verified=false;
    function cancel(){if(timer!==null)unschedule(timer);timer=null;active=false;}
    const emit=(event,extra={})=>report({event,...extra});
    function tick(){
      timer=null;if(!active)return;
      if(userBusy()){emit('CAPTIONS_USER_OVERRIDE');cancel();return;}
      if(now()-started>=30000){emit(verified?'CAPTIONS_DEFAULT_COMPLETE':'CAPTIONS_UNVERIFIED',{attempts});cancel();return;}
      try{
        const v=video();
        if(bound && v!==bound){emit('CAPTIONS_VIDEO_CHANGED');cancel();return;}
        if(v && v.readyState>=1){
          bound=v;
          const p=api(v);
          if(p && typeof p.getCaptionsList==='function' && typeof p.getCurrentCaptions==='function' &&
            typeof p.setCurrentCaptions==='function' && Number(p.getCaptionsList()?.length)>1){
            const tracks=Array.from(v.textTracks||[]);
            const showing=tracks.some(t=>t.mode==='showing');
            if(Number(p.getCurrentCaptions())===0 && !showing){
              if(++stable>=2 && !verified){verified=true;emit('CAPTIONS_OFF_VERIFIED',{attempts});}
            }else{
              stable=0;verified=false;
              if(attempts<3 && now()-lastRequest>=3000){
                attempts++;lastRequest=now();p.setCurrentCaptions(0);
                tracks.forEach(t=>{if(t.kind==='subtitles'||t.kind==='captions')t.mode='disabled';});
                emit('CAPTIONS_OFF_REQUESTED',{attempt:attempts});
              }
            }
          }
        }
      }catch(e){emit('CAPTIONS_API_ERROR',{error:String(e?.name||'Error')});}
      if(active)timer=schedule(tick,1000);
    }
    function start(){if(active||bound)return;active=true;started=now();emit('CAPTIONS_ARMED');timer=schedule(tick,1000);}
    return {start,cancel};
  }
  return {discover,captions};
})();
if(typeof module!=='undefined')module.exports=JaJwPreferences;
