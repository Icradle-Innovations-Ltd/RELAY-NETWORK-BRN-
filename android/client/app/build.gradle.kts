plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.brn.client"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.brn.client"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "CONTROL_PLANE_BASE_URL", "\"https://relay.healthlinks.ug/api\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("com.wireguard.android:tunnel:1.0.20250531")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
