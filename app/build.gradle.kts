plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.aura.launcher"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aura.launcher"
        // AURA targets Android 17 (API 37) exclusively.
        minSdk = 37
        targetSdk = 37
        // versionName is what people see. versionCode only ever goes up so each build installs
        // over the last (pre-release builds already used 1–14).
        versionCode = 15
        versionName = "1.0.0"
    }

    // One stable signing key for debug and release builds. Google sign-in (OAuth) is registered
    // against this key's SHA-1, and it lets new builds install over earlier ones.
    // The keystore is not committed; without it, builds fall back to the local debug key.
    val auraKeystore = rootProject.file("keystore/aura-signing.keystore")
    signingConfigs {
        if (auraKeystore.exists()) {
            create("aura") {
                storeFile = auraKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }
    val auraSigning = signingConfigs.findByName("aura") ?: signingConfigs.getByName("debug")

    buildTypes {
        debug {
            signingConfig = auraSigning
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = auraSigning
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
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
