#!/usr/bin/env python3
"""Exercise the pinned visualizer and the Android native-frame presentation bridge."""
import http.server, pathlib, threading, shutil
from playwright.sync_api import sync_playwright

ROOT = pathlib.Path(__file__).resolve().parents[1]

class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=str(ROOT / 'app/src/main/assets/music-visualizer'), **kw)
    def log_message(self, *a):
        pass

server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
try:
    with sync_playwright() as p:
        browser = p.chromium.launch(
            executable_path=shutil.which('google-chrome') or shutil.which('chromium'),
            headless=True,
            args=['--enable-webgl', '--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader']
        )
        page = browser.new_page(viewport={'width': 960, 'height': 540}, device_scale_factor=1)
        errors = []
        page.on('pageerror', lambda e: errors.append(str(e)))
        page.add_init_script("""
          const wave = Array.from({length: 1024}, (_, i) => 128 + Math.round(70 * Math.sin(i * 0.1)));
          window.JaWave = {
            samples: () => JSON.stringify({mix: wave, left: wave, right: wave}),
            isPlaying: () => true,
            ready: () => true,
            presented: 0,
            lastFrame: '',
            present: (data) => { window.JaWave.lastFrame = data; window.JaWave.presented++; }
          };
        """)
        page.goto(f'http://127.0.0.1:{server.server_port}/index.html')
        page.wait_for_function('Number(document.querySelector("#visual").dataset.frames) > 10', timeout=30000)
        page.wait_for_function('window.JaWave.presented > 3', timeout=30000)
        assert page.evaluate('document.querySelector("#visual").width') <= 960
        assert page.evaluate('document.querySelector("#visual").height') <= 540
        assert page.evaluate('window.JaWave.lastFrame.startsWith("data:image/jpeg;base64,")'), 'Native bridge must receive JPEG frames'
        visible_pixels = page.evaluate("""
          async () => {
            const image = new Image();
            image.src = window.JaWave.lastFrame;
            await image.decode();
            const probe = document.createElement('canvas');
            probe.width = image.width; probe.height = image.height;
            const context = probe.getContext('2d', {willReadFrequently: true});
            context.drawImage(image, 0, 0);
            const pixels = context.getImageData(0, 0, probe.width, probe.height).data;
            let lit = 0;
            for (let i = 0; i < pixels.length; i += 4) {
              if (pixels[i] > 12 || pixels[i + 1] > 12 || pixels[i + 2] > 12) lit++;
            }
            return lit;
          }
        """)
        assert visible_pixels > 100, f'Native image frame is black ({visible_pixels} lit pixels)'
        first = page.locator('#status').inner_text()
        page.evaluate('nextPreset()')
        assert page.locator('#status').inner_text() != first, 'Preset selection should change'
        previous_presented = page.evaluate('window.JaWave.presented')
        page.set_viewport_size({'width': 390, 'height': 760})
        page.wait_for_function('window.JaWave.presented > arguments[0]', arg=previous_presented, timeout=30000)
        size = page.evaluate('({width: document.querySelector("#visual").width, height: document.querySelector("#visual").height})')
        assert size['width'] <= 960 and size['height'] <= 540, f'Portrait size exceeded native bitmap bounds: {size}'
        assert size['width'] > 0 and size['height'] > 0
        assert not errors, errors
        (ROOT / 'test-results').mkdir(exist_ok=True)
        page.screenshot(path=str(ROOT / 'test-results/music-visualizer.png'))
        print(f'Native bridge render, non-black JPEG frames, preset change, and capped portrait resize passed ({size["width"]}x{size["height"]})')
        browser.close()
finally:
    server.shutdown()
