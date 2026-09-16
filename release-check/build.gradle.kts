plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Standalone instrumentation APK. A test variant of :app would omit shared
// Kotlin/AndroidX dependencies that R8 renames in the production APK.
android {
    namespace = "com.tadpole.instrument.releasecheck"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.tadpole.instrument.releasecheck"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("junit:junit:4.13.2")
    implementation("androidx.test:runner:1.6.2")
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
