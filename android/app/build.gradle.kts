plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val updateManifestUrl = providers.gradleProperty("bitchatUpdateManifestUrl").orNull.orEmpty()
val updateSigningPublicKey = providers.gradleProperty("bitchatUpdateSigningPublicKey").orNull.orEmpty()

android {
    namespace = "com.bitchat.droid"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.bitchat.droid"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "UPDATE_MANIFEST_URL", buildConfigString(updateManifestUrl))
        buildConfigField("String", "UPDATE_SIGNING_PUBLIC_KEY", buildConfigString(updateSigningPublicKey))
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "BLE_SERVICE_UUID", "\"F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5A\"")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("String", "BLE_SERVICE_UUID", "\"F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C\"")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")

    testImplementation("junit:junit:4.13.2")
}
