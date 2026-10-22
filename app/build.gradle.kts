plugins { id("com.android.application") }

android {
    namespace = "com.gather.quotapocket"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.gather.quotapocket"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
