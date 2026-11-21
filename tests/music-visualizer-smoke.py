#!/usr/bin/env python3
"""Exercise the actual pinned WebGL renderer with synthetic PCM, not Deezer audio."""
import http.server, pathlib, threading, shutil
from playwright.sync_api import sync_playwright
ROOT = pathlib.Path(__file__).resolve().parents[1]
class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self,*a,**kw): super().__init__(*a,directory=str(ROOT/'app/src/main/assets/music-visualizer'),**kw)
    def log_message(self,*a): pass
server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler)
threading.Thread(target=server.serve_forever,daemon=True).start()
try:
 with sync_playwright() as p:
    browser=p.chromium.launch(executable_path=shutil.which('google-chrome') or shutil.which('chromium'),headless=True,args=['--enable-webgl','--use-gl=angle','--use-angle=swiftshader','--enable-unsafe-swiftshader'])
    page=browser.new_page(viewport={'width':960,'height':540})
    errors=[]; page.on('pageerror',lambda e:errors.append(str(e)))
    page.add_init_script('window.JaWave={samples:()=>JSON.stringify(Array.from({length:1024},(_,i)=>128+Math.round(70*Math.sin(i*0.1))))}')
    page.goto(f'http://127.0.0.1:{server.server_port}/index.html')
    page.wait_for_function('Number(document.querySelector("canvas").dataset.frames)>10',timeout=30000)
    first=page.locator('#status').inner_text(); page.evaluate('nextPreset()')
    assert page.locator('#status').inner_text()!=first
    page.set_viewport_size({'width':390,'height':760})
    page.wait_for_function('Number(document.querySelector("canvas").dataset.frames)>20',timeout=30000)
    assert not errors,errors
    (ROOT/'test-results').mkdir(exist_ok=True)
    page.screenshot(path=str(ROOT/'test-results/music-visualizer.png'))
    print('WebGL: native-PCM bridge, actual preset render, preset change and portrait resize passed')
    browser.close()
finally: server.shutdown()
