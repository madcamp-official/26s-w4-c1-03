plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.gamdo.app"
    compileSdk = 35

    val gamdoApiBaseUrl = providers.gradleProperty("gamdoApiBaseUrl")
        // Physical-device debug builds use the CAMP-2 SSH tunnel forwarded to 18000.
        // Emulator builds should override this with -PgamdoApiBaseUrl=http://10.0.2.2:8000/api/v1/
        .orElse("http://127.0.0.1:18000/api/v1/")
        .get()
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

    defaultConfig {
        applicationId = "com.gamdo.app"
        // 29, not 26: below Q the MediaStore gallery export needs a runtime
        // WRITE_EXTERNAL_STORAGE grant that the permission flow never asked for, so
        // §1-5's "찍은 사진이 갤러리에서 보인다" failed silently on API 26~28 —
        // `runCatching` swallowed the SecurityException and only the local copy survived.
        // Raising the floor to scoped storage deletes that failure mode instead of
        // branching around it. Owner decision 2026-07-26 (reduced device coverage accepted).
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "GAMDO_API_BASE_URL", "\"$gamdoApiBaseUrl\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.all {
            // The value-dump harness prints the numbers produced from the real
            // assets; without this Gradle swallows test stdout.
            it.testLogging { showStandardStreams = true }

            // Forward -Dgamdo.* to the test JVM. Gradle's own -D lands on the
            // daemon, not on the forked test process, so without this the preview
            // harness silently sees null and skips itself — which reads exactly
            // like a passing build.
            System.getProperties().forEach { key, value ->
                val name = key.toString()
                if (name.startsWith("gamdo.")) it.systemProperty(name, value.toString())
            }
            // Same reason: the harness re-renders the same inputs on demand, so
            // up-to-date checks would make a deliberate re-run do nothing.
            it.outputs.upToDateWhen { false }
        }
    }
}

dependencies {
    // Core / lifecycle / activity
    implementation(libs.androidx.core.ktx)
    implementation(libs.bundles.lifecycle)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // CameraX
    implementation(libs.bundles.camerax)

    // ML Kit (face + pose streaming; accurate pose kept out until perf-checked — see Day 1 plan)
    implementation(libs.bundles.mlkit)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Networking + serialization
    implementation(libs.bundles.retrofit)

    // Image loading
    implementation(libs.coil.compose)

    // Permissions
    implementation(libs.accompanist.permissions)

    // DataStore (device UUID persistence — Day 1 §1-3)
    implementation(libs.androidx.datastore.preferences)

    // Unit / instrumentation test
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}
