# Changes from review3

- JaFrameRateSampler.java (new): validate one-second or five-second frame samples,
  require two consistent intervals, reset after seek/counter discontinuities.
- JaTvFrameRateMatcher.kt: use cadence sampler; reset when video dimensions change.
- MainActivity.kt: log installed build version under JA_STARTUP.
- tests/JaFrameRateSamplerTest.java (new): includes actual frame/time pairs from
  the user's ADB log and recovery/discontinuity cases.
- app/build.gradle.kts: version 1.1.6-review4 / 11604.
- .github/workflows/build-apk.yml: run cadence tests; label review4 APK artifact.
- README.md: install/version verification and actual display-match diagnostics.

Full source is included, with all review2 startup fixes and review3 icon assets.
No changes to JavaScript remote/autoplay behavior, source registry, catalogue
loading or resume logic in this frame-matching patch.
