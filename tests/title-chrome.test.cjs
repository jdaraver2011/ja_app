const assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const source=fs.readFileSync(require('node:path').join(__dirname,'../app/src/main/assets/ja_tv_remote/remote.js'),'utf8');
const fn=source.slice(source.indexOf('  function hidePlayerTitleChrome()'),source.indexOf('  let titleRefreshTimer'));
function node(text,top=30,controls=false){const attrs=new Map();return {isConnected:true,innerText:text,className:'',id:'',closest:()=>controls?{}:null,querySelector:()=>null,getBoundingClientRect:()=>({top,bottom:top+30,width:250,height:30}),getAttribute:k=>attrs.get(k),setAttribute:(k,v)=>attrs.set(k,v)};}
const title=node('THE ODYSSEY 2026'),caption=node('Caption 2026',600),control=node('Settings 2026',20,true),menu=node('Quality 1080p',30);
const candidates=[title,caption,control,menu];
const context={window:{innerHeight:720},document:{querySelector:()=>({}),documentElement:{setAttribute(){},clientHeight:720},querySelectorAll:selector=>selector.includes('div,span')?candidates:[]},TITLE_HIDE_ATTR:'hide-title',TITLE_NODE_ATTR:'hidden-title',currentVideo:()=>({}),send:()=>{},location:{hostname:'player.example'}};
vm.createContext(context);vm.runInContext('let titleRuleReported=false;\n'+fn+'\nhidePlayerTitleChrome();',context);
assert.equal(title.getAttribute('hidden-title'),'true');assert.equal(caption.getAttribute('hidden-title'),undefined);assert.equal(control.getAttribute('hidden-title'),undefined);assert.equal(menu.getAttribute('hidden-title'),undefined);
const replacement=node('ANOTHER TITLE 2025');candidates[0]=replacement;vm.runInContext('hidePlayerTitleChrome()',context);assert.equal(replacement.getAttribute('hidden-title'),'true');
console.log('PASS: unclassified upper-edge title and replacement hidden; lower captions, menu and control nodes preserved');
