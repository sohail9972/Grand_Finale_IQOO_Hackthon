// ---------------------------------------------------------------------------
// :app module - Animal Health Bridge
// ---------------------------------------------------------------------------
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // The Compose compiler is a Kotlin plugin as of Kotlin 2.0, not a separate
    // androidx compiler artifact.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    // KSP generates the Room DAO/Entity boilerplate.
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.sohailsquad.animalhealthbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sohailsquad.animalhealthbridge"
        // 27, not 26: the GenieX AAR declares <uses-sdk minSdkVersion="27" /> in
        // its own manifest, so the whole app must be 27+ to link against it.
        minSdk = 27          // Android 8.1
        targetSdk = 35       // Android 15; the spec targets Android 13+ behaviour
        versionCode = 1
        versionName = "0.1.0-step1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        debug {
            // Keep debug builds fast; no minification while iterating.
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
            )
        }
        jniLibs {
            useLegacyPackaging = true

            // The sherpa-onnx "static link" AAR is meant to bundle ONNX Runtime
            // internally, and does so correctly for arm64/armeabi-v7a/x86_64.
            // For 32-bit x86 it ALSO ships a libonnxruntime.so, which collides
            // with the one from onnxruntime-android and fails the merge.
            //
            // Our target hardware is arm64-v8a, so the 32-bit x86 copy is dead
            // weight: dropping it removes the collision and shrinks the APK.
            // x86_64 (the emulator ABI) is left untouched.
            excludes += "lib/x86/libonnxruntime.so"
        }
    }

    // Model payloads are memory-mapped at runtime, so they must not be
    // compressed inside the APK.
    androidResources {
        noCompress += setOf("task", "onnx", "bin", "ort", "tflite")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// ---------------------------------------------------------------------------
// KSP / Room configuration.
//
// This block must sit at the top level of the module script (it is a KSP
// extension, not an `android {}` property). The exported schema directory is
// checked into version control so Step 2+ migrations can be diffed.
// ---------------------------------------------------------------------------
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

dependencies {
    // --- AndroidX core -----------------------------------------------------
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)

    // --- Lifecycle ---------------------------------------------------------
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // --- Compose (versions come from the BOM) ------------------------------
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // --- Persistence -------------------------------------------------------
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    // --- Coroutines + serialization ---------------------------------------
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // --- On-device AI ------------------------------------------------------
    // Offline Hindi ASR (sherpa-onnx + a streaming zipformer transducer).
    // The "static" variant links ONNX Runtime internally, so it does not clash
    // with the onnxruntime-android artifact used for translation below.
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"))
    // MediaPipe LLM Inference (Gemma-3 1B) - wired up in Step 5.
    implementation(libs.mediapipe.tasks.genai)
    // ONNX Runtime for IndicTrans2 (Step 4) and IndicConformer ASR (Step 3).
    implementation(libs.onnxruntime.android)
    // Qualcomm GenieX: Qwen3 inference on the Snapdragon NPU (QAIRT/Hexagon).
    // Replaces the MediaPipe Gemma-3 path in LlmEngine.
    implementation(libs.geniex.android)

    // --- Camera / media ----------------------------------------------------
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation("androidx.camera:camera-video:1.4.0")
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.accompanist.permissions)
    // Bundled ML Kit barcode model: runs fully on-device, so the QR scanner
    // works in airplane mode.
    implementation(libs.mlkit.barcode.scanning)

    // --- Test --------------------------------------------------------------
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
