const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const source=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/ja_tv_remote/remote.js'),'utf8');
const fn=source.slice(source.indexOf('  function activateQualityRow('),source.indexOf('  function selectMenu('));
const video={paused:false,ended:false,isConnected:true,videoWidth:1920,videoHeight:1080,readyState:4};
const events=[],timers=[];let clicks=0;
const context={qualityVideo:()=>video,qualityIntentVersion:0,qualityDocumentActive:true,
 JaQualityResume:require('../app/src/main/assets/ja_tv_remote/quality-resume.js'),focusQualityRow:()=>{},
 qualityPlaybackSnapshot:(origin,stage)=>({stage,position:10,width:1920,height:1080,paused:false,readyState:4}),
 qualitySampleTimers:new Set(),document:{hidden:false},send:e=>events.push(e),setTimeout:(fn,ms)=>{timers.push({fn,ms});return timers.length;}};
vm.createContext(context);vm.runInContext(fn,context);context.activateQualityRow({click:()=>clicks++},'automatic');
for(const t of timers)t.fn();
assert.equal(clicks,1);assert.equal(timers.length,4);const group=events.find(e=>e.event==='QUALITY_COMPARISON_SERIES');
assert.equal(group.samples.length,6);assert.equal(group.samples[0].stage,'before');assert.equal(group.samples.at(-1).stage,'after-8000ms');
assert.ok(JSON.stringify(group).length<3500);assert.equal(context.qualitySampleTimers.size,0);
console.log('PASS: one grouped comparison contains all six stages, bounded payload and completed timer cleanup');
