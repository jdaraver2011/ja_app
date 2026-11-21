"""Boot the exact bundled static client with JA's location/display parameters in a real browser."""
import asyncio,http.server,json,pathlib,re,shutil,threading
from playwright.async_api import async_playwright
ROOT=pathlib.Path(__file__).resolve().parents[1]/'app/src/main/assets/weatherstar'
class Server(http.server.SimpleHTTPRequestHandler):
 def __init__(self,*a,**k):super().__init__(*a,directory=str(ROOT),**k)
 def log_message(self,*a):pass
async def main():
 server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Server)
 threading.Thread(target=server.serve_forever,daemon=True).start()
 async with async_playwright() as p:
  exe=shutil.which('google-chrome') or shutil.which('chromium')
  browser=await p.chromium.launch(executable_path=exe,headless=True,args=['--no-sandbox','--autoplay-policy=no-user-gesture-required'])
  page=await browser.new_page(viewport={'width':1280,'height':720})
  errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
  await page.goto(f'http://127.0.0.1:{server.server_port}/?viewMode=wide-enhanced&kiosk=true&jaKiosk=true&refreshTime=900000&mediaPlaying=false&scanLines=false&latLonQuery=Metairie&latLon=%7B%22lat%22%3A29.99%2C%22lon%22%3A-90.18%7D')
  await page.wait_for_timeout(25000)
  playlist=await page.evaluate("fetch('playlist.json').then(r=>r.json())")
  assert playlist['availableFiles']==[],'Browser audio must be disabled while native Android owns playback'
  classes=await page.locator('body').get_attribute('class')
  for width,height in [(1920,1080),(1280,720),(960,540),(640,360),(360,800),(800,360)]:
   await page.set_viewport_size({'width':width,'height':height})
   await page.wait_for_timeout(350)
   rect=await page.locator('#divTwcMain').bounding_box()
   content=await page.locator('#container').bounding_box()
   for box in [rect,content]:
    assert box['x']>=-1 and box['y']>=-1 and box['x']+box['width']<=width+1 and box['y']+box['height']<=height+1,(width,height,box)
   assert abs(rect['width']/rect['height']-854/480)<0.005,'Weather aspect ratio changed'
   assert not await page.locator('#divTwcBottom').is_visible(),'Weather controls must stay hidden'
  for text in ['Conditions at New Orleans','Temp: 68°F Heat Index: 72°F','Barometric Pressure: 30.04','Wind: N 5 MPH','Visib: 10 mi. Ceiling: 4100 ft.']:
   box=await page.evaluate("""text=>{
    const scroll=document.querySelector('#container>.scroll');scroll.style.display='block';
    const fixed=scroll.querySelector('.fixed');scroll.querySelector('.scroll-header').textContent='';
    fixed.textContent=text;fixed.classList.remove('reveal');
    const range=document.createRange();range.selectNodeContents(fixed);
    const t=range.getBoundingClientRect(),row=scroll.getBoundingClientRect();
    return {textCenter:t.x+t.width/2,rowCenter:row.x+row.width/2};
   }""",text)
   assert abs(box['textCenter']-box['rowCenter'])<2,(text,box)
  await page.screenshot(path='/tmp/weatherstar-smoke.png')
  print(json.dumps({'errors':errors,'bodyClass':classes,'canvases':await page.locator('canvas').count(),'text':(await page.locator('body').inner_text())[:500]}))
  assert not errors,'WeatherStar runtime exception'
  assert 'kiosk' in classes,'Kiosk setting not applied'
  assert await page.locator('canvas').count()>0,'Weather displays were not initialized'
  await browser.close()
 server.shutdown()
asyncio.run(main())
