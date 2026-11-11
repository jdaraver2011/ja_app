# Changes from review1

- app/src/main/java/com/example/ja/JaStartupBarrier.java (new): one-shot startup gate.
- app/src/main/java/com/example/ja/GeckoExperimentalPlayer.kt: await cached installs,
  register all delegates before loading, remove 15-second load-anyway path,
  cancel pending startup on disposal, log startup stages and elapsed time.
- app/src/main/java/com/example/ja/MainActivity.kt: prewarm built-ins, show pending
  setup message and allow the longer first-run setup deadline.
- app/build.gradle.kts: version 1.1.6-review2 / 11602.
- .github/workflows/build-apk.yml: startup gate JVM tests and review2 artifact name.
- tests/JaStartupBarrierTest.java (new): asynchronous ordering and lifecycle tests.
- README.md: cause hypothesis, build instructions, validation limits and device test.

No JavaScript player asset changed from review1. Catalogue and resume behavior
are unchanged in this cold-start patch. This full ZIP includes every source file.
