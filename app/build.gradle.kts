plugins {
    id("com.android.application")
}

android {
    namespace = "jp.dongurikobo.donixcwalk"
    compileSdk = 35

    defaultConfig {
        applicationId = "jp.dongurikobo.donixcwalk"
        minSdk = 26
        targetSdk = 35
        versionCode = 25
        versionName = "1.0.26"
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_FILE")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
            }
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
