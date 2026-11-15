const assert=require('node:assert/strict');
const {create}=require('../app/src/main/assets/ja_tv_remote/quality-activation.js');
function fixture(owned=true){const events=[],waits=[],resolvers=[];let row={},valid=true;
 const q=create({open:()=>{events.push('open');return owned;},find:()=>row,
 focus:r=>{events.push('focus');assert.equal(r,row);},activate:r=>{events.push('click');assert.equal(r,row);},
 close:()=>events.push('close'),mask:v=>events.push('mask:'+v),valid:()=>valid,report:()=>{},
 wait:ms=>{waits.push(ms);return new Promise(resolve=>resolvers.push(resolve));}});
 return {q,events,waits,replace:()=>row={},invalidate:()=>valid=false,async next(){resolvers.shift()();await Promise.resolve();await Promise.resolve();}};}
(async()=>{
 let f=fixture(),p=f.q.run('1080p');assert.deepEqual(f.events,['mask:true','open']);assert.equal(f.q.run('720p'),p);
 f.replace();await f.next();assert.equal(f.events.at(-1),'focus');f.replace();await f.next();assert.equal(f.events.at(-1),'click');
 assert.ok(!f.events.includes('close'));await f.next();await p;
 assert.deepEqual(f.waits,[800,300,1800]);assert.deepEqual(f.events,['mask:true','open','focus','click','close','mask:false']);
 f=fixture();p=f.q.run('1080p');f.q.cancel();await f.next();await assert.rejects(p,/CANCELLED/);assert.ok(!f.events.includes('click'));assert.equal(f.events.at(-1),'mask:false');
 f=fixture();p=f.q.run('1080p');await f.next();f.invalidate();await f.next();await assert.rejects(p,/CANCELLED/);assert.ok(!f.events.includes('click'));
 f=fixture(false);p=f.q.run('1080p');await f.next();await f.next();await f.next();await p;assert.ok(!f.events.includes('close'));
 console.log('PASS: delayed open/focus/activation/settling, fresh-row queries, transaction deduplication, cancellation and user-menu ownership');
})().catch(e=>{console.error(e);process.exitCode=1;});
