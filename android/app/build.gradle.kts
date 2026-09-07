plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.spotless)
}

android {
    namespace = "com.wlav.stlauncher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wlav.stlauncher"
        minSdk = 26
        targetSdk = 36
        versionCode = 31
        versionName = "0.27.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // AGP generates a debug keystore per machine under ~/.android, so a second
    // machine — or a wiped home directory — produces a different signature and
    // every reinstall fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE, taking the
    // installed user data with it. Pointing this at a keystore that is kept
    // outside the SDK directory makes the debug signature stable.
    signingConfigs {
        getByName("debug") {
            val overridePath =
                providers
                    .gradleProperty("stLauncherDebugKeystore")
                    .orElse(providers.environmentVariable("ST_LAUNCHER_DEBUG_KEYSTORE"))
                    .orNull
                    // An empty value means "not configured": CI and shell
                    // expansion both produce blanks for unset variables.
                    ?.takeIf(String::isNotBlank)
            if (overridePath != null) {
                val overrideFile = file(overridePath)
                // Failing loudly beats silently falling back to the default key:
                // a silent fallback is what produces the signature conflict.
                check(overrideFile.isFile) {
                    "Debug keystore not found at $overridePath. Fix stLauncherDebugKeystore " +
                        "or ST_LAUNCHER_DEBUG_KEYSTORE, or unset it to use the default debug key."
                }
                storeFile = overrideFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
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

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    packaging {
        jniLibs {
            // The Node PIE is invoked through Android's linker and must be
            // extracted with executable permissions on installation.
            useLegacyPackaging = true
        }
        resources {
            excludes +=
                setOf(
                    "META-INF/DEPENDENCIES",
                    "META-INF/LICENSE*",
                    "META-INF/NOTICE*",
                )
        }
    }

    sourceSets {
        getByName("main") {
            assets.directories.add("../../runtime/android-assets")
            jniLibs.directories.add("../../runtime/generated/jniLibs")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":runtime-core"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.material)
    implementation(libs.okhttp)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

val requiredRuntimeAssets =
    listOf(
        layout.projectDirectory.file("../../runtime/android-assets/runtime.bundle"),
        layout.projectDirectory.file("../../runtime/android-assets/runtime-manifest.json"),
        layout.projectDirectory.file("../../runtime/generated/jniLibs/arm64-v8a/libstnode.so"),
    )

val verifyRuntimeAssets by tasks.registering {
    group = "verification"
    description = "Checks generated Node and SillyTavern runtime assets."
    inputs.files(requiredRuntimeAssets).withPropertyName("runtimeAssets")

    doLast {
        val missing = inputs.files.files.filterNot(File::isFile)
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Missing generated runtime assets: ${missing.joinToString { it.path }}. " +
                    "Run `python3 tools/build_runtime.py` from the repository root.",
            )
        }
    }
}

tasks
    .matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(verifyRuntimeAssets) }

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint("1.7.1")
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint("1.7.1")
    }
}
