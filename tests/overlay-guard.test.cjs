'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),{fixture}=require('./overlay-fixture.cjs');
const source=fs.readFileSync('app/src/main/assets/ja_overlay_guard/guard.js','utf8');
{
 const f=fixture(source,{size:3000});f.advance(220);assert.equal(f.walks,0,'guard is dormant until native session configuration');assert.equal(f.observing,false);f.enable(true);f.advance(220);assert(f.walks<=160);f.advance(2000);assert.equal(f.walks,3000);assert(f.maxTaskWalks<=160);
 const before=f.walks,p=f.scam();f.mutation([{type:'childList',addedNodes:[p]}]);f.advance(250);assert.equal(p.attrs['data-ja-popup-suppressed'],'true');assert(f.walks-before<10);
 const normal=f.body.append(new f.El('div','VPN settings'));f.mutation([{type:'childList',addedNodes:[normal]}]);f.advance(250);assert.equal(normal.attrs['data-ja-popup-suppressed'],undefined);
 const videoPanel=f.scam(),video=videoPanel.append(new f.El('video'));video.paused=false;video.readyState=4;f.mutation([{type:'childList',addedNodes:[videoPanel]}]);f.advance(250);assert.equal(videoPanel.attrs['data-ja-popup-suppressed'],undefined);
 f.enable(false);assert.equal(f.observing,false,'native can suspend guard in IPTV session');const disabledWalks=f.walks;f.mutation([{type:'childList',addedNodes:[f.scam()]}]);f.advance(30000);assert.equal(f.walks,disabledWalks);assert.equal(f.timerCount,0);
 const g=fixture(source,{size:100});g.enable(true);g.advance(220);assert(g.observing,'guard runs in non-IPTV player session');
 g.document.hidden=true;g.emit('document','visibilitychange');const paused=g.walks;g.advance(30000);assert.equal(g.walks,paused);assert.equal(g.timerCount,0);assert.equal(g.observing,false);
 g.document.hidden=false;g.emit('document','visibilitychange');g.advance(2000);assert(g.walks>paused);
 g.emit('window','pagehide');assert.equal(g.timerCount,0);assert.equal(g.observing,false);const exited=g.walks;g.advance(30000);assert.equal(g.walks,exited);
 g.emit('window','pageshow');g.advance(2000);assert(g.walks>exited);
}
{
 const f=fixture(source);f.enable(true);f.advance(300);const nodes=[];for(let i=0;i<70;i++)nodes.push(f.body.append(new f.El('div','ordinary '+i)));
 const p=f.scam();nodes.push(p);f.mutation([{type:'childList',addedNodes:nodes}]);f.advance(2500);assert.equal(p.attrs['data-ja-popup-suppressed'],'true','overflow fallback');
 const later=f.body.append(new f.El('div','ordinary'));later.className='popup';later.css.position='fixed';later.append(new f.El('button','Close'));const text=later.childNodes[0];text.textContent='Install ClearVPN';f.mutation([{type:'characterData',target:text}]);f.advance(250);assert.equal(later.attrs['data-ja-popup-suppressed'],'true');
 const hidden=f.scam();hidden.css.display='none';f.mutation([{type:'childList',addedNodes:[hidden]}]);f.advance(250);assert.equal(hidden.attrs['data-ja-popup-suppressed'],undefined);hidden.css.display='block';f.mutation([{type:'attributes',target:hidden}]);f.advance(250);assert.equal(hidden.attrs['data-ja-popup-suppressed'],'true');
}
console.log('PASS: bounded/scoped scans, detection, video protection, overflow, visibility and page cleanup');
for(const phrase of ['Virus on your phone','Protect your connection with VPN','VPN must be activated','Install ClearVPN','Activate ClearVPN','To access this content securely please activate','VPN is required to continue']) {
 const f=fixture(source);f.enable(true);f.advance(300);const p=f.scam();p.childNodes[0].textContent=phrase;
 f.mutation([{type:'childList',addedNodes:[p]}]);f.advance(250);
 assert.equal(p.attrs['data-ja-popup-suppressed'],'true',phrase);
}
console.log('PASS: existing scam phrase rules remain effective');
