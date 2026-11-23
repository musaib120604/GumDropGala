plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val versionCodeEnv = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
val versionNameEnv = System.getenv("VERSION_NAME") ?: "1.0.0"

android {
    namespace = "com.gumdropgala"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gumdropgala.app"
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodeEnv
        versionName = versionNameEnv
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
    sourceSets["main"].java.srcDir("src/main/kotlin")
}

dependencies {
    implementation(project(":engine"))
}
