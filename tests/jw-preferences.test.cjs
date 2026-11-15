const assert=require('node:assert/strict');
const {discover,captions}=require('../app/src/main/assets/ja_tv_remote/jw-preferences.js');
const quality=require('../app/src/main/assets/ja_tv_remote/output-quality.js');
// Match the custom row structure reported in the device log, without a .jwplayer ancestor.
function row(label){let active=false;return {label,textContent:label,classList:{contains:()=>active},getAttribute:()=>null,click(){active=true;},set active(v){active=v;},closest(){return this;}};}
const qrows=['Auto','360p','720p','1080p'].map(row);qrows[0].active=true;
for(const r of qrows)r.click=()=>{qrows.forEach(x=>x.active=false);r.active=true;};
const off=row('Off');const menus=[{textContent:'Settings Quality Auto360p720p1080p',querySelectorAll:()=>qrows},{textContent:'Subtitles Options Off',querySelector:()=>({}),querySelectorAll:()=>[off]}];
const video={closest:()=>null};
const doc={querySelectorAll:s=>s==='video'?[video]:s==='.jw-menu'?menus:[],querySelector:()=>({})};
let adapter=discover(video,{},doc,()=>{});
assert.equal(adapter.getCurrentQuality(),0);adapter.setCurrentQuality(3);assert.equal(adapter.getCurrentQuality(),3);
assert.equal(adapter.getCurrentCaptions(),-1);adapter.setCurrentCaptions(0);assert.equal(adapter.getCurrentCaptions(),0);
assert.equal(quality.select(adapter.getQualityLevels(),1080).index,3);
assert.equal(discover(video,{}, {...doc,querySelectorAll:s=>s==='video'?[{},{}]:[]},()=>{}),null);
const jw={getContainer:()=>({contains:v=>v===video})};assert.equal(discover(video,{jwplayer:()=>jw},doc,()=>{}),jw);
function fixture(){let time=0,fn=null,index=1,calls=0,ready=false,busy=false;const events=[];
 const v={readyState:4,textTracks:[{kind:'subtitles',mode:'showing'}]};
 const api={getCaptionsList:()=>ready?[{},{}]:[{}],getCurrentCaptions:()=>index,setCurrentCaptions:i=>{calls++;index=i;}};
 const c=captions({video:()=>v,api:()=>api,report:e=>events.push(e),userBusy:()=>busy,now:()=>time,schedule:f=>{fn=f;return 1;},unschedule:()=>fn=null});
 return {c,v,events,ready:()=>ready=true,busy:()=>busy=true,late:()=>{index=1;v.textTracks[0].mode='showing';},get calls(){return calls;},step(n){for(let i=0;i<n;i++){time+=1000;let f=fn;fn=null;f?.();}}};}
let f=fixture();f.c.start();f.step(6);assert.equal(f.calls,0);f.ready();f.step(4);assert.equal(f.calls,1);assert.ok(f.events.some(e=>e.event==='CAPTIONS_OFF_VERIFIED'));
f.late();f.step(4);assert.equal(f.calls,2);f.busy();f.step(1);assert.equal(f.events.at(-1).event,'CAPTIONS_USER_OVERRIDE');f.late();f.step(5);assert.equal(f.calls,2);
f=fixture();f.c.start();f.step(35);assert.equal(f.events.at(-1).event,'CAPTIONS_UNVERIFIED');
f=fixture();f.c.start();f.c.cancel();f.ready();f.step(10);assert.equal(f.calls,0);
console.log('PASS: custom menu quality/off selection and verification; ambiguous players rejected; JW container discovery; late captions, track recreation, manual override, timeout and cancellation');
