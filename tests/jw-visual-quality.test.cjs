const assert=require('node:assert/strict');
const {create}=require('../app/src/main/assets/ja_tv_remote/output-quality.js');
function fixture(mode='auto',visualAvailable=true){let fn,time=0,index=0,requests=0;const events=[];
 const v={paused:false,ended:false,readyState:4,videoWidth:1920,videoHeight:800,currentTime:1};
 let visual={mode,level:{index:1,width:1920,height:800}};
 const api={requiresVisualQuality:true,getQualityLevels:()=>[{label:'Auto'},{label:'1080p',width:1920,height:800}],
 getCurrentQuality:()=>index,setCurrentQuality:i=>{index=i;requests++;},getVisualQuality:()=>visualAvailable?visual:null};
 const q=create({video:()=>v,api:()=>api,report:e=>events.push(e),now:()=>time,schedule:f=>{fn=f;return 1;},unschedule:()=>fn=null});
 return {q,events,manual:()=>visual.mode='manual',wrong:()=>visual.level={index:1,width:1280,height:720},get requests(){return requests;},step(n){for(let i=0;i<n;i++){time+=1000;v.currentTime++;const f=fn;fn=null;f?.();}}};}
let f=fixture();f.q.request(1080);f.step(10);assert.equal(f.requests,1);assert.ok(!f.events.some(e=>e.event==='JW_STREAM_QUALITY_CONFIRMED'));
f.manual();f.step(2);assert.equal(f.events.at(-1).event,'JW_STREAM_QUALITY_CONFIRMED');assert.equal(f.events.at(-1).visual.level.height,800);
f=fixture();f.q.request(1080);f.step(35);assert.equal(f.events.at(-1).event,'QUALITY_MATCH_UNVERIFIED');
f=fixture('manual',false);f.q.request(1080);f.step(35);assert.equal(f.events.at(-1).event,'QUALITY_MATCH_UNVERIFIED');
f=fixture('manual');f.wrong();f.q.request(1080);f.step(35);assert.equal(f.events.at(-1).event,'QUALITY_MATCH_UNVERIFIED');
console.log('PASS: selection marker/Auto/absent visual API/wrong rendition cannot establish stream confirmation; Manual cropped-1080-class rendition can');
