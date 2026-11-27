plugins {
    id("com.android.application") version "8.8.2"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("com.google.devtools.ksp") version "2.0.21-1.0.28"
}

val debugBuildNumber = providers.environmentVariable("BRIEFSONA_DEBUG_BUILD_NUMBER")
        .map { it.toInt().also { number -> require(number in 1..2_099_999_997) } }.orNull

android {
    namespace = "com.macguyverism.briefsona"
    compileSdk = 35

    defaultConfig {
        val projectOrigin = providers.environmentVariable("BRIEFSONA_PROJECT_URL").orNull.orEmpty().trimEnd('/')
        require(projectOrigin.isEmpty() || Regex("https://[a-z0-9-]+\\.supabase\\.co").matches(projectOrigin)) {
            "Expected a public Supabase project origin"
        }
        buildConfigField("String", "PROJECT_ORIGIN", "\"$projectOrigin\"")
        val backupOrigin = providers.environmentVariable("BRIEFSONA_BACKUP_URL").orNull.orEmpty().trimEnd('/')
        val backupKey = providers.environmentVariable("BRIEFSONA_BACKUP_PUBLISHABLE_KEY").orNull.orEmpty()
        require(backupOrigin.isEmpty() || Regex("https://[a-z0-9-]+\\.supabase\\.co").matches(backupOrigin)) {
            "Expected a public Supabase backup project origin"
        }
        require(backupKey.isEmpty() || Regex("sb_publishable_[A-Za-z0-9_-]+").matches(backupKey)) {
            "Expected a Supabase publishable key"
        }
        buildConfigField("String", "STATION_BACKUP_ORIGIN", "\"$backupOrigin\"")
        buildConfigField("String", "STATION_BACKUP_KEY", "\"$backupKey\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        applicationId = "com.macguyverism.briefsona"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.0.23"
    }

    val debugKeystore = providers.environmentVariable("BRIEFSONA_DEBUG_KEYSTORE_PATH").orNull
    val debugPasswordFile = providers.environmentVariable("BRIEFSONA_DEBUG_PASSWORD_PATH").orNull
    require((debugKeystore == null) == (debugPasswordFile == null)) {
        "Configure both Briefsona debug signing paths"
    }
    if (debugKeystore != null) {
        requireNotNull(debugBuildNumber) { "Set BRIEFSONA_DEBUG_BUILD_NUMBER for a signed test APK" }
        val password = file(requireNotNull(debugPasswordFile)).readText().trim()
        signingConfigs.getByName("debug") {
            storeFile = file(debugKeystore)
            storeType = "PKCS12"
            storePassword = password
            keyAlias = "briefsona-debug"
            keyPassword = password
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

androidComponents {
    if (debugBuildNumber != null) {
        onVariants(selector().withBuildType("debug")) { variant ->
            variant.outputs.forEach { it.versionCode.set(2 + debugBuildNumber) }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.3")
    ksp("androidx.room:room-compiler:2.6.1")
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
