import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

/**
 * Load the Google Maps API key from local.properties at configure time.
 *
 * local.properties is in .gitignore by default for Android Studio projects,
 * so the raw key never reaches version control. A missing key degrades to
 * an empty string — the repository layer treats that as "no live data" and
 * falls back to the old jitter-based mock.
 */
val googleMapsApiKey: String = run {
    val props = Properties()
    val f = rootProject.file("local.properties")
    if (f.exists()) props.load(f.inputStream())
    props.getProperty("GOOGLE_MAPS_API_KEY")
        ?: System.getenv("GOOGLE_MAPS_API_KEY")
        ?: ""
}

/**
 * Optional OpenWeatherMap key. Populates the BuildConfig field that the
 * weather repository reads — when blank, the repository short-circuits and
 * returns null so the surge predictor falls back to the no-weather pathway.
 */
val openWeatherApiKey: String = run {
    val props = Properties()
    val f = rootProject.file("local.properties")
    if (f.exists()) props.load(f.inputStream())
    props.getProperty("OPEN_WEATHER_API_KEY")
        ?: System.getenv("OPEN_WEATHER_API_KEY")
        ?: ""
}

android {
    namespace = "com.farewise.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.farewise.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // Exposed at runtime as BuildConfig.GOOGLE_MAPS_API_KEY — never hard-code.
        buildConfigField("String", "GOOGLE_MAPS_API_KEY", "\"$googleMapsApiKey\"")
        manifestPlaceholders["GOOGLE_MAPS_API_KEY"] = googleMapsApiKey
        buildConfigField("String", "OPEN_WEATHER_API_KEY", "\"$openWeatherApiKey\"")
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
        // Enables java.time.* on minSdk 24 (java.time is native from API 26).
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        // Required so BuildConfig.GOOGLE_MAPS_API_KEY is generated.
        buildConfig = true
    }

    // TFLite models ship as raw binary in assets; compression breaks memory-mapped load.
    androidResources {
        noCompress += "tflite"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,DEPENDENCIES}"
        }
    }
}

dependencies {
    // --- Core / Lifecycle ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // --- Compose ---
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // --- Hilt ---
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // --- Room ---
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // --- DataStore ---
    implementation(libs.androidx.datastore.preferences)

    // --- Coroutines ---
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // --- WorkManager (Phase 3 background inference) ---
    implementation(libs.androidx.work.runtime.ktx)

    // --- TensorFlow Lite (Phase 3 on-device inference) ---
    implementation(libs.tensorflow.lite)
    implementation(libs.tensorflow.lite.support)

    // --- Location & Maps ---
    implementation(libs.play.services.location)
    implementation(libs.play.services.maps)
    implementation(libs.maps.compose)

    // --- Network (Google Maps Routes API) ---
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)

    // --- Logging ---
    implementation(libs.timber)

    // --- Splash screen ---
    implementation(libs.androidx.core.splashscreen)

    // --- Desugaring ---
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // --- Testing ---
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
