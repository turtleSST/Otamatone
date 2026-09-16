import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseProperties = Properties().apply {
    val path = providers.gradleProperty("releaseSigningProperties").orElse(".signing/signing.properties").get()
    val config = rootProject.file(path)
    if (config.isFile) config.inputStream().use { load(it) }
}
fun signingValue(property: String, environment: String): String? =
    providers.environmentVariable(environment).orNull?.takeIf { it.isNotBlank() }
        ?: releaseProperties.getProperty(property)?.takeIf { it.isNotBlank() }
val releaseStore = signingValue("storeFile", "TADPOLE_STORE_FILE")
val releaseStorePassword = signingValue("storePassword", "TADPOLE_STORE_PASSWORD")
val releaseAlias = signingValue("keyAlias", "TADPOLE_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "TADPOLE_KEY_PASSWORD")
val releaseSigningReady = listOf(releaseStore,releaseStorePassword,releaseAlias,releaseKeyPassword).all { it != null }

android {
    namespace = "com.tadpole.instrument"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.tadpole.instrument"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        if (releaseSigningReady) create("release") {
            storeFile = rootProject.file(releaseStore!!)
            storePassword = releaseStorePassword
            keyAlias = releaseAlias
            keyPassword = releaseKeyPassword
            enableV1Signing = false // minSdk 26 supports APK Signature Scheme v2.
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = false
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (releaseSigningReady) signingConfigs.getByName("release") else null
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    lint { abortOnError = true }
}

// Debug builds and unit tests need no private key. Release packaging must never
// silently fall back to a debug certificate or an unsigned deliverable.
tasks.matching { it.name in setOf("packageRelease", "packageReleaseBundle", "signReleaseBundle") }.configureEach {
    doFirst {
        check(releaseSigningReady) {
            "Release signing is not configured. See docs/release.md or run python scripts/setup_release_signing.py."
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
