const fs = require('fs');
const vm = require('vm');
const assert = require('node:assert/strict');
const code = fs.readFileSync('app/src/main/assets/music-visualizer/visualizer.js', 'utf8');
const canvas = {width:300,height:150,dataset:{},addEventListener(){}};
const listeners = {};
let renderer;
const ctx = {
 document:{getElementById:id=>id==='visual'?canvas:{textContent:''},hidden:false},
 innerWidth:412,innerHeight:1800, Uint8Array, console,
 requestAnimationFrame(){}, addEventListener:(name,fn)=>{listeners[name]=fn;},
 window:{butterchurn:{createVisualizer:(_audio,c,opts)=>{
  assert.equal(c.width,opts.width,'Canvas backing width must match renderer at creation');
  assert.equal(c.height,opts.height,'Canvas backing height must match renderer at creation');
  return renderer={loadPreset(){},setRendererSize(w,h){
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
console.log('Canvas backing dimensions match renderer on portrait startup and landscape resize');
