import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Load signing credentials from a gitignored keystore.properties in the project root.
// See keystore.properties.template for the expected keys. When the file is
// absent (e.g. on a fresh clone) the release signingConfig is left unset and
// `./gradlew :app:assembleRelease` will produce an unsigned APK/AAB.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) load(FileInputStream(f))
}

android {
    namespace = "com.readabhishek.phonescanner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.readabhishek.phonescanner"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            // Populate only when keystore.properties exists. See the template
            // file at the project root for the required keys. Never commit the
            // keystore .jks file or keystore.properties.
            val storePath = keystoreProps["storeFile"] as String?
            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
                storePassword = keystoreProps["storePassword"] as String?
                keyAlias = keystoreProps["keyAlias"] as String?
                keyPassword = keystoreProps["keyPassword"] as String?
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Apply the release signing config only if credentials were loaded.
            if (!(keystoreProps["storeFile"] as String?).isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core Android / Kotlin
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Google ML Kit Document Scanner (handles edge detection, perspective,
    // multi-page capture, and can emit a ready-made PDF).
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")

    // Google ML Kit Text Recognition (on-device OCR, Latin script).
    // Used to pull readable text out of the scanned page images.
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")

    // Testing (optional but included for completeness)
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
