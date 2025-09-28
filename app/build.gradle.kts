plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") // ← drop the version here
}

android {
    namespace = "com.particleformen.orderswidget"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.particleformen.orderswidget"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }

    buildFeatures { compose = true }

    // If you are NOT using the new compose compiler plugin alias,
    // add this block. Otherwise you can omit it.
    // composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }
}

dependencies {
    // --- Compose BOM keeps versions aligned ---
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))

    // Core Compose bits used by your MainActivity.kt
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Widget + workers + networking (what you already had)
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Glance stores widget state in Preferences DataStore
    implementation("androidx.compose.material3:material3:1.3.2")


    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
