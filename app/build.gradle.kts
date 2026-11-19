import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.aura.launcher"
    compileSdk = 37

    defaultConfig {
        // The app's ID on Google Play and on the phone ("com.aura.launcher" was already taken on Play).
        // The code itself stays in the com.aura.launcher package (namespace above).
        applicationId = "com.auralab.launcher"
        // AURA targets Android 17 (API 37) exclusively.
        minSdk = 37
        targetSdk = 37
        // versionName is what people see. versionCode only ever goes up so each build installs
        // over the last (pre-release builds already used 1–14).
        versionCode = 19
        versionName = "1.2.1"

        // Where "Report this reply" sends reports (an HTTPS endpoint that accepts a JSON POST).
        // Set auraReportUrl in gradle.properties or with -PauraReportUrl=… for store builds.
        // Empty: reports open a pre-filled GitHub issue instead.
        buildConfigField("String", "REPORT_URL", "\"${project.findProperty("auraReportUrl") ?: ""}\"")
    }

    // Signing. Neither key is committed (see .gitignore); back both up.
    // - Release builds (what goes to Google Play) use AURA's release/upload key, configured in
    //   keystore/release.properties. Play refuses anything signed with a debug key.
    // - Debug builds use a shared debug key so they install over each other.
    // Without the release key, release builds fall back to the debug key: fine for sideloading,
    // but Google Play will reject them.
    val auraKeystore = rootProject.file("keystore/aura-signing.keystore")
    val releaseProps = rootProject.file("keystore/release.properties")
    signingConfigs {
        if (auraKeystore.exists()) {
            create("aura") {
                storeFile = auraKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        if (releaseProps.exists()) {
            val p = Properties().apply { releaseProps.inputStream().use { load(it) } }
            create("release") {
                storeFile = rootProject.file("keystore/" + p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }
    val auraSigning = signingConfigs.findByName("aura") ?: signingConfigs.getByName("debug")
    val releaseSigning = signingConfigs.findByName("release")
        ?: auraSigning.also { logger.warn("AURA: keystore/release.properties not found, so release builds are signed with a DEBUG key. Google Play will reject them.") }

    buildTypes {
        debug {
            signingConfig = auraSigning
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = releaseSigning
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    // Google sign-in + OAuth authorization (Gemini API access with the user's Google account)
    implementation("com.google.android.gms:play-services-auth:22.0.0")

    // Gemini Nano on the phone (no account, no key) through Android AICore, where supported.
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta5")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Screenshot tests rendered on the JVM with Robolectric native graphics.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.18")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test.espresso:espresso-core:3.8.0-alpha04")
}
