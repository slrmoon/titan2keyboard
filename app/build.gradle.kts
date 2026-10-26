plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.slrmoon.clack"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.slrmoon.clack"
        minSdk = 29
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        // One stable key for every build, so new APKs install over old ones without uninstalling.
        // CI writes it from the CLACK_KEYSTORE secret; locally it falls back to the debug key.
        val ks = file(System.getenv("CLACK_KEYSTORE_FILE") ?: "clack.keystore")
        if (ks.exists()) {
            create("clack") {
                storeFile = ks
                storePassword = System.getenv("CLACK_KEYSTORE_PASSWORD") ?: "clack-keyboard"
                keyAlias = System.getenv("CLACK_KEY_ALIAS") ?: "clack"
                keyPassword = System.getenv("CLACK_KEY_PASSWORD") ?: System.getenv("CLACK_KEYSTORE_PASSWORD") ?: "clack-keyboard"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("clack") ?: signingConfigs.getByName("debug")
        }
        debug {
            signingConfig = signingConfigs.findByName("clack") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    androidResources {
        // dictionaries are already gzip-compressed
        noCompress += "gz"
    }
}

dependencies {
    implementation(project(":core"))
}
