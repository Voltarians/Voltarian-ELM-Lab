plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "org.voltarians.elmlab.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.voltarians.elmlab"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-beta"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":elm-core"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
}
