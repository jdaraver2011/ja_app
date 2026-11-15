const assert = require('node:assert/strict');
const {select, create} = require('../app/src/main/assets/ja_tv_remote/output-quality.js');
const levels = [{label:'Auto',height:1080},{label:'360p'},{label:'720p'},{label:'1080p'}];
assert.equal(select(levels,1080).index,3);
assert.equal(select(levels,900).index,2);
assert.equal(select(levels,2160).index,3);
assert.equal(select([{label:'2160p'}],1080),null);
assert.equal(select([{label:'Auto',height:1080}],1080),null);
assert.equal(select([{width:1920,height:800}],1080).resolution,1080);
assert.equal(select([{width:3840,height:1600}],1080),null);
assert.equal(select([{label:'4K',width:3840,height:2160}],2160).index,0);
function fixture({accept=true,available=true,busy=false}={}) {
 let time=0, timer=null, index=0, calls=0;
 const events=[];
 const v={paused:false,ended:false,readyState:4,videoWidth:1920,videoHeight:1080,currentTime:10};
 let active=v;
 const player={getQualityLevels:()=>levels,getCurrentQuality:()=>index,setCurrentQuality:i=>{calls++;if(accept)index=i;}};
 const q=create({video:()=>active,api:()=>available?player:null,report:e=>events.push(e),userBusy:()=>busy,
 now:()=>time,schedule:fn=>{timer=fn;return 1;},unschedule:()=>{timer=null;}});
 return {q,v,events,get calls(){return calls;},get pending(){return !!timer;},setIndex:i=>index=i,
 replace:()=>{active={...v};},step(n=1){for(let i=0;i<n;i++){time+=1000;v.currentTime+=1;const fn=timer;timer=null;fn?.();}}};
}
let f=fixture();f.q.request(1080);f.step(8);
assert.equal(f.calls,1);assert.equal(f.events.at(-1).event,'QUALITY_MATCH_VERIFIED');assert.equal(f.pending,false);
f.q.request(1080);f.step(3);assert.equal(f.calls,1);
f=fixture({available:false});f.q.request(1080);f.step(35);assert.equal(f.calls,0);assert.equal(f.events.at(-1).event,'QUALITY_MATCH_UNAVAILABLE');assert.equal(f.pending,false);
f=fixture({accept:false});f.q.request(1080);f.step(35);assert.equal(f.calls,1);assert.equal(f.events.at(-1).event,'QUALITY_MATCH_UNVERIFIED');
f=fixture({busy:true});f.q.request(1080);f.step();assert.equal(f.calls,0);assert.equal(f.events.at(-1).event,'QUALITY_MATCH_USER_CONTROL');
f=fixture();f.q.request(1080);f.step(3);f.setIndex(2);f.step();assert.equal(f.calls,1);assert.equal(f.events.at(-1).event,'QUALITY_MATCH_USER_OR_PLAYER_OVERRIDE');
f=fixture();f.q.request(1080);f.step(3);f.replace();f.step();assert.equal(f.events.at(-1).event,'QUALITY_MATCH_VIDEO_CHANGED');
f=fixture();f.q.request(1080);f.q.cancel();f.step(5);assert.equal(f.calls,0);assert.equal(f.pending,false);
f=fixture();f.q.request(0);f.q.request(9999);f.step(5);assert.equal(f.calls,0);assert.equal(f.events.length,0);
f=fixture();f.v.paused=true;f.q.request(1080);f.step(35);assert.equal(f.calls,0);assert.equal(f.pending,false);
console.log('PASS: selection, crop handling, confirmation, no API, ignored request, manual override, replacement, cancellation, invalid target and paused startup');
