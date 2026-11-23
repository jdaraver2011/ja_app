/* JA's offline MilkDrop-compatible presentation. Butterchurn draws directly to the WebView canvas. */
(() => {
  const canvas = document.getElementById('visual');
  const status = document.getElementById('status');
  let frames = 0;
  const report = (code, peak = 0) => {
    try { if (window.JaWave && window.JaWave.report) window.JaWave.report(code, frames, peak); } catch (_) {}
  };
  const fail = (error) => {
    const code = String(error && error.name || 'RENDER_ERROR');
    document.getElementById('error').textContent = 'Visualizer stopped (' + code + '). Music continues. Use Back to return to the player.';
    canvas.dataset.error = code;
    report('ERROR_' + code);
  };
  addEventListener('error', event => fail(event.error || {name:'SCRIPT_ERROR'}));
  canvas.addEventListener('webglcontextlost', event => { event.preventDefault(); fail({name:'CONTEXT_LOST'}); });
  try {
    const engine = (window.butterchurn.default || window.butterchurn);
    const presetApi = (window.butterchurnPresets.default || window.butterchurnPresets);
    const presets = presetApi.getPresets();
    const names = Object.keys(presets);
    if (!names.length) throw Error('No presets');
    const nativeAudio = window.JaWave && typeof window.JaWave.samples === 'function';
    // Keep the shader workload modest on TV hardware; WebView scales this canvas to the full screen.
    const renderLimitWidth = 800;
    const renderLimitHeight = 450;
    const size = () => {
      const scale = Math.min(1, renderLimitWidth / innerWidth, renderLimitHeight / innerHeight);
      return [Math.max(64, Math.round(innerWidth * scale)), Math.max(64, Math.round(innerHeight * scale))];
    };
    const [w,h] = size();
    canvas.width = w;
    canvas.height = h;
    const visual = engine.createVisualizer(null, canvas, {
      width:w, height:h, meshWidth:32, meshHeight:24, pixelRatio:1
    });
    let index = Math.max(0, names.indexOf('Flexi - mindblob mix'));
    window.nextPreset = () => {
      index = (index + 1) % names.length;
      visual.loadPreset(presets[names[index]], 1.5);
      status.textContent = names[index];
    };
    visual.loadPreset(presets[names[index]], 0);
    status.textContent = names[index];
    addEventListener('resize', () => {
      const [width,height] = size();
      canvas.width = width;
      canvas.height = height;
      visual.setRendererSize(width,height);
    });

    const audioLevels = {
      timeByteArray:new Uint8Array(1024).fill(128),
      timeByteArrayL:new Uint8Array(1024).fill(128),
      timeByteArrayR:new Uint8Array(1024).fill(128)
    };
    let lastAudioSample = 0;
    let lastHeartbeat = 0;
    let heartbeatFrames = 0;
    report('DIRECT_WEBGL_' + w + 'x' + h);

    function render(now) {
      if (canvas.dataset.error) return;
      requestAnimationFrame(render);
      if (document.hidden || (nativeAudio && !window.JaWave.isPlaying())) return;
      try {
        // Audio crosses the native bridge at 30 Hz; Butterchurn renders on every display vsync.
        if (nativeAudio && now - lastAudioSample >= 1000 / 30) {
          const data = JSON.parse(window.JaWave.samples());
          audioLevels.timeByteArray.set(data.mix);
          audioLevels.timeByteArrayL.set(data.left);
          audioLevels.timeByteArrayR.set(data.right);
          lastAudioSample = now;
        }
        visual.render({audioLevels});
        frames++;
        canvas.dataset.frames = String(frames);
        if (now - lastHeartbeat >= 5000) {
          const elapsed = now - lastHeartbeat;
          const fps = lastHeartbeat === 0 ? 0 : Math.round((frames - heartbeatFrames) * 1000 / elapsed);
          let peak = 0;
          for (const sample of audioLevels.timeByteArray) peak = Math.max(peak, Math.abs(sample - 128));
          if (lastHeartbeat !== 0) report('DIRECT_WEBGL_FPS_' + fps + '_' + canvas.width + 'x' + canvas.height, peak);
          lastHeartbeat = now;
          heartbeatFrames = frames;
        }
      } catch (error) { fail(error); }
    }
    requestAnimationFrame(render);
  } catch (error) {
    document.getElementById('error').textContent = 'MilkDrop visualizer needs WebGL 2 support on this device. Music continues playing. Use Back to return to the player.';
    status.textContent = 'Visualizer unavailable';
    canvas.dataset.error = String(error && error.name || 'START_ERROR');
    report('START_ERROR');
  }
})();