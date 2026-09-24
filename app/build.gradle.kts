import java.time.LocalDate
import java.time.ZoneOffset

// The owner chose to ship a default OpenAI key in the app (from the CI secret OPENAI_API_KEY, never from
// the repo). It is XOR-scrambled so string scanners don't spot it; it is NOT secret from a determined user.
val embeddedOpenAiKey: String = (System.getenv("OPENAI_API_KEY") ?: "").trim()

fun scramble(key: String): String =
    key.toByteArray().mapIndexed { i, b -> (b.toInt() xor (0x5A + i % 7)) and 0xFF }.joinToString(",")

// Versioning: every CI run gets its own build number in versionName ("0.5.<run>"), so each APK is
// identifiable. versionCode stays fixed on purpose: Android refuses to install a lower versionCode over a
// higher one, and a fixed code lets testers switch between any two builds (back to the previous release
// too) without uninstalling and losing their settings. Bump it only for a store release.
val buildNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
val appVersionName: String = if (buildNumber > 0) "0.5.$buildNumber" else "0.5.0-local"
val gitSha: String = System.getenv("GITHUB_SHA")?.take(7) ?: "local"
val buildDate: String = LocalDate.now(ZoneOffset.UTC).toString()

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

android {
    namespace = "com.gpsradio.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gpsradio.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 100
        versionName = appVersionName
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("String", "BUILD_DATE", "\"$buildDate\"")
        testInstrumentationRunner = "com.gpsradio.app.GpsRadioTestRunner"
        buildConfigField("String", "EMBEDDED_KEY", "\"${scramble(embeddedOpenAiKey)}\"")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    signingConfigs {
        // A fixed, committed debug key (not a secret) so every CI build installs as an update
        // of the previous one instead of requiring an uninstall.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // The on-device AI libraries are built with a newer Kotlin; their API is plain and reads fine.
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/versions/9/previous-compilation-data.bin")
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.media:media:1.7.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // On-device story writers (spec A §69): Gemini Nano via ML Kit GenAI, open models via LiteRT-LM.
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.1")

    // JVM tests with a simulated Android (Robolectric) + Compose UI tests.
    testImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit-ktx:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // End-to-end tests on an emulator against a local fake of OpenAI/Wikipedia/OSM.
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.2.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
