plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.arpalaser.iot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.arpalaser.iot"
        // API 26 (Android 8.0): mínimo que garantiza PBKDF2WithHmacSHA256 y Android Keystore AES-GCM.
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // Ofuscación y reducción de código (dificulta la ingeniería inversa - ISO 27400).
            isMinifyEnabled = true
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // Cifrado de preferencias (sesión y clave del dispositivo) respaldado por Android Keystore.
    implementation("androidx.security:security-crypto:1.0.0")

    testImplementation("junit:junit:4.13.2")
}
