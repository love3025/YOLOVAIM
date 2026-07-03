plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "team.maodie.aimbot"
    compileSdk = 36

    defaultConfig {
        applicationId = "team.maodie.aimbot"
        minSdk = 31
        targetSdk = 35
        versionCode = 20
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += ""
            }
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.jks")
            storePassword = "aimbot123456"
            keyAlias = "aimbot"
            keyPassword = "aimbot123456"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md"
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
        viewBinding = true
        aidl = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(libs.onnxruntime.android)
    implementation(libs.tensorflow.lite) {
        exclude(group = "org.tensorflow", module = "tensorflow-lite-support-api")
    }
    implementation(libs.tensorflow.lite.gpu)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // External Shizuku (used when the device has Shizuku app installed; the existing
    // ShizukuInjectorClient path).  This is a runtime fallback, not the primary path.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    // Vendored AimSvc server runtime — hidden API access, parcelable list, gson, etc.
    implementation(libs.rikka.hidden.compat)
    compileOnly(libs.rikka.hidden.stub)
    implementation(libs.rikka.parcelablelist)
    implementation(libs.gson)
    implementation(libs.hiddenapibypass)
    // AdbPairing protocol deps (boringssl for SPAKE2, bouncycastle for AdbKey x509, conscrypt for TLS)
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.conscrypt.android)
    // Native deps for libaimbot_svc.so + libaimbot_svc_pair.so
    implementation(libs.boringssl)
    implementation(libs.lsposed.libcxx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}