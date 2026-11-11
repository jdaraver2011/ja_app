# Combined update: review3

Contains all review2 source and fixes, plus:

- AndroidManifest.xml: launcher, round icon and Android TV banner references.
- app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}: generated icons.
- app/src/main/res/mipmap-anydpi-v26: adaptive icon definitions.
- app/src/main/res/values/icon_colors.xml: black adaptive background.
- app/src/main/res/drawable-xhdpi/tv_banner.png: 320 x 180 Android TV banner.
- branding/ja-icon-original.png: original supplied image, unchanged.
- scripts/prepare_icons.py: repeatable icon resource generation.
- app/build.gradle.kts: version 1.1.6-review3 / 11603.
- .github/workflows/build-apk.yml: review3 artifact name.
- README.md: combined upload and validation instructions.

Keep app/src/main/java/com/example/ja/JaStartupBarrier.java and
 tests/JaStartupBarrierTest.java from review2; both are included in this ZIP.
Upload all contents together, including the resource directories.
