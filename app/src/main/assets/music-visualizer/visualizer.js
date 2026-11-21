/* JA's offline MilkDrop-compatible presentation. Audio comes only from the native PCM bridge. */
(() => {
  const canvas = document.getElementById('visual');
  const status = document.getElementById('status');
  // Keep WebGL offscreen. Present a CPU-backed 2D bitmap instead of a WebGL compositor layer.
  const renderCanvas = document.createElement('canvas');
  const presenter = canvas.getContext('2d', {alpha:false, willReadFrequently:true});
  let frames = 0;
  const report = (code, peak = 0) => { try { if (window.JaWave && window.JaWave.report) window.JaWave.report(code, frames, peak); } catch (_) {} };
  const fail = (error) => {
    const code = String(error && error.name || 'RENDER_ERROR');
    document.getElementById('error').textContent = 'Visualizer stopped (' + code + '). Music continues. Use Back to return to the player.';
    canvas.dataset.error = code; report('ERROR_' + code);
  };
  addEventListener('error', event => fail(event.error || {name:'SCRIPT_ERROR'}));
  renderCanvas.addEventListener('webglcontextlost', event => { event.preventDefault(); fail({name:'CONTEXT_LOST'}); });
  try {
    if (!presenter) throw {name:'PRESENTATION_UNAVAILABLE'};
    const engine = (window.butterchurn.default || window.butterchurn);
    const presetApi = (window.butterchurnPresets.default || window.butterchurnPresets);
    const presets = presetApi.getPresets();
    const names = Object.keys(presets);
    if (!names.length) throw Error('No presets');
    const size = () => { const scale = Math.min(1, 1280 / innerWidth, 720 / innerHeight); return [Math.max(64, Math.round(innerWidth * scale)), Math.max(64, Math.round(innerHeight * scale))]; };
    const [w,h] = size();
    // Butterchurn sets viewport/texture sizes, but does not resize the supplied canvas.
    canvas.width = renderCanvas.width = w; canvas.height = renderCanvas.height = h;
    const visual = engine.createVisualizer(null, renderCanvas, {width:w, height:h, meshWidth:32, meshHeight:24, pixelRatio:1});
    let index = Math.max(0, names.indexOf('Flexi - mindblob mix'));
    window.nextPreset = () => { index = (index + 1) % names.length; pixelChecked = false; visual.loadPreset(presets[names[index]], 1.5); status.textContent = names[index]; };
    visual.loadPreset(presets[names[index]], 0); status.textContent = names[index];
    addEventListener('resize', () => { const [width,height] = size(); canvas.width = renderCanvas.width = width; canvas.height = renderCanvas.height = height; visual.setRendererSize(width,height); });
    let last = 0;
    let heartbeat = 0;
    let pixelChecked = false;
    report("READY");
    function render(now) {
      if (canvas.dataset.error) return;
      requestAnimationFrame(render);
      if (document.hidden || now - last < 40) return;
      last = now;
      try {
        if (window.JaWave && typeof window.JaWave.samples !== 'function') throw {name:'PCM_BRIDGE_MISSING'};
        const data = window.JaWave ? JSON.parse(window.JaWave.samples()) : new Array(1024).fill(128);
        const samples = Uint8Array.from(data);
        visual.render({audioLevels:{timeByteArray:samples,timeByteArrayL:samples,timeByteArrayR:samples}});
        // Copy in this callback, before the browser can discard the WebGL drawing buffer.
        presenter.drawImage(renderCanvas, 0, 0, canvas.width, canvas.height);
        canvas.dataset.frames = String(++frames);
        if (!pixelChecked && frames >= 40) {
          pixelChecked = true;
          const gl = renderCanvas.getContext('webgl2');
          const pixel = new Uint8Array(4); let gpuPeak = 0; let outputPeak = 0;
          for (const x of [0.2,0.4,0.6,0.8]) for (const y of [0.2,0.4,0.6,0.8]) {
            gl.readPixels(Math.floor(canvas.width*x),Math.floor(canvas.height*y),1,1,gl.RGBA,gl.UNSIGNED_BYTE,pixel);
            gpuPeak = Math.max(gpuPeak,pixel[0],pixel[1],pixel[2]);
            const displayed = presenter.getImageData(Math.floor(canvas.width*x),Math.floor(canvas.height*y),1,1).data;
            outputPeak = Math.max(outputPeak,displayed[0],displayed[1],displayed[2]);
          }
          canvas.dataset.outputPeak = String(outputPeak);
          canvas.dataset.bufferSize = canvas.width + 'x' + canvas.height;
          report('GPU_' + renderCanvas.width + 'x' + renderCanvas.height + '_GL' + gl.getError(), gpuPeak);
          report('PRESENT_2D_' + canvas.width + 'x' + canvas.height, outputPeak);
        }
        if (now - heartbeat > 5000) {
          heartbeat = now;
          let peak = 0; for (const sample of samples) peak = Math.max(peak, Math.abs(sample - 128));
          report('FRAME_' + canvas.width + 'x' + canvas.height, peak);
        }
      } catch (error) { fail(error); }
    }
    requestAnimationFrame(render);
  } catch (error) {
    document.getElementById('error').textContent = 'MilkDrop visualizer needs WebGL 2 support on this device. Music continues playing. Use Back to return to the player.';
    status.textContent = 'Visualizer unavailable';
    canvas.dataset.error = String(error && error.name || 'START_ERROR'); report('START_ERROR');
  }
})();
