plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.evo.merger"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.evo.merger"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs.pickFirsts += "**/libc++_shared.so" }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("dev.ffmpegkit-maintained:ffmpeg:6.0.3")
}
