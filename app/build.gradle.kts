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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(
        group = "com.alibaba",
        name = "dashscope-sdk-java",
        version = "2.22.9"
    )
}
