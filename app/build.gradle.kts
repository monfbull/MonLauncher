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
        minSdk = 31
        targetSdk = 34
        // Increase versionCode for every distributed build.
        versionCode = 2
        versionName = "0.1.0-beta.2"
    }
    // Release signing config, read from keystore.properties (not in the repo).
    // All releases must use the same key, or they won't install as updates.
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
            // Fail instead of falling back to the debug key.
            signingConfig = signingConfigs.findByName("release")
                ?: throw GradleException("Release signing needs keystore.properties (see app/build.gradle.kts)")
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
    implementation("com.google.zxing:core:3.5.3") // QR codes
}

// Save R8's mapping for each release under release-mappings/<version>/ (gitignored; attach it to
// the GitHub release). It's needed to decode crash traces from that build.
tasks.matching { it.name == "assembleRelease" }.configureEach {
    doLast {
        val version = "${android.defaultConfig.versionName}-${android.defaultConfig.versionCode}"
        copy {
            from(layout.buildDirectory.file("outputs/mapping/release/mapping.txt"))
            into(rootProject.file("release-mappings/$version"))
        }
    }
}
