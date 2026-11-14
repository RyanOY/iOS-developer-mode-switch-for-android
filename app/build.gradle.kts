plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ryanoy.iosdevmode"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ryanoy.iosdevmode"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            // go-ios is cross compiled for arm64-v8a only; keep the APK small.
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            // libgoios.so is a statically linked Go binary, not a shared library.
            // It must be extracted to nativeLibraryDir so the app can exec() it.
            useLegacyPackaging = true
            // Never let the Android strip task touch the Go executable.
            keepDebugSymbols += "**/libgoios.so"
        }
        resources {
            excludes += arrayOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

dependencies {
    // Property list (XML / binary) codec used by the usbmuxd bridge.
    implementation("com.googlecode.plist:dd-plist:1.27")
}
