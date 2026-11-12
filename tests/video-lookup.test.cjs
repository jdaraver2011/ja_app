const fs = require('node:fs'), vm = require('node:vm'), assert = require('node:assert/strict');
const source = fs.readFileSync('app/src/main/assets/ja_tv_remote/remote.js', 'utf8');
// Exercise the exact selector used by remote actions and one-second statistics.
const start = source.indexOf('  let videoLookupCached = false;');
const end = source.indexOf('  const bottomBarCandidates', start);
assert(start > 0 && end > start);
let queries = 0, layoutReads = 0;
const pending = [];
const first = {isConnected:true,paused:false,ended:false,visible:true};
const second = {isConnected:true,paused:false,ended:false,visible:true};
let nodes = [first];
const ctx = {videos:()=>{queries++;return nodes;},visible:v=>{layoutReads++;return v.visible;},queueMicrotask:f=>pending.push(f)};
vm.createContext(ctx); vm.runInContext(source.slice(start,end)+'\nglobalThis.getVideo = currentVideo;',ctx);
for (let i=0;i<100;i++) assert.equal(ctx.getVideo(),first);
assert.equal(queries,1); assert.equal(layoutReads,1);
while(pending.length) pending.shift()();
first.visible=false; nodes=[first,second];
assert.equal(ctx.getVideo(),second);
second.isConnected=false; nodes=[];
assert.equal(ctx.getVideo(),null); // immediate invalidation when removed
while(pending.length) pending.shift()();
second.isConnected=true;nodes=[second];assert.equal(ctx.getVideo(),second);
console.log('PASS: 100 same-task lookups use one DOM scan; next task and disconnection rediscover video');
