import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
}

// The Google Maps API key is per-developer/per-machine, so it lives in
// local.properties (already gitignored by the Android Gradle Plugin
// template) rather than in source control. Get one at
// https://console.cloud.google.com/google/maps-apis/credentials for the
// "loramenuma" Firebase/GCP project, restrict it to the Maps SDK for
// Android, and add MAPS_API_KEY=... to android/local.properties.
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}
val mapsApiKey: String = localProperties.getProperty("MAPS_API_KEY") ?: ""

// The release signing keystore is per-developer/per-machine too, and must never be committed —
// losing it means losing the ability to publish updates to this app. Its path and passwords live
// in local.properties (gitignored); the keystore file itself should live outside the repo
// entirely (e.g. ~/keystores/loraviewer-release.jks). If these properties aren't set, release
// builds are left unsigned rather than failing, so assembleRelease/lint keep working without a
// keystore present (e.g. in CI) — only Play Console upload actually requires a signed bundle.
val releaseStoreFile: String? = localProperties.getProperty("RELEASE_STORE_FILE")
val releaseStorePassword: String? = localProperties.getProperty("RELEASE_STORE_PASSWORD")
val releaseKeyAlias: String? = localProperties.getProperty("RELEASE_KEY_ALIAS")
val releaseKeyPassword: String? = localProperties.getProperty("RELEASE_KEY_PASSWORD")
val hasReleaseSigningConfig =
    !releaseStoreFile.isNullOrBlank() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.cobaltloom.loraviewer"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.cobaltloom.loraviewer"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.maps.compose)
    implementation(libs.play.services.maps)
    implementation(libs.play.services.location)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.mlkit.text.recognition.japanese)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.billing.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
