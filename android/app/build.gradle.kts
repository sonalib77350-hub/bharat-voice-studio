plugins {
    id("com.android.application")
}

android {
    namespace = "com.sonali.punaai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sonali.punaai"
        minSdk = 24
        targetSdk = 36
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // One fixed key for every build so each new APK installs over the previous one.
    // Private-use app: the keystore is committed on purpose (android/app/puna-release.p12).
    signingConfigs {
        create("puna") {
            storeFile = file("puna-release.p12")
            storeType = "pkcs12"
            storePassword = "puna-ai-2026"
            keyAlias = "puna"
            keyPassword = "puna-ai-2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("puna")
        }
        debug {
            signingConfig = signingConfigs.getByName("puna")
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
