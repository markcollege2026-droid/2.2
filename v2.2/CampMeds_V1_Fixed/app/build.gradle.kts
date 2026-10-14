plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.campmeds.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.campmeds.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.3-prototype"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    // Lets the instrumented migration test read the exported Room schema JSON files (app/schemas).
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Export the Room schema JSON so migrations (V1 -> V2 ...) can be written and tested against a real baseline.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// The migration test ships app/schemas as androidTest assets, and KSP writes the current schema into that
// directory while compiling the app. Make that ordering explicit so the packaged assets always contain the
// generated schema JSON, whichever task the build happens to schedule first.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("AndroidTestAssets") }.configureEach {
    dependsOn("kspDebugKotlin")
}

dependencies {
    // Core / Compose
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // BUG FIX (V1 bug 2): Icons.Default.Logout / Upload are NOT in material3 or icons-core; they live in
    // icons-extended (which also contains everything in icons-core, so core is not listed separately).
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Room + SQLCipher (encryption at rest)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")
    implementation("androidx.sqlite:sqlite:2.4.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06") // Keystore-backed key wrapping

    // CameraX + ML Kit barcode scanning
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    // On-device OCR. The Latin model is bundled in the APK (no runtime download, works offline).
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Printing a QR label bitmap through the system print dialog
    implementation("androidx.print:print:1.0.0")

    // QR code generation (for printing/attaching to bottles)
    implementation("com.google.zxing:core:3.5.3")

    // Networking (openFDA NDC lookup — the one online call in the app)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // XLSX export
    implementation("org.apache.poi:poi:5.2.5")
    implementation("org.apache.poi:poi-ooxml:5.2.5")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub in plain JVM unit tests; the real implementation is needed for NdcApiClient tests.
    testImplementation("org.json:json:20231013")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // One of these tests is run on a device for every migration; the runner is what makes them runnable.
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
}
