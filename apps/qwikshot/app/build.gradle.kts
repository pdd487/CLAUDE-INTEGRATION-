import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from keystore.properties (see keystore.properties.example).
// When it is absent, release builds fall back to the debug key so that
// `./gradlew assembleRelease` still produces an APK you can install.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
val hasReleaseKeystore = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { !keystoreProperties.getProperty(it).isNullOrBlank() }

android {
    namespace = "com.qwikshot.app"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
        targetSdk = 35
    }

    // One codebase, two installable apps. Both share the floating-bubble engine;
    // the flavor decides what a tap does. Flavor resources in src/<flavor>/res
    // override names, colors, and icons so the two bubbles look different on screen.
    flavorDimensions += "bubble"
    productFlavors {
        create("shot") {
            dimension = "bubble"
            applicationId = "com.qwikshot.app"
            versionCode = 2
            versionName = "1.0.1"
            // Tap = hide bubble, fire the system screenshot, bring bubble back.
            buildConfigField("boolean", "DRIVE_SCAN_MODE", "false")
            buildConfigField("float", "DEFAULT_Y_FRACTION", "0.35f")
        }
        create("scan") {
            dimension = "bubble"
            applicationId = "com.qwikscan.app"
            versionCode = 1
            versionName = "1.0.0"
            // Tap = open Google Drive's document scanner.
            buildConfigField("boolean", "DRIVE_SCAN_MODE", "true")
            // Docks lower than QwiKShot so both bubbles can sit on the same edge.
            buildConfigField("float", "DEFAULT_Y_FRACTION", "0.55f")
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
}
