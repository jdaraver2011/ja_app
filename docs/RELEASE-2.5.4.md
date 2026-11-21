# JA 2.5.4 — Music visualizer canvas correction

The Pixel 7 Pro / Android 17 device log shows live PCM samples and approximately 24 render calls/second, with no reported JS error, while all presets remain black. Inspection of the pinned Butterchurn 2.6.7 implementation shows it configures renderer viewport/texture sizes but never sizes the supplied output canvas. JA supplied large renderer dimensions while leaving the actual drawing buffer at the browser default 300×150. CSS sizing does not resize that buffer; the viewport can crop away the intended output.

JA now sets canvas.width and canvas.height before renderer creation and keeps both synchronized on resize. Renderer settings, canvas and drawing buffer use the same capped dimensions. This is a confirmed integration defect; Pixel device retesting is required to confirm it fully resolves that phone's symptom.

A Node regression fails on the old implementation and passes with matching portrait startup and landscape resize dimensions. Browser and real Android WebView tests now require nonzero sampled RGB output as well as live PCM and frame progress. Android uses a synthetic sine waveform and checks canvas/viewport width. One small, bounded pixel sample per preset reports output peak and GL error through JA_MUSIC_VIS; it does not continuously read full framebuffers.

Version code 20504 is higher than the installed 2.5.3 (20503), allowing Settings > Update Now. Publication is enabled, using the existing persistent signing key. Music 2.5.3 layout changes and playback/foreground protections are retained.
