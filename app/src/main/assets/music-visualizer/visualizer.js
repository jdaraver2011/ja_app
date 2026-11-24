/* JA's offline MilkDrop-compatible presentation. Audio comes only from the native PCM bridge. */
(() => {
  const canvas = document.getElementById('visual');
  const status = document.getElementById('status');
  // Keep WebGL offscreen; use the native ImageView presentation path for Android TV.
  const renderCanvas = document.createElement('canvas');
  const presenter = canvas.getContext('2d', {alpha:false});
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
    const nativePresentation = window.JaWave && typeof window.JaWave.present === 'function';
    // Match detail to the active screen, bounded to 960x540 for native ImageView transfer performance.
    const renderLimitWidth = 960;
    const renderLimitHeight = 540;
    const size = () => {
      const pixelRatio = Math.max(1, window.devicePixelRatio || 1);
      const screenWidth = Math.max(64, Math.round(innerWidth * pixelRatio));
      const screenHeight = Math.max(64, Math.round(innerHeight * pixelRatio));
      const scale = Math.min(1, renderLimitWidth / screenWidth, renderLimitHeight / screenHeight);
      return [Math.max(64, Math.round(screenWidth * scale)), Math.max(64, Math.round(screenHeight * scale))];
    };
    const [w,h] = size();
    canvas.width = renderCanvas.width = w;
    canvas.height = renderCanvas.height = h;
    const visual = engine.createVisualizer(null, renderCanvas, {width:w, height:h, meshWidth:32, meshHeight:24, pixelRatio:1});
    let index = Math.max(0, names.indexOf('Flexi - mindblob mix'));
    window.nextPreset = () => { index = (index + 1) % names.length; visual.loadPreset(presets[names[index]], 1.5); status.textContent = names[index]; };
    visual.loadPreset(presets[names[index]], 0);
    status.textContent = names[index];
    addEventListener('resize', () => {
      const [width,height] = size();
      canvas.width = renderCanvas.width = width;
      canvas.height = renderCanvas.height = height;
      visual.setRendererSize(width,height);
    });

    const audioLevels = {
      timeByteArray:new Uint8Array(1024).fill(128),
      timeByteArrayL:new Uint8Array(1024).fill(128),
      timeByteArrayR:new Uint8Array(1024).fill(128)
    };
    let lastAudioSample = 0;
    let lastPresented = 0;
    let heartbeat = 0;
    let heartbeatFrames = 0;
    report('READY_' + w + 'x' + h);

    function render(now) {
      if (canvas.dataset.error) return;
      requestAnimationFrame(render);
      if (document.hidden || (nativePresentation && !window.JaWave.isPlaying())) return;
      try {
        // Reuse fixed buffers and query native PCM at 30 Hz, while Butterchurn renders on vsync.
        if (nativePresentation && now - lastAudioSample >= 1000 / 30) {
          const data = JSON.parse(window.JaWave.samples());
          audioLevels.timeByteArray.set(data.mix);
          audioLevels.timeByteArrayL.set(data.left);
          audioLevels.timeByteArrayR.set(data.right);
          lastAudioSample = now;
        }
        visual.render({audioLevels});
        frames++;
        canvas.dataset.frames = String(frames);

        // Retain the working WebGL -> 2D canvas -> Android ImageView path.
        // Transfer at most 30 frames/s and keep one native bitmap in flight.
        if (!nativePresentation || (now - lastPresented >= 1000 / 30 && window.JaWave.ready())) {
          presenter.drawImage(renderCanvas, 0, 0, canvas.width, canvas.height);
          if (nativePresentation) {
            window.JaWave.present(canvas.toDataURL('image/jpeg', 0.55), names[index]);
            lastPresented = now;
          }
        }
        if (now - heartbeat >= 5000) {
          const elapsed = now - heartbeat;
          const fps = heartbeat === 0 ? 0 : Math.round((frames - heartbeatFrames) * 1000 / elapsed);
          let peak = 0;
          for (const sample of audioLevels.timeByteArray) peak = Math.max(peak, Math.abs(sample - 128));
          if (heartbeat !== 0) report('FRAME_' + fps + '_' + canvas.width + 'x' + canvas.height, peak);
          heartbeat = now;
          heartbeatFrames = frames;
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