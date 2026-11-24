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
        versionCode = 3
        versionName = "0.3.0-beta"
        manifestPlaceholders["appLabel"] = "ELM Lab"
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".beta"
            manifestPlaceholders["appLabel"] = "ELM Lab Beta"
        }
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
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation(project(":elm-core"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
}
