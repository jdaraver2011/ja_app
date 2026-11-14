'use strict';
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
const source=fs.readFileSync('app/src/main/assets/ja_vidfast_api/vidfast.js','utf8');
function fixture({origin='https://vidfast.vc',child=false,video=null,path='/movie/123'}={}) {
  let now=10000, next=0, connects=0, message, disconnected;
  const scheduled=new Map(), listeners={}, posted=[], native=[];
  const port={postMessage:d=>native.push(d),onMessage:{addListener:f=>message=f},onDisconnect:{addListener:f=>disconnected=f},disconnect:()=>{}};
  const window={addEventListener:(n,f)=>listeners[n]=f,removeEventListener:n=>delete listeners[n],postMessage:(d,o)=>posted.push({d,o})};
  window.top=child?{}:window;
  const context={window,location:{origin,href:origin+path,pathname:path},
    document:{querySelectorAll:s=>s==='video'&&video?[video]:[]},performance:{now:()=>now},URL,
    browser:{runtime:{connectNative:()=>{connects++;return port;}}},
    setTimeout:(f,ms)=>{const id=++next;scheduled.set(id,{f,at:now+ms});return id;},clearTimeout:id=>scheduled.delete(id),
    setInterval:()=>++next,clearInterval:()=>{}};
  vm.runInNewContext(source,context);
  const advance=ms=>{now+=ms;for(const [id,t]of [...scheduled])if(t.at<=now){scheduled.delete(id);t.f();}};
  const configure=(extra={})=>message({action:'configure',token:'private',tmdbId:123,mediaType:'movie',positionMs:0,restart:false,...extra});
  const event=(extra={},opts={})=>listeners.message({origin:'https://vidfast.vc',source:window,...opts,data:{type:'PLAYER_EVENT',data:{event:'timeupdate',currentTime:30,duration:100,playing:true,tmdbId:123,mediaType:'movie',...extra}}});
  const control=control=>message({action:'control',control,token:'private'});
  return {native,posted,configure,event,control,advance,listeners,get connects(){return connects;},raw:()=>message,window,disconnected};
}
assert.equal(fixture({origin:'https://vidsrc.sh'}).connects,0);
assert.equal(fixture({child:true}).connects,0);
{
  const f=fixture();f.configure();f.event();assert.equal(f.native.at(-1).event,'STATE');
  const count=f.native.length;
  f.event({}, {origin:'https://ads.invalid'});f.event({}, {source:{}});
  f.event({currentTime:NaN});f.event({duration:Infinity});f.event({playing:'true'});
  assert.equal(f.native.length,count,'reject bad origins, windows and payloads');
  f.control('toggle');assert.equal(f.posted.at(-2).d.command,'pause');
  f.event({event:'pause',playing:false});assert(f.native.some(d=>d.result==='api_confirmed'));
  f.control('back10');assert.equal(f.posted.at(-2).d.time,20);
  f.event({event:'seeked',currentTime:20});
  f.control('forward10');assert.equal(f.posted.at(-2).d.time,30);
  f.event({event:'seeked',currentTime:30});
  assert(f.posted.every(p=>p.o==='https://vidfast.vc'&&!('token'in p.d)),'private token stays in extension');
}
{
  const f=fixture();f.configure({positionMs:60000});f.event({currentTime:0});
  assert.equal(f.native.at(-1).resumeApplied,false);assert(f.posted.some(p=>p.d.command==='seek'&&p.d.time===60));
  f.event({event:'seeked',currentTime:60});assert.equal(f.native.at(-1).resumeApplied,true);
  f.advance(3000);f.event({currentTime:63});assert.equal(f.posted.filter(p=>p.d.command==='seek').length,1,'resume once');
}
{
  const f=fixture();f.configure({restart:true});f.event({currentTime:30});
  assert(f.posted.some(p=>p.d.command==='seek'&&p.d.time===0));
  f.event({event:'seeked',currentTime:0});assert.equal(f.native.at(-1).resumeApplied,true);
}
{
  const video={isConnected:true,readyState:4,paused:false,currentTime:30,getBoundingClientRect:()=>({width:640,height:360}),
    pause(){this.paused=true;this.calls=(this.calls||0)+1;},play(){this.paused=false;},seekable:{length:1,start:()=>0,end:()=>100}};
  const f=fixture({video});f.configure();f.event();f.control('toggle');video.paused=true;
  f.advance(1000);assert.equal(video.calls,undefined,'late pause cannot toggle back to playing');
  assert.equal(f.native.at(-1).result,'html5_fallback');
  f.event({event:'play',playing:true});video.paused=false;f.control('toggle');
  f.event({tmdbId:456});f.advance(1000);assert.equal(video.paused,false,'cancel pending controls for wrong title');
  assert.equal(f.native.at(-1).event,'INVALIDATED');
}
{
  const f=fixture();f.configure();f.event();f.advance(6000);f.control('toggle');
  assert.equal(f.native.at(-1).result,'stale_status');
  f.listeners.pagehide();const count=f.posted.length;f.advance(6000);assert.equal(f.posted.length,count);
}
{
  const f=fixture({path:'/tv/123/2/4'});f.configure({mediaType:'tv',season:2,episode:4});
  f.event({mediaType:'tv',season:2,episode:3});assert.equal(f.native.at(-1).event,'INVALIDATED');
  f.event({mediaType:'tv',season:2,episode:4});assert.equal(f.native.at(-1).event,'STATE');
  f.control('toggle');const count=f.native.length;f.disconnected();f.advance(1000);
  assert.equal(f.native.length,count,'disconnect cancels pending command');
}
console.log('VidFast API adapter: all checks passed');
