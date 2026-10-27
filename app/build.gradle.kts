plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

ksp {
    // Commit the generated Room schema JSON (app/schemas/...) so the on-device
    // migration suite (RoomMigrationTest) can validate every upgrade on real SQLite.
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.sangiorgisrl.screenlink"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sangiorgisrl.screenlink"
        minSdk = 26
        targetSdk = 35
        // First public release, so the counter starts at 1: nothing has ever been
        // published with a higher one, so no installed build can look "newer" than
        // this. Bump versionCode on every release; versionName is what the v* tag
        // has to match (release.yml checks it twice, source and built APK).
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // The key for the debug APK is checked in, on purpose. AGP's own debug key is
        // generated fresh per machine - and per CI runner - so two builds of the same app
        // carry different signatures: the next release then refuses to install over the
        // one already on the phone (INSTALL_FAILED_UPDATE_INCOMPATIBLE), and uninstalling
        // throws away the pinned host keys, the saved connections and the
        // Keystore-encrypted SSH keys. One constant key removes that trap.
        //
        // It is a throwaway sideload key, public by design: it proves nothing about who
        // built an APK, it signs nothing else, and it must never be the key behind a Play
        // upload. For a private one, override without touching this file:
        //   -Pscreenlink.sideloadKeystore=/path/to/ks -Pscreenlink.sideloadPassword=...
        create("sideload") {
            // Root-relative: the keystore lives beside the VCS metadata, not inside :app.
            storeFile = rootProject.file(
                providers.gradleProperty("screenlink.sideloadKeystore")
                    .getOrElse("keystore/sideload.p12")
            )
            storePassword = providers.gradleProperty("screenlink.sideloadPassword")
                .getOrElse("screenlink")
            keyAlias = "screenlink"
            keyPassword = storePassword
        }
    }

    buildTypes {
        debug {
            // Not AGP's per-machine debug key - see signingConfigs above.
            signingConfig = signingConfigs.getByName("sideload")
        }
        release {
            // R8 runs on release: dead code (including anything the vendored terminal
            // code no longer reaches) is stripped, and the shipped dex is smaller.
            // The reflective registries that R8 cannot see - JSch's algorithm tables
            // and BouncyCastle's JCA provider - are kept in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The app has its own in-app language picker, so every locale must already be
    // present at install time: with language splits Play would strip values-es from
    // the APK and switching to Spanish in-app would silently fall back to English.
    // (This is the alternative to downloading locales through the Play Core library.)
    bundle {
        language {
            enableSplit = false
        }
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        // MigrationTestHelper reads the committed schema JSON from the androidTest
        // assets (alongside the default src/androidTest/assets).
        getByName("androidTest") {
            assets.srcDirs("$projectDir/schemas")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all { it.useJUnitPlatform() }
        }
    }

    lint {
        // These four checks are "a newer version exists" advisories; the SDK and
        // dependency versions are a deliberate, separately-controlled choice (see
        // README "Tech"), not lint defects. Every other warning/error fails the build.
        disable += setOf(
            "GradleDependency",
            "NewerVersionAvailable",
            "AndroidGradlePluginVersion",
            "OldTargetApi",
        )
        abortOnError = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    // Declared because the app uses it directly (compat drawable loading in the
    // terminal's text-selection handles). It also arrives transitively through
    // ML Kit's `common`, so it is in the APK either way.
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.navigation:navigation-compose:2.9.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3-window-size-class")

    // Room
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // SSH (pure-Java SSH client, reused from the mwiede/jsch fork of JSch)
    implementation("com.github.mwiede:jsch:0.2.26")
    // Portable crypto provider so SSH ciphers work reliably on stock Android runtimes.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // Dictation: ML Kit's GenAI Speech Recognition. Advanced mode runs the on-device
    // Gemini Nano model through Android's AICore service (Pixel 10/11 today); Basic mode
    // is the traditional on-device model. Alpha software: the framework recognizer stays
    // in the chain as the fallback (see speech/ChainedSpeechEngine.kt).
    implementation("com.google.mlkit:genai-speech-recognition:1.0.0-alpha1")

    implementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // --- Unit tests (JVM): JUnit 5 (Jupiter) ONLY - EPL-free classpath ---------
    // The unit-test classpath carries NO junit:junit (EPL-1.0) artifact at all.
    // Robolectric (JUnit 4 based) and the Compose JUnit4 rule were removed; their
    // coverage lives either on-device (instrumented suite) or as pure-JVM logic tests.
    // The instrumented (androidTest) suite still uses AndroidJUnit4 - that is the
    // Android platform runner, a dev-only tool, never shipped.
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // --- Instrumented tests (need a device/emulator + a reachable SSH server) ---
    androidTestImplementation(platform(composeBom))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    // MigrationTestHelper + FrameworkSQLiteOpenHelperFactory for the Room migration suite.
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("androidx.sqlite:sqlite-framework:2.4.0")
}
