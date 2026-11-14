// VidFast's documented postMessage API. Runs only in the official top-level embed.
(() => {
  'use strict';
  const ORIGIN='https://vidfast.vc';
  if (window!==window.top || location.origin!==ORIGIN || globalThis.__jaVidFast18) return;
  globalThis.__jaVidFast18=true;
  let port=null, config=null, latest=null, lastStateAt=-Infinity, lastPostedAt=-Infinity;
  let resumeApplied=false, resumeAttempts=0, lastResumeAt=-Infinity;
  let pending=null, controlQueue=[], nextControl=0, alive=true, retries=0;
  const EVENTS=new Set(['play','pause','seeked','ended','timeupdate','playerstatus']);
  const frames=() => Array.from(document.querySelectorAll('iframe')).filter(frame => {
    try {
      const rect=frame.getBoundingClientRect();
      return new URL(frame.src,location.href).origin===ORIGIN && rect.width>=160 && rect.height>=90;
    }catch(_){return false;}
  });
  const trustedSource=e => e.origin===ORIGIN && (e.source===window || frames().some(f=>f.contentWindow===e.source));
  const send=(event, extra={}) => {
    if(!port || !config || !alive)return;
    try {port.postMessage({event,token:config.token,...extra});}catch(_){}
  };
  const post=command => {
    if(!alive)return;
    window.postMessage(command,ORIGIN);
    for(const frame of frames())try {frame.contentWindow.postMessage(command,ORIGIN);}catch(_){}
  };
  const localVideo=() => {
    const videos=Array.from(document.querySelectorAll('video'));
    for(const frame of frames())try {videos.push(...frame.contentDocument.querySelectorAll('video'));}catch(_){}
    return videos.find(v=>{
      try {const r=v.getBoundingClientRect();return v.isConnected && r.width>=160 && r.height>=90 && v.readyState>=2;}catch(_){return false;}
    }) || null;
  };
  const domAction=desired => {
    const video=localVideo();if(!video)return false;
    try {
      if(desired.command==='play') {
        if(video.paused)video.play()?.catch?.(()=>{});
      }else if(desired.command==='pause') {if(!video.paused)video.pause();}
      else {
        const ranges=video.seekable;let allowed=false;
        for(let i=0;i<(ranges?.length||0);i++)if(desired.time>=ranges.start(i)&&desired.time<=ranges.end(i))allowed=true;
        if(!allowed)return false;
        if(Math.abs(video.currentTime-desired.time)>0.2)video.currentTime=desired.time;
      }
      return true;
    }catch(_){return false;}
  };
  const complete=(request,result) => {
    if(pending!==request)return;
    clearTimeout(request.timer);pending=null;
    send('CONTROL_RESULT',{action:request.action,result});pump();
  };
  const pump=() => {
    if(pending || !controlQueue.length || !alive)return;
    const action=controlQueue.shift();
    if(!latest || performance.now()-lastStateAt>5000){send('CONTROL_RESULT',{action,result:'stale_status'});pump();return;}
    const desired=action==='toggle' ? {command:latest.playing?'pause':'play'} :
      {command:'seek',time:Math.max(0,Math.min(latest.duration,latest.currentTime+(action==='back10'?-10:10)))};
    const request={id:++nextControl,action,desired};pending=request;
    request.timer=setTimeout(()=>{
      // Apply the desired absolute state, never blindly toggle/seek twice after a late API response.
      const applied=domAction(desired);
      complete(request,applied?'html5_fallback':'unconfirmed');
    },900);
    post(desired);post({command:'getStatus'});
  };
  const tryResume=() => {
    if(!latest || !config || resumeApplied)return;
    const target=Math.max(0,config.positionMs/1000);
    if(Math.abs(latest.currentTime-target)<=1.5) {
      resumeApplied=true;send('RESUME_RESULT',{applied:true});return;
    }
    if(target>latest.duration || resumeAttempts>=3 || performance.now()-lastResumeAt<2000)return;
    lastResumeAt=performance.now();resumeAttempts++;
    post({command:'seek',time:target});post({command:'getStatus'});
  };
  const receive=e => {
    if(!config || !trustedSource(e) || e.data?.type!=='PLAYER_EVENT')return;
    const d=e.data.data;
    if(!d || !EVENTS.has(d.event))return;
    if(d.tmdbId!==config.tmdbId || d.mediaType!==config.mediaType ||
      (config.mediaType==='tv' && (d.season!==config.season || d.episode!==config.episode))) {
      latest=null;lastStateAt=-Infinity;controlQueue=[];
      if(pending)clearTimeout(pending.timer);pending=null;
      send('INVALIDATED');return;
    }
    if(typeof d.currentTime!=='number' || !Number.isFinite(d.currentTime) || d.currentTime<0 ||
      typeof d.duration!=='number' || !Number.isFinite(d.duration) || d.duration<=0 || d.currentTime>d.duration+2 ||
      !Number.isSafeInteger(Math.round(d.duration*1000)) || typeof d.playing!=='boolean')return;
    latest={currentTime:d.currentTime,duration:d.duration,playing:d.playing,event:d.event};lastStateAt=performance.now();
    tryResume();
    if(pending) {
      const request=pending, desired=request.desired;
      const matches=desired.command==='seek' ? Math.abs(d.currentTime-desired.time)<=1.5 : d.playing===(desired.command==='play');
      if(matches)complete(request,'api_confirmed');
    }
    const force=d.event==='play'||d.event==='pause'||d.event==='seeked'||d.event==='ended';
    if(force || performance.now()-lastPostedAt>=900) {
      lastPostedAt=performance.now();
      send('STATE',{tmdbId:config.tmdbId,mediaType:config.mediaType,season:config.season,episode:config.episode,
        playerEvent:d.event,currentTime:d.currentTime,duration:d.duration,playing:d.playing,resumeApplied});
    }
  };
  window.addEventListener('message',receive);
  const connect=() => {
    if(!alive)return;
    try {
      const incoming=browser.runtime.connectNative('ja_vidfast_api');port=incoming;
      incoming.onMessage.addListener(message=>{
        if(!alive || port!==incoming)return;
        if(message?.action==='configure') {
          const path=location.pathname.split('/').filter(Boolean);
          if(!Number.isSafeInteger(message.tmdbId) || message.tmdbId<=0 || path[0]!==message.mediaType ||
            Number(path[1])!==message.tmdbId ||
            (message.mediaType==='tv' && (Number(path[2])!==message.season || Number(path[3])!==message.episode)))return;
          if(pending)clearTimeout(pending.timer);pending=null;controlQueue=[];
          config=message;latest=null;lastStateAt=-Infinity;lastPostedAt=-Infinity;resumeAttempts=0;lastResumeAt=-Infinity;
          resumeApplied=message.positionMs<=0 && message.restart!==true;
          post({command:'getStatus'});
        }else if(message?.action==='control' && config && message.token===config.token &&
          ['toggle','back10','forward10'].includes(message.control) && controlQueue.length<16) {
          controlQueue.push(message.control);pump();
        }
      });
      incoming.onDisconnect.addListener(()=>{
        if(port!==incoming)return;port=null;config=null;latest=null;lastStateAt=-Infinity;
        if(pending)clearTimeout(pending.timer);pending=null;controlQueue=[];
        if(alive && ++retries<=4)setTimeout(connect,1200);
      });
    }catch(_){if(alive && ++retries<=4)setTimeout(connect,1200);}
  };
  connect();
  const poll=setInterval(()=>{
    if(config && performance.now()-lastStateAt>2500)post({command:'getStatus'});
  },2000);
  window.addEventListener('pagehide',()=>{
    alive=false;clearInterval(poll);if(pending)clearTimeout(pending.timer);
    controlQueue=[];window.removeEventListener('message',receive);
    try{port?.disconnect();}catch(_){}
  },{once:true});
})();
