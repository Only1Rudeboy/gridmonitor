plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optionaler Release-Schlüssel: wird nur verwendet, wenn die Umgebungsvariablen
// gesetzt sind (in der GitHub Action aus Secrets). Sonst wird der Debug-Schlüssel
// benutzt, damit auch ohne Secrets eine installierbare APK entsteht.
val keystoreFile: String? = System.getenv("UV_KEYSTORE_FILE")
val hasReleaseKeystore = !keystoreFile.isNullOrBlank() && file(keystoreFile).exists()

android {
    namespace = "at.osmovoltaik.uvwarner"
    compileSdk = 35

    defaultConfig {
        applicationId = "at.osmovoltaik.uvwarner"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreFile!!)
                storePassword = System.getenv("UV_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("UV_KEY_ALIAS")
                keyPassword = System.getenv("UV_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
