// Match GeckoView 160 / Media3's Kotlin 2.4 metadata while using AGP's
// built-in Kotlin integration (the kotlin-android plugin is not applied).
buildscript {
    repositories { google(); mavenCentral() }
    dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21") }
}

plugins {
    id("com.android.application") version "9.5.1" apply false
    id("com.android.legacy-kapt") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
