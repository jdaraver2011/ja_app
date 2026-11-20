// Native JA owns fullscreen. Fit the whole 854x480 display inside the actual WebView.
export default function fitJaKiosk() {
  if (new URLSearchParams(window.location.search).get('jaKiosk') !== 'true') return false;
  const wrapper = document.querySelector('#divTwc');
  const main = document.querySelector('#divTwcMain');
  const container = document.querySelector('#container');
  if (!wrapper || !main || !container) return true;
  const width = window.visualViewport?.width || document.documentElement.clientWidth || window.innerWidth;
  const height = window.visualViewport?.height || document.documentElement.clientHeight || window.innerHeight;
  const scale = Math.min(width / 854, height / 480);
  if (!(scale > 0)) return true;
  const set = (el, values) => Object.entries(values).forEach(([name,value]) => el.style.setProperty(name,value,'important'));
  set(document.documentElement, {width:'100%',height:'100%',overflow:'hidden','text-size-adjust':'100%','-webkit-text-size-adjust':'100%'});
  set(document.body, {width:'100%',height:'100%',margin:'0',padding:'0',overflow:'hidden',background:'#000'});
  set(wrapper, {position:'fixed',left:'0',top:'0',width:`${width}px`,height:`${height}px`,'max-width':'none',transform:'none',overflow:'hidden',margin:'0'});
  set(main, {position:'absolute',width:'854px',height:'480px',left:`${(width-854*scale)/2}px`,top:`${(height-480*scale)/2}px`,transform:`scale(${scale})`,'transform-origin':'0 0','margin-left':'0','margin-top':'0'});
  set(container, {position:'relative',left:'0',top:'0',width:'854px',height:'480px',transform:'none','margin-left':'0','margin-top':'0'});
  ['#divQuery','#divTwcBottom'].forEach(id => { const el=document.querySelector(id);if(el)set(el,{display:'none'}); });
  return true;
}
