plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "org.blender.blender"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.blender.blender"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "5.3-android"

        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    androidResources {
        noCompress += "zip"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isDebuggable = true
        }
    }
}
