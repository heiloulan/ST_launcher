plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.wlav.stlauncher.rescue"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wlav.stlauncher"
        minSdk = 26
        targetSdk = 36
        // Match :app so the rescue and normal APKs can replace each other.
        versionCode = 31
        versionName = "0.27.3-rescue"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.activity)
    implementation(libs.commons.compress)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
