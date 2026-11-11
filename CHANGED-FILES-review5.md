# Review5 changes relative to the uploaded improved branch

- JaGeckoAdBlockers.kt: lifecycle observer, serial operations, no cached Active
  success, version logs, status flow, initialization deadlines and page URLs.
- JaBlockerLifecycleState.java (new): selection generations and ready invalidation.
- JaBlockerTools.kt (new): scoped dashboard/logger Gecko session.
- MainActivity.kt: live Settings status, tool entry, blocker warning and explicit
  Off-and-retry recovery action.
- GeckoExperimentalPlayer.kt: page progress diagnostics.
- app/build.gradle.kts: 1.1.6-review5 / 11605 and Gecko dependency-version log field.
- tests/JaBlockerLifecycleStateTest.java (new): stale selection/lifecycle cases.
- .github/workflows/build-apk.yml: current test/build/artifact workflow.
- .github/workflows/test1.yml: removed; delete the old file in the GitHub branch.
- .gitignore: exclude local configuration, build outputs and compiler caches.
- README.md and VALIDATION-review5.md: build, diagnostics and scope.

Engine version, icon assets, source providers, genre rules, playback-control JS,
frame-matching and resume implementation are retained from the uploaded branch.
