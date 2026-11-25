import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "mon.launcher"
    compileSdk = 35
    defaultConfig {
        applicationId = "mon.launcher"
        minSdk = 30
        targetSdk = 34
        // Increase versionCode for every distributed build.
        versionCode = 3
        versionName = "0.1.0-beta.3"
    }
    // Reads keystore.properties (not in the repo).
    // Every release must use the same key, or it won't install as an update.
    val keystore = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore.getProperty("storeFile"))
            storePassword = keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = keystore.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Null without keystore.properties.
            // Debug builds still work. Release builds fail (see the end of this file).
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    androidResources { noCompress += "png" }
}

dependencies {
    implementation(project(":core"))
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.9.3")
    // The app uses these directly, so they're declared here.
    // They are minimum versions. Gradle uses newer ones if activity-compose pulls them in.
    implementation("androidx.core:core-ktx:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.google.zxing:core:3.5.3") // QR codes
}

// Saves R8's mapping to release-mappings/<version>/ (gitignored).
// Attach it to the GitHub release: it's needed to decode crash traces from that build.
tasks.matching { it.name == "assembleRelease" }.configureEach {
    doLast {
        val version = "${android.defaultConfig.versionName}-${android.defaultConfig.versionCode}"
        copy {
            from(layout.buildDirectory.file("outputs/mapping/release/mapping.txt"))
            into(rootProject.file("release-mappings/$version"))
        }
    }
}

// Fails before any release task runs, instead of building an unsigned APK.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        if (!rootProject.file("keystore.properties").exists()) {
            throw GradleException("Release signing needs keystore.properties (see app/build.gradle.kts)")
        }
    }
}
