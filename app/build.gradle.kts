import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.legacy-kapt")
    id("androidx.baselineprofile")
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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 20901
        versionName = "2.9.1"
        buildConfigField("boolean", "JA_DADDYLIVE_US_PILOT",
            (System.getenv("JA_DADDYLIVE_US_PILOT") == "true").toString())
        val updateRepo = local.getProperty("JA_UPDATE_REPOSITORY", "jdaraver2011/ja_app")
        require(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]+").matches(updateRepo))
        buildConfigField("String", "JA_UPDATE_REPOSITORY", "\"$updateRepo\"")
        buildConfigField("String", "GECKOVIEW_VERSION", "\"160.0.20261104181552\"")
        buildConfigField("String", "TMDB_API_KEY", "\"${prop("TMDB_API_KEY")}\"")
        buildConfigField("String", "AUTHORIZED_EMBED_TEMPLATE", "\"${prop("AUTHORIZED_EMBED_TEMPLATE")}\"")
        buildConfigField("String", "AUTHORIZED_EMBED_HOST", "\"${prop("AUTHORIZED_EMBED_HOST")}\"")
        buildConfigField("String", "DIRECT_MEDIA_TEMPLATE", "\"${prop("DIRECT_MEDIA_TEMPLATE")}\"")
        buildConfigField("String", "DIRECT_MEDIA_HOST", "\"${prop("DIRECT_MEDIA_HOST")}\"")
        buildConfigField("String", "SUPABASE_URL", "\"${prop("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"${prop("SUPABASE_PUBLISHABLE_KEY")}\"")
    }
    val updateStore = System.getenv("JA_SIGNING_STORE")
    if (!updateStore.isNullOrBlank()) {
        signingConfigs.create("updates") {
            storeFile = file(updateStore)
            storePassword = System.getenv("JA_KEYSTORE_PASSWORD") ?: error("Missing JA_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("JA_KEY_ALIAS") ?: error("Missing JA_KEY_ALIAS")
            keyPassword = System.getenv("JA_KEY_PASSWORD") ?: error("Missing JA_KEY_PASSWORD")
        }
        buildTypes.getByName("debug").signingConfig = signingConfigs.getByName("updates")
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("updates")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
// The profile output settings belong to the app target extension.
baselineProfile {
    mergeIntoMain = true
    saveInSrc = true
}
dependencies {
    baselineProfile(project(":baselineprofile"))
    implementation("androidx.browser:browser:1.8.0")
    implementation("org.mozilla.geckoview:geckoview:160.0.20261104181552")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
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
    implementation("androidx.media3:media3-session:1.11.1")
    // API 8 media templates; isolated test branch, no stable auto-update publication.
    implementation("androidx.car.app:app:1.8.0-rc01")
    implementation("androidx.car.app:app-projected:1.8.0-rc01")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// GeckoView 160 integrates Media3 1.11.1. Keep every Media3 module on
// that same version instead of mixing old forced modules with newer HLS/decoder.
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "androidx.media3") useVersion("1.11.1")
    }
}
