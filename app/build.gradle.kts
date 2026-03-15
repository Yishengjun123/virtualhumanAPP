plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.virhuman"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.virhuman"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "AI_APP_ID", "\"c5c16ddfba234c57b850accc878eaeb1\"")
        buildConfigField("String", "AI_API_KEY", "\"sk-7626139b5ed14779a4329b6f73da5255\"")
        buildConfigField("String", "XFYUN_APP_ID", "\"f65eda8f\"")
        buildConfigField("String", "XFYUN_API_KEY", "\"52ce880e5b2a6a23402e99c735757ecc\"")
        buildConfigField("String", "XFYUN_API_SECRET", "\"OWUxYTRjYWRkNDkxNWFlYjRlM2RkYzgx\"")
        buildConfigField("String", "XFYUN_ABILITY", "\"e867a88f2\"")
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
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    packaging {
        jniLibs {
            pickFirsts += setOf(
                "lib/arm64-v8a/libonnxruntime.so",
                "lib/arm64-v8a/libsherpa-onnx-c-api.so",
                "lib/arm64-v8a/libsherpa-onnx-cxx-api.so",
                "lib/arm64-v8a/libsherpa-onnx-jni.so",
                "lib/armeabi-v7a/libonnxruntime.so",
                "lib/armeabi-v7a/libsherpa-onnx-c-api.so",
                "lib/armeabi-v7a/libsherpa-onnx-cxx-api.so",
                "lib/armeabi-v7a/libsherpa-onnx-jni.so",
                "lib/x86/libonnxruntime.so",
                "lib/x86/libsherpa-onnx-c-api.so",
                "lib/x86/libsherpa-onnx-cxx-api.so",
                "lib/x86/libsherpa-onnx-jni.so",
                "lib/x86_64/libonnxruntime.so",
                "lib/x86_64/libsherpa-onnx-c-api.so",
                "lib/x86_64/libsherpa-onnx-cxx-api.so",
                "lib/x86_64/libsherpa-onnx-jni.so"
            )
        }
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-1.12.27.aar"))
    implementation(files("libs/AIKit.aar"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.mlkit.vision)
    implementation(libs.androidx.camera.extensions)
    implementation(libs.face.detection)
    implementation("com.google.android.exoplayer:exoplayer:2.19.1")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("com.tencent:mmkv-static:1.2.10")
    implementation(
        group = "com.alibaba",
        name = "dashscope-sdk-java",
        version = "2.22.9"
    )
}

