const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const code = fs.readFileSync('app/src/main/assets/ja_source_status/status.js', 'utf8');
function harness({trusted = true, resumeMs = 0, ready = true} = {}) {
  let now = 0;
  const messages = [], intervals = [], timers = [], documentEvents = {};
  let receive;
  class Video {}
  const video = Object.assign(new Video(), {
    isConnected:true, videoWidth:1920, videoHeight:1080, paused:false,
    ended:false, readyState:4, currentTime:10,
    getBoundingClientRect:()=>({width:1280,height:720}),
    seekable:{length:1,start:()=>0,end:()=>7200}
  });
  const top = {};
  const win = {top,addEventListener:()=>{}};
  if (trusted) win.top = win;
  const port = {
    postMessage:m=>messages.push(m),
    onMessage:{addListener:f=>{receive=f;}},
    onDisconnect:{addListener:()=>{}}
  };
  const document = {
    body:{innerText:''}, hidden:false, referrer:'https://unrelated.example/',
    querySelectorAll:()=>[video],
    addEventListener:(name,cb)=>{documentEvents[name]=cb;}
  };
  vm.runInNewContext(code, {
    window:win,document,location:{hostname:trusted?'vidsrc.sh':'ads.example'},
    performance:{now:()=>now},HTMLVideoElement:Video,innerWidth:1280,
    getComputedStyle:()=>({display:'block',visibility:'visible'}),URL,
    browser:{runtime:{connectNative:()=>port}},
    setInterval:(f,ms)=>{intervals.push({f,ms});return intervals.length;},
    clearInterval:()=>{},setTimeout:f=>{timers.push(f);return timers.length;}
  });
  if (ready) receive({action:'resume',positionMs:resumeMs});
  function advance(ms,position) {
    now += ms;
    if (position !== undefined) video.currentTime=position;
    for (const timer of intervals) timer.f();
  }
  return {messages,video,advance,receive,document,documentEvents};
}
{
 const h=harness(); h.advance(0,10);h.advance(1000,11);
 assert.equal(h.messages.some(m=>m.event==='VIDEO_STARTED'),false);
 h.advance(2100,13.1);
 assert.equal(h.messages.filter(m=>m.event==='VIDEO_STARTED').length,1);
 h.advance(5000,18.1);
 assert(h.messages.some(m=>m.event==='PROGRESS'&&m.positionMs===18100));
 h.advance(31000,18.1);
 assert(h.messages.some(m=>m.event==='STALLED'));
}
{
 const h=harness({trusted:false});h.advance(0,10);h.advance(4000,14);
 assert.equal(h.messages.some(m=>m.event==='VIDEO_STARTED'),false);
}
{
 const h=harness({resumeMs:120000});h.advance(0,10);h.advance(4000,14);
 assert.equal(h.video.currentTime,120);
}
{
 const h=harness({ready:false});h.advance(0,10);h.advance(4000,14);
 h.advance(6000,20);
 assert.equal(h.messages.some(m=>m.event==='PROGRESS'),false);
 h.receive({action:'resume',positionMs:180000});
 assert.equal(h.video.currentTime,180);
}
{
 const h=harness();h.advance(0,10);h.advance(4000,14);
 h.video.paused=true;h.advance(60000,14);
 assert.equal(h.messages.some(m=>m.event==='STALLED'),false);
}
console.log('PASS: sustained start, untrusted frame rejection, progress, resume, late handshake, stall and pause behavior');

{
 const h=harness(); h.video.duration=100;
 h.advance(0,10); h.advance(4000,14); h.advance(5000,90);
 assert(h.messages.some(m=>m.event==='PROGRESS' && m.positionMs===90000 && m.durationMs===100000));
 h.video.duration=Infinity; h.advance(5000,91);
 assert.equal(h.messages.filter(m=>m.event==='PROGRESS').at(-1).durationMs,0);
 h.video.duration=100; h.video.currentTime=100; h.video.ended=true;
 h.documentEvents.ended();
 assert.equal(h.messages.filter(m=>m.event==='PROGRESS').at(-1).positionMs,100000);
}
console.log('PASS: finite duration forwarding, unknown/live duration rejection and ended progress flush');

{
 const h=harness(); h.video.duration=100;
 h.advance(0,10); h.advance(4000,14); h.advance(5000,90);
 const count=()=>h.messages.filter(m=>m.event==='PROGRESS').length;
 const before=count();h.video.paused=true;
 h.documentEvents.pause();h.advance(5000,90);h.documentEvents.ended();
 assert.equal(count(),before,'unchanged pause/end samples do not cause extra database writes');
 h.video.duration=99;h.documentEvents.pause();
 assert.equal(count(),before+1,'duration corrections must reach watched marking');
 h.video.currentTime=50;h.documentEvents.pause();
 assert.equal(count(),before+2,'backward seeks must still persist');
}
console.log('PASS: duplicate progress suppression preserves duration updates and backward seeks');
