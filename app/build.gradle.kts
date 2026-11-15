import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI passes -PversionCode=<run number>; local builds default to 1.
val appVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.shinobisondre.stockanalyzer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shinobisondre.stockanalyzer"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = "1.0.$appVersionCode"
        buildConfigField("String", "UPDATE_REPO", "\"ShinobiSondre/stock-analyzer-app\"")
        // 64-bit ARM only (every current Galaxy): keeps ML Kit's OCR model from tripling the APK size.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        create("release") {
            // A keystore from the KEYSTORE_FILE env var wins (move the key to a
            // GitHub secret later); otherwise the repo's release.jks is used so
            // every CI build is signed with the same key and updates install.
            val envStore = System.getenv("KEYSTORE_FILE")
            storeFile = if (envStore != null) file(envStore) else file("release.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "stockanalyzer-release"
            keyAlias = System.getenv("KEY_ALIAS") ?: "stockanalyzer"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "stockanalyzer-release"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":engine"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.window:window:1.3.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // On-device OCR for importing broker screenshots (bundled model, works offline).
    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
}
