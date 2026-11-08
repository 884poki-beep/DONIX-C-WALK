plugins { id("com.android.application") }
android {
    namespace = "jp.dongurikobo.donixcwalk"
    compileSdk = 35
    defaultConfig {
        applicationId = "jp.dongurikobo.donixcwalk"
        minSdk = 26
        targetSdk = 28
        versionCode = 1
        versionName = "1.0.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
