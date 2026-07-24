plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wlav.stlauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wlav.stlauncher"
        minSdk = 26
        targetSdk = 35
        versionCode = 22
        versionName = "0.22"
    }

    buildTypes {
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

    // Don't compress node binary and .so files in assets
    androidResources {
        noCompress += listOf("so", "node", "tar", "gz")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.12.1")
}

val requiredRuntimeAssets = listOf(
    "sillytavern.tar",
    "git_bundle.tar",
    "node_bundle/node",
    "node_bundle/ffmpeg",
    "node_bundle/libc++_shared.so",
    "node_bundle/libcrypto.so.3",
    "node_bundle/libssl.so.3",
)

val verifyRuntimeAssets by tasks.registering {
    group = "verification"
    description = "Checks generated Node and SillyTavern runtime assets."

    doLast {
        val assetsDirectory = project.file("src/main/assets")
        val missing = requiredRuntimeAssets.filterNot { assetsDirectory.resolve(it).isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Missing generated runtime assets: ${missing.joinToString()}. " +
                    "Run `python3 tools/prepare_assets.py` from the repository root " +
                    "(or `python3 ../tools/prepare_assets.py` from android/).",
            )
        }
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(verifyRuntimeAssets) }
