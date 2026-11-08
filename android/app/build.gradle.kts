plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.lzpresence.skyline"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lzpresence.skyline"
        minSdk = 26
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    // One fixed key so each new build installs over the last one.
    // The CI workflow creates skyline.p12 on its first run and commits it.
    signingConfigs {
        create("shared") {
            storeFile = file("skyline.p12")
            storePassword = "skyline"
            keyAlias = "skyline"
            keyPassword = "skyline"
            storeType = "pkcs12"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.caverock:androidsvg-aar:1.4")
}
