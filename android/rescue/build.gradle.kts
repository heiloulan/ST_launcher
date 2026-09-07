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
        versionCode = 27
        versionName = "0.26-rescue-test1"
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
