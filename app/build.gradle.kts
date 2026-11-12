import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.legacy-kapt")
}
val local = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun prop(key: String) = (local.getProperty(key) ?: "").replace("\\", "\\\\").replace("\"", "\\\"")
android {
    namespace = "com.example.ja"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig {
        applicationId = "com.example.ja"
        minSdk = 26
        targetSdk = 35
        versionCode = 11610
        versionName = "1.1.6-review10"
        buildConfigField("String", "GECKOVIEW_VERSION", "\"160.0.20261104181552\"")
        buildConfigField("String", "TMDB_API_KEY", "\"${prop("TMDB_API_KEY")}\"")
        buildConfigField("String", "AUTHORIZED_EMBED_TEMPLATE", "\"${prop("AUTHORIZED_EMBED_TEMPLATE")}\"")
        buildConfigField("String", "AUTHORIZED_EMBED_HOST", "\"${prop("AUTHORIZED_EMBED_HOST")}\"")
        buildConfigField("String", "DIRECT_MEDIA_TEMPLATE", "\"${prop("DIRECT_MEDIA_TEMPLATE")}\"")
        buildConfigField("String", "DIRECT_MEDIA_HOST", "\"${prop("DIRECT_MEDIA_HOST")}\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation("androidx.browser:browser:1.8.0")
    implementation("org.mozilla.geckoview:geckoview:160.0.20261104181552")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    kapt("androidx.room:room-compiler:2.8.5")
    // The updated compiler emits Kotlin 2.4 metadata; keep Room's processor
    // reader on the matching supported metadata library.
    kapt("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.21")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// GeckoView 160 integrates Media3 1.11.1. Keep every Media3 module on
// that same version instead of mixing old forced modules with newer HLS/decoder.
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "androidx.media3") useVersion("1.11.1")
    }
}
