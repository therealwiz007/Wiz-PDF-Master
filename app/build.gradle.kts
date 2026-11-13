plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.wizlabs.pdfmaster"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wizlabs.pdfmaster"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.1.0"
        // Ship only English resources (drops dozens of unused library translations).
        resourceConfigurations += listOf("en")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // x86_64 so the app also runs on the emulator.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        }
        release {
            // Real phones only; Play delivers just the one each device needs from the AAB.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val ks = System.getenv("KEYSTORE_FILE")
            signingConfig = if (!ks.isNullOrBlank()) {
                signingConfigs.create("release") {
                    storeFile = file(ks)
                    storePassword = System.getenv("KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("KEY_ALIAS")
                    keyPassword = System.getenv("KEY_PASSWORD")
                }
            } else {
                // Local test builds only. CI refuses to build without the real keystore secrets.
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                // Post-quantum crypto tables from BouncyCastle; PDFBox never uses them.
                "org/bouncycastle/pqc/**", "org/bouncycastle/x509/**"
            )
        }
        // Compress native libraries: much smaller download for directly shared APKs.
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    // Installs bundled baseline profiles on sideloaded installs too: faster start, smoother first scrolls.
    implementation("androidx.profileinstaller:profileinstaller:1.4.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.fragment:fragment:1.8.6")
    implementation("androidx.exifinterface:exifinterface:1.4.0")

    val camerax = "1.4.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // Edge detection, perspective correction and scan filters, all on-device.
    implementation("org.opencv:opencv:4.10.0")
    // Writing PDFs (with an invisible OCR text layer) and reading text for search.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // Text recognition with the model bundled inside the APK (no download, no Play services model).
    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
