plugins {
    id("com.android.application")
}

android {
    namespace = "app.lightcraft.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.lightcraft.android"
        // MediaStore export (no storage permission) needs Android 10+
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
}
