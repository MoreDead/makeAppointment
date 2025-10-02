plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jlleitschuh.gradle.ktlint")
}

android {
    namespace = "com.example.docscanics"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.docscanics"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Code style: ktlint
    // See: https://github.com/JLLeitschuh/ktlint-gradle
    // Run checks: ./gradlew :app:ktlintCheck
    // Auto-format: ./gradlew :app:ktlintFormat
    // Treat as Android project for appropriate defaults
    // (no additional rules configured yet)
    // You can configure reporters, disabled rules, etc., if needed.
}

ktlint {
    android.set(true)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Lifecycle & Activity
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    // ML Kit Text Recognition (on-device)
    implementation("com.google.mlkit:text-recognition:16.0.0")

    // Google Gemini AI for enhanced text extraction
    implementation("com.google.ai.client.generativeai:generativeai:0.7.0")

    // EXIF handling for image orientation
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Kotlin Coroutines for async operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.0")

    // Java 8+ APIs desugaring (for java.time etc.)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
