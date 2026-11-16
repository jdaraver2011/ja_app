/* Capability detection and bounded, sanitized JW API telemetry for the active video. */
var JaJwApi = (() => {
  const methods=['getContainer','getQualityLevels','getCurrentQuality','setCurrentQuality',
    'getVisualQuality','getCaptionsList','getCurrentCaptions','setCurrentCaptions','on','off'];
  const numeric=v=>{if(v===null||v===undefined)return null;const n=Number(v);return Number.isFinite(n)&&n>=0&&n<=1e10?n:null;};
  function level(v){return {index:numeric(v?.index),width:numeric(v?.width),height:numeric(v?.height),
    bitrate:numeric(v?.bitrate),label:/^(auto|\d{3,4}p|4k)$/i.test(String(v?.label||''))?String(v.label).slice(0,10):'other'};}
  function visual(player){
    try{if(typeof player.getVisualQuality!=='function')return null;const v=player.getVisualQuality();
      if(!v)return null;
      return {mode:['auto','manual'].includes(v.mode)?v.mode:'unknown',
        reason:['auto','api','initial choice'].includes(v.reason)?v.reason:'unknown',level:level(v.level)};
    }catch(_){return null;}
  }
  function create({page,doc,report,exportCallback=null,current=null}){
    const adapters=new WeakMap(),subscriptions=[];let active=true,eventCount=0;
    function emit(event,detail){if(active)report(event,detail);}
    function inspectPlayer(player){
      let selected=null,levels=[];
      try{selected=numeric(player.getCurrentQuality?.());}catch(_){}
      try{const list=player.getQualityLevels?.();for(let i=0;i<Math.min(Number(list?.length)||0,32);i++)levels.push({...level(list[i]),index:i});}catch(_){}
      return {selected,levels,visual:visual(player)};
    }
    function adapter(player,scope){
      if(adapters.has(player))return adapters.get(player);
      const caps={};for(const name of methods){try{caps[name]=typeof player[name]==='function';}catch(_){caps[name]=false;}}
      emit('JW_API_CAPABILITIES',{methods:caps});
      const result={qualityEvidence:'JW_API_SELECTION',requiresVisualQuality:true,
        getVisualQuality:()=>visual(player),getDiagnostics:()=>inspectPlayer(player)};
      for(const name of ['getQualityLevels','getCurrentQuality','setCurrentQuality','getCaptionsList','getCurrentCaptions','setCurrentCaptions'])
        if(caps[name])result[name]=(...args)=>player[name](...args);
      adapters.set(player,result);
      if(caps.on && caps.off && exportCallback){
        for(const name of ['levels','levelsChanged','visualQuality']){
          try{
            let last=0;
            const callback=exportCallback(()=>{
              const time=Date.now();if(!active||eventCount>=32||time-last<100)return;
              try{if(current && !player.getContainer()?.contains(current()))return;}catch(_){return;}
              last=time;eventCount++;emit('JW_QUALITY_EVENT',{type:name,...inspectPlayer(player)});
            },scope);
            player.on(name,callback);subscriptions.push({player,name,callback});
          }catch(_){emit('JW_EVENTS_UNAVAILABLE',{reason:'EVENT_REGISTRATION_FAILED'});}
        }
      }else emit('JW_EVENTS_UNAVAILABLE',{reason:'POLLING_ONLY'});
      return result;
    }
    function discover(video){
      if(!active||!video)return null;
      try{
        const scope=page();const factory=scope?.jwplayer;
        const version=typeof factory?.version==='string'?factory.version.replace(/[^a-zA-Z0-9.\-]/g,'').slice(0,40):'unknown';
        emit('JW_API_PROBE',{globalType:typeof factory,version});
        if(typeof factory!=='function'){emit('JW_API_UNAVAILABLE',{reason:'NO_JW_GLOBAL'});return null;}
        const ids=[];let ancestor=video;
        for(let depth=0;ancestor&&depth<10;depth++,ancestor=ancestor.parentElement)if(ancestor.id)ids.push(ancestor.id);
        for(const el of Array.from(doc.querySelectorAll('.jwplayer[id]')).slice(0,16))ids.push(el.id);
        const candidates=[...new Set(ids),0,1,2,3,4,5,6,7,undefined];
        for(const id of candidates){
          try{
            const player=id===undefined?factory.call(scope):factory.call(scope,id);
            if(player?.getContainer?.()?.contains(video))return adapter(player,scope);
          }catch(_){}
        }
        emit('JW_API_UNAVAILABLE',{reason:'NO_MATCHING_CONTAINER'});
      }catch(_){emit('JW_API_UNAVAILABLE',{reason:'PAGE_ACCESS_FAILED'});}
      return null;
    }
    function inspect(video){
      const player=discover(video);if(player)return {adapter:'JW_API',...player.getDiagnostics()};
      // Only detect known library presence, never inspect arbitrary window properties or URLs.
      const libraries={};
      try{const scope=page();for(const name of ['Hls','dashjs','videojs'])libraries[name]=typeof scope?.[name];}catch(_){}
      return {adapter:'CUSTOM_OR_UNKNOWN',reason:'NO_MATCHING_JW_API',libraries};
    }
    function dispose(){active=false;for(const {player,name,callback} of subscriptions){try{player.off(name,callback);}catch(_){}}subscriptions.length=0;}
    return {discover,inspect,dispose};
  }
  return {create,visual};
})();
if(typeof module!=='undefined')module.exports=JaJwApi;
