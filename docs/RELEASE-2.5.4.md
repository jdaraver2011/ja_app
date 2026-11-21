# JA 2.5.4 — Music visualizer canvas correction

The Pixel 7 Pro / Android 17 device log shows live PCM samples and approximately 24 render calls/second, with no reported JS error, while all presets remain black. Inspection of the pinned Butterchurn 2.6.7 implementation shows it configures renderer viewport/texture sizes but never sizes the supplied output canvas. JA supplied large renderer dimensions while leaving the actual drawing buffer at the browser default 300×150. CSS sizing does not resize that buffer; the viewport can crop away the intended output.

JA now sets canvas.width and canvas.height before renderer creation and keeps both synchronized on resize. Renderer settings, canvas and drawing buffer use the same capped dimensions. This is a confirmed integration defect; Pixel device retesting is required to confirm it fully resolves that phone's symptom.

A Node regression fails on the old implementation and passes with matching portrait startup and landscape resize dimensions. Browser and real Android WebView tests now require nonzero sampled RGB output as well as live PCM and frame progress. Android uses a synthetic sine waveform and checks canvas/viewport width. One small, bounded pixel sample per preset reports output peak and GL error through JA_MUSIC_VIS; it does not continuously read full framebuffers.

Version code 20504 is higher than the installed 2.5.3 (20503), allowing Settings > Update Now. Publication is enabled, using the existing persistent signing key. Music 2.5.3 layout changes and playback/foreground protections are retained.


## Published verification

- [Workflow 44952719326](https://github.com/jdaraver2011/ja_app/actions/runs/44952719326), built commit `3a03c747f00bba5524c43dab480d60632381e42b`.
- Browser requires nonzero RGB pixels and correctly sized startup/rotation buffers; source canvas regression fails against the prior implementation and passes after the correction.
- Android 35 emulator: **5 passed, 0 failures, 0 errors, 0 skipped**. Real WebView test 26.819s, with `WEB_OUTPUT_381x720_GL0 frames=40 jsPeak=58`: correctly sized output, nonzero sampled RGB and no GL error. Synthetic PCM was used. The early null-canvas console message in its log comes from the test's evaluation probe before page loading, not the shipped rendering loop.
- Playback lifetime, large queue selection, library navigation and playlist persistence passed, along with existing regressions, compilation and strict lint.
- Latest public release: [v2.5.4](https://github.com/jdaraver2011/ja_app/releases/tag/v2.5.4), not draft/prerelease.
- APK `com.example.ja`, version `2.5.4`, code `20504`, size **561591468 bytes**, SHA-256 `c68ba1ed91aa897732ca591ddcaf230eab90883398e25f3bf26cab1a0ba01aa6`.
- Public update metadata was downloaded and matched against the uploaded APK asset's size and digest. Install using **Settings > Update Now**.
- Pixel 7 Pro / Android 17 should be retested to confirm the phone's black-screen symptom is resolved. The existing JA_MUSIC_VIS log capture now includes output-buffer dimensions, sampled RGB peaks and GL error codes.
