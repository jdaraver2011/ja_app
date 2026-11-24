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
    const profiling = nativePresentation && typeof window.JaWave.profilingEnabled === 'function' &&
      window.JaWave.profilingEnabled();
    let profileHeight = 720;
    const newSample = () => ({start:0, frames:0, pending:0, pcm:0, render:0, copy:0, jpeg:0, bridge:0, gpu:0, gpuFrames:0});
    let sample = profiling ? newSample() : null;
    // Use a 720p backing surface for 16:9, fitting other aspect ratios inside it.
    const renderLimitWidth = 1280;
    const renderLimitHeight = 720;
    const size = () => {
      if (profiling) return [profileHeight === 540 ? 960 : 1280, profileHeight];
      const pixelRatio = Math.max(1, window.devicePixelRatio || 1);
      const screenWidth = Math.max(64, Math.round(innerWidth * pixelRatio));
      const screenHeight = Math.max(64, Math.round(innerHeight * pixelRatio));
      const scale = Math.min(renderLimitWidth / screenWidth, renderLimitHeight / screenHeight);
      return [Math.max(64, Math.round(screenWidth * scale)), Math.max(64, Math.round(screenHeight * scale))];
    };
    const [w,h] = size();
    canvas.width = renderCanvas.width = w;
    canvas.height = renderCanvas.height = h;
    const visual = engine.createVisualizer(null, renderCanvas, {width:w, height:h, meshWidth:32, meshHeight:24, pixelRatio:1});
    const gl = profiling ? renderCanvas.getContext('webgl2') : null;
    const gpuTimer = gl?.getExtension('EXT_disjoint_timer_query_webgl2');
    let gpuQuery = null;
    let index = Math.max(0, names.indexOf('Flexi - mindblob mix'));
    window.nextPreset = () => { index = (index + 1) % names.length; visual.loadPreset(presets[names[index]], 1.5); status.textContent = names[index]; };
    visual.loadPreset(presets[names[index]], 0);
    status.textContent = names[index];
    function resize() {
      const [width,height] = size();
      if (canvas.width === width && canvas.height === height) return;
      canvas.width = renderCanvas.width = width;
      canvas.height = renderCanvas.height = height;
      visual.setRendererSize(width,height);
    }
    addEventListener('resize', resize);
    if (profiling) {
      window.setJaProfileResolution = height => {
        if ((height !== 540 && height !== 720) || height === profileHeight) return;
        if (gpuQuery) gl.deleteQuery(gpuQuery);
        gpuQuery = null;
        profileHeight = height;
        sample = newSample();
        resize();
      };
    }

    const audioLevels = {
      timeByteArray:new Uint8Array(1024).fill(128),
      timeByteArrayL:new Uint8Array(1024).fill(128),
      timeByteArrayR:new Uint8Array(1024).fill(128)
    };
    const frameInterval = 1000 / 30;
    let nextFrame = 0;
    let heartbeat = 0;
    let heartbeatFrames = 0;
    report('READY_' + w + 'x' + h);

    function render(now) {
      if (canvas.dataset.error) return;
      requestAnimationFrame(render);
      if (document.hidden) return;
      try {
        if (nativePresentation) {
          // Skip expensive work while the previous bitmap is still in flight.
          if (now + 0.5 < nextFrame || !window.JaWave.isPlaying()) return;
          if (!window.JaWave.ready()) {
            if (sample) sample.pending++;
            return;
          }
          nextFrame = now - nextFrame >= frameInterval ? now + frameInterval : nextFrame + frameInterval;
          if (sample && !sample.start) sample.start = performance.now();
          const pcmStart = sample ? performance.now() : 0;
          const data = JSON.parse(window.JaWave.samples());
          audioLevels.timeByteArray.set(data.mix);
          audioLevels.timeByteArrayL.set(data.left);
          audioLevels.timeByteArrayR.set(data.right);
          if (sample) sample.pcm += performance.now() - pcmStart;
        }
        let timedRender = false;
        if (gpuTimer) {
          const disjoint = gl.getParameter(gpuTimer.GPU_DISJOINT_EXT);
          if (gpuQuery && (disjoint || gl.getQueryParameter(gpuQuery, gl.QUERY_RESULT_AVAILABLE))) {
            if (!disjoint) {
              sample.gpu += gl.getQueryParameter(gpuQuery, gl.QUERY_RESULT) / 1e6;
              sample.gpuFrames++;
            }
            gl.deleteQuery(gpuQuery);
            gpuQuery = null;
          }
          if (!gpuQuery && !disjoint) {
            gpuQuery = gl.createQuery();
            if (gpuQuery) {
              gl.beginQuery(gpuTimer.TIME_ELAPSED_EXT, gpuQuery);
              timedRender = true;
            }
          }
        }
        let stage = sample ? performance.now() : 0;
        try { visual.render({audioLevels}); }
        finally { if (timedRender) gl.endQuery(gpuTimer.TIME_ELAPSED_EXT); }
        if (sample) {
          sample.render += performance.now() - stage;
          stage = performance.now();
        }
        frames++;
        canvas.dataset.frames = String(frames);

        // Retain the working WebGL -> 2D canvas -> Android ImageView path.
        // Transfer at most 30 frames/s and keep one native bitmap in flight.
        presenter.drawImage(renderCanvas, 0, 0, canvas.width, canvas.height);
        if (nativePresentation) {
          if (sample) {
            sample.copy += performance.now() - stage;
            stage = performance.now();
          }
          const jpeg = canvas.toDataURL('image/jpeg', 0.55);
          if (sample) {
            sample.jpeg += performance.now() - stage;
            stage = performance.now();
          }
          window.JaWave.present(jpeg, names[index]);
          if (sample) {
            const end = performance.now();
            sample.bridge += end - stage;
            sample.frames++;
            const elapsed = end - sample.start;
            if (elapsed >= 5000) {
              const mean = value => (value / sample.frames).toFixed(2);
              window.JaWave.reportProfile(`size=${canvas.width}x${canvas.height} fps=${(sample.frames * 1000 / elapsed).toFixed(1)} frames=${sample.frames} blockedCallbacks=${sample.pending} pcmMs=${mean(sample.pcm)} renderSubmitMs=${mean(sample.render)} copyMs=${mean(sample.copy)} jpegMs=${mean(sample.jpeg)} bridgeCallMs=${mean(sample.bridge)} gpuMs=${sample.gpuFrames ? (sample.gpu / sample.gpuFrames).toFixed(2) : 'na'} gpuSamples=${sample.gpuFrames}`);
              sample = newSample();
            }
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
