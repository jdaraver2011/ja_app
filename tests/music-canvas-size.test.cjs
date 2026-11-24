const fs = require('fs');
const vm = require('vm');
const assert = require('node:assert/strict');
const code = fs.readFileSync('app/src/main/assets/music-visualizer/visualizer.js', 'utf8');
const canvas = {width:300,height:150,dataset:{},addEventListener(){},getContext(){return {drawImage(){}}},toDataURL(){return 'data:image/jpeg;base64,test';}};
const renderCanvas = {width:300,height:150,addEventListener(){}};
const listeners = {};
let renderer;
let frameCallback;
let renders = 0;
let presentations = 0;
let bridgeReady = true;
const waveform = Array(1024).fill(128);
const ctx = {
 document:{getElementById:id=>id==='visual'?canvas:{textContent:''},createElement:tag=>tag==='canvas'?renderCanvas:{},hidden:false},
 innerWidth:412,innerHeight:1800, Uint8Array, console,
 requestAnimationFrame(fn){frameCallback=fn;}, addEventListener:(name,fn)=>{listeners[name]=fn;},
 window:{JaWave:{ready:()=>bridgeReady,isPlaying:()=>true,samples:()=>JSON.stringify({mix:waveform,left:waveform,right:waveform}),present:()=>{presentations++;}},butterchurn:{createVisualizer:(_audio,c,opts)=>{
  assert.equal(c.width,opts.width,'Canvas backing width must match renderer at creation');
  assert.equal(c.height,opts.height,'Canvas backing height must match renderer at creation');
  return renderer={loadPreset(){},render(){renders++;},setRendererSize(w,h){
   assert.equal(c.width,w,'Resize must update canvas backing width');assert.equal(c.height,h,'Resize must update canvas backing height');
  }};
 }},butterchurnPresets:{getPresets:()=>({'Flexi - mindblob mix':{}})}}
};
vm.runInNewContext(code,ctx);
assert.ok(renderer, 'Renderer must initialize');
assert.ok(!canvas.dataset.error,'Canvas sizing failure must not be hidden by catch');
assert.equal(canvas.width,165);assert.equal(canvas.height,720);
ctx.innerWidth=1800;ctx.innerHeight=412;listeners.resize();
assert.equal(canvas.width,1280);assert.equal(canvas.height,293);
ctx.innerWidth=960;ctx.innerHeight=540;listeners.resize();
assert.equal(canvas.width,1280);assert.equal(canvas.height,720);
for (let i=0;i<60;i++) frameCallback(i*1000/59.94);
assert.equal(renders,30);
assert.equal(presentations,renders);
bridgeReady=false;
frameCallback(1000);
assert.equal(renders,30,'Skip rendering when a native bitmap is pending');
console.log('720p canvas sizing and native 30 fps frame pacing passed');
