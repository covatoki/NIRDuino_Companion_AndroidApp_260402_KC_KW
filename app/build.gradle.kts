plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("kotlin-kapt")
    id("kotlin-parcelize")
    id("dagger.hilt.android.plugin")
}

android {

    buildFeatures {
        compose = true
    }

    namespace = "com.example.nirduino_android_app_v2"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.nirduino_android_app_v2"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    packagingOptions {
        resources.pickFirsts += setOf("lib/**/libsqlcipher.so")
    }

}

val roomVersion       = "2.7.2"
val coroutinesVersion = "1.8.1"
val lifecycleVersion  = "2.8.0"
val hiltVersion       = "2.51"

dependencies {

    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.1.10"))

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.activity:activity-compose:1.9.0")

    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.compose.material3:material3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.media3:media3-common-ktx:1.7.1")
    implementation("androidx.room:room-common-jvm:2.7.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.1")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")

    // ── Room + SQLCipher ────────────────────────────────────────────────
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    implementation("net.zetetic:android-database-sqlcipher:4.5.4@aar")
    implementation("androidx.sqlite:sqlite:2.3.1")
    implementation("androidx.sqlite:sqlite-framework:2.3.1")

    // ── Coroutines ──────────────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")

    // ── Lifecycle / ViewModel ──────────────────────────────────────────
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:$lifecycleVersion")

    // ── UI helpers ─────────────────────────────────────────────────────
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.12.0")

    // ── Hilt (optional) ────────────────────────────────────────────────
    implementation("com.google.dagger:hilt-android:$hiltVersion")
    kapt("com.google.dagger:hilt-android-compiler:$hiltVersion")
    implementation("androidx.hilt:hilt-navigation-fragment:1.0.0")

    // Jetpack Security (encrypted prefs for DB key)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // OkHttp for HTTP requests
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("org.apache.commons:commons-math3:3.6.1")

}

/* ---------- KAPT speed-ups ---------- */
kapt {
    correctErrorTypes = true
}
