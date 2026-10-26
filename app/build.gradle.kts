plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.aflasher.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.aflasher.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }

    // Release signing is read from environment variables so the keystore
    // itself never has to be committed to the repo. Set these as GitHub
    // Actions secrets (RELEASE_KEYSTORE_BASE64, RELEASE_KEYSTORE_PASSWORD,
    // RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD) and the workflow decodes the
    // keystore into app/release.keystore before the build step.
    val ksFile = file("release.keystore")
    val hasReleaseSigning = ksFile.exists() &&
        System.getenv("RELEASE_KEYSTORE_PASSWORD") != null &&
        System.getenv("RELEASE_KEY_ALIAS") != null &&
        System.getenv("RELEASE_KEY_PASSWORD") != null

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = ksFile
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }
}
