'use strict';
const vm=require('node:vm');
function fixture(source,{size=0}={}) {
 let now=0,next=0,walks=0,maxTaskWalks=0,textReads=0,maxTaskTextReads=0;const timers=new Map(),events=new Map(),messages=[];
 const on=(target,type,fn)=>{const key=target+':'+type;if(!events.has(key))events.set(key,[]);events.get(key).push(fn);};
 const emit=(target,type,data={})=>{for(const f of events.get(target+':'+type)||[])f(data);};
 class El {
  constructor(tag='div',text=''){this.nodeType=1;this.tagName=tag.toUpperCase();this.children=[];this.childNodes=[];this.parentElement=null;this.attrs={};this.className='';this.id='';this.css={display:'block',visibility:'visible',opacity:'1',position:'static',zIndex:'0'};this.rect={width:400,height:200};this.style={setProperty:(k,v)=>{if(k==='display')this.css.display=v;}};if(text)this.append(new Text(text));}
  get isConnected(){return this===html||!!this.parentElement?.isConnected;}
  append(child){child.parentElement=this;this.childNodes.push(child);if(child.nodeType===1)this.children.push(child);return child;}
  contains(other){return other===this||this.childNodes.some(c=>c===other||(c.nodeType===1&&c.contains(other)));}
  get textContent(){return this.childNodes.map(c=>c.textContent).join(' ');}
  setAttribute(k,v){this.attrs[k]=v;}
  getAttribute(k){return this.attrs[k]??null;}
  getBoundingClientRect(){return this.rect;}
  querySelectorAll(selector){const out=[];function visit(n){for(const c of n.childNodes||[]){if(c.nodeType!==1)continue;if(selector.includes('video')&&c.tagName==='VIDEO'||selector.includes('button')&&c.tagName==='BUTTON'||selector==='iframe'&&c.tagName==='IFRAME')out.push(c);visit(c);}}visit(this);return out;}
  querySelector(s){return this.querySelectorAll(s)[0]||null;}
 }
 class Text{constructor(text){this.nodeType=3;this.value=text;this.parentElement=null;}get textContent(){textReads++;return this.value;}set textContent(value){this.value=value;}get isConnected(){return !!this.parentElement?.isConnected;}}
 const html=new El('html'),body=html.append(new El('body'));body.rect={width:1280,height:720};
 for(let i=0;i<size;i++)body.append(new El('span','ordinary title '+i));
 let observer;
 const document={body,documentElement:html,hidden:false,baseURI:'https://vidsrc.sh/embed/movie/123',addEventListener:(n,f)=>on('document',n,f),querySelector:s=>body.querySelector(s),querySelectorAll:s=>body.querySelectorAll(s),
  createTreeWalker(root){const nodes=[];function visit(n){for(const c of n.childNodes||[])if(c.nodeType===3)nodes.push(c);else visit(c);}visit(root);let i=0;return {nextNode(){if(i>=nodes.length)return null;walks++;return nodes[i++];}};}};
 const port={postMessage:m=>messages.push(m),onDisconnect:{addListener:()=>{}},disconnect(){}};
 const set=(f,ms,interval)=>{const id=++next;timers.set(id,{f,at:now+ms,interval});return id;};
 const window={addEventListener:(n,f)=>on('window',n,f)};window.parent=window;
 vm.runInNewContext(source,{window,document,location:{hostname:'vidsrc.sh'},innerWidth:1280,innerHeight:720,NodeFilter:{SHOW_TEXT:4},URL,getComputedStyle:n=>n.css,performance:{now:()=>now},Date:{now:()=>1800000000000+now},
  MutationObserver:class{constructor(cb){this.cb=cb;this.active=false;observer=this;}observe(){this.active=true;}disconnect(){this.active=false;}},browser:{runtime:{connectNative:()=>port}},setTimeout:(f,ms)=>set(f,ms,0),clearTimeout:id=>timers.delete(id),setInterval:(f,ms)=>set(f,ms,ms),clearInterval:id=>timers.delete(id)});
 function advance(ms){const target=now+ms;let steps=0;while(true){let chosen=null;for(const [id,t]of timers)if(t.at<=target&&(!chosen||t.at<chosen[1].at))chosen=[id,t];if(!chosen)break;if(++steps>200000)throw Error('timer loop');const [id,t]=chosen;now=t.at;if(t.interval)t.at+=t.interval;else timers.delete(id);const before=walks,readsBefore=textReads;t.f();maxTaskTextReads=Math.max(maxTaskTextReads,textReads-readsBefore);maxTaskWalks=Math.max(maxTaskWalks,walks-before);}now=target;}
 function mutation(records){if(observer.active)observer.cb(records);}
 function scam(parent=body){const el=parent.append(new El('div','Protect your connection with VPN'));el.className='popup warning';el.css.position='fixed';el.append(new El('button','Close'));return el;}
 return {document,body,El,Text,advance,mutation,scam,messages,emit,get textReads(){return textReads;},get maxTaskTextReads(){return maxTaskTextReads;},get walks(){return walks;},get maxTaskWalks(){return maxTaskWalks;},get timerCount(){return timers.size;},get observing(){return observer.active;}};
}
module.exports={fixture};
