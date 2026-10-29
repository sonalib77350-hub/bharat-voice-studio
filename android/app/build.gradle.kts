plugins {
    id("com.android.application")
}

android {
    namespace = "com.sonali.bharatvoicestudio"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sonali.bharatvoicestudio"
        minSdk = 24
        targetSdk = 36
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // One fixed key for every build, so a new APK installs over the previous one as an update.
    // Private-use app: the keystore is committed on purpose (android/app/release.p12).
    signingConfigs {
        create("bvs") {
            storeFile = file("release.p12")
            storeType = "pkcs12"
            storePassword = "bvs-release-2026"
            keyAlias = "bvs"
            keyPassword = "bvs-release-2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("bvs")
        }
        debug {
            signingConfig = signingConfigs.getByName("bvs")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
