import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// ─── Stealth(KPM 无痕触摸)配对盐 ─────────────────────────────────────
// 盐不进 git:真实值写在 local.properties 的 stealth.pairSalt=...(与编译
// inputprobe.kpm 用的是同一个,见 scripts/sync_touch_pairing.sh),或经
// -Pstealth.pairSalt=... 传入。没配时 CMake 侧会 FATAL_ERROR 拦下 —— 占位
// 盐编出的 daemon 永远握不上 KPM,与其运行期难排查不如构建期报错。
// CI 等无条件配盐的场合用 -Pstealth.allowPlaceholder=true 显式豁免。
fun stealthPairSalt(): String? {
    (project.findProperty("stealth.pairSalt") as String?)?.let { return it }
    val localProps = Properties()
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { localProps.load(it) }
    return localProps.getProperty("stealth.pairSalt")
}
val stealthAllowPlaceholder = project.hasProperty("stealth.allowPlaceholder")

android {
    namespace = "io.github.love3025.yolovaim"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.love3025.yolovaim"
        minSdk = 31
        targetSdk = 35
        versionCode = 22
        versionName = "1.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += ""
                arguments += listOf("-DSTEALTH_ALLOW_PLACEHOLDER=${if (stealthAllowPlaceholder) "1" else "0"}") +
                    (stealthPairSalt()?.let { listOf("-DTOUCH_PAIR_SALT=$it") } ?: emptyList())
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
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}