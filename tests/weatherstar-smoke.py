"""Boot the exact bundled static client with JA's location/display parameters in a real browser."""
import asyncio,contextlib,http.server,json,pathlib,shutil,threading
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
  browser=await p.chromium.launch(executable_path=exe,headless=True,args=['--no-sandbox'])
  page=await browser.new_page(viewport={'width':1280,'height':720})
  errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
  await page.goto(f'http://127.0.0.1:{server.server_port}/?viewMode=wide-enhanced&kiosk=true&refreshTime=900000&mediaPlaying=false&scanLines=false&latLonQuery=Metairie&latLon=%7B%22lat%22%3A29.99%2C%22lon%22%3A-90.18%7D')
  await page.wait_for_timeout(25000)
  classes=await page.locator('body').get_attribute('class')
  await page.screenshot(path='/tmp/weatherstar-smoke.png')
  print(json.dumps({'errors':errors,'bodyClass':classes,'canvases':await page.locator('canvas').count(),'text':(await page.locator('body').inner_text())[:500]}))
  assert not errors,'WeatherStar runtime exception'
  assert 'kiosk' in classes,'Kiosk setting not applied'
  assert await page.locator('canvas').count()>0,'Weather displays were not initialized'
  await browser.close()
 server.shutdown()
asyncio.run(main())
