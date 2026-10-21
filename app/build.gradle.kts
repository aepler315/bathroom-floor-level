plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aepler.floorlevel"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aepler.floorlevel"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
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
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // org.json ships with Android; the JVM unit tests need a real copy.
    testImplementation("org.json:json:20240303")
    // Renders the real screens to PNGs (app/build/screens) without a device.
    testImplementation("org.robolectric:robolectric:4.14.1")
}
