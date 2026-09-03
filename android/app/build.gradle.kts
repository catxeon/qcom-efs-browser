plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.qcom.efs"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.qcom.efs"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.1.0"
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

    buildFeatures { compose = true }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
}

/**
 * Builds daemon/ with the NDK when ANDROID_NDK_HOME is set and the asset is
 * missing or older than the sources.  CI builds it explicitly beforehand.
 */
val buildDaemon by tasks.registering(Exec::class) {
    val daemonDir = rootProject.file("../daemon")
    val asset = file("src/main/assets/qcom-efsd")
    workingDir = daemonDir
    commandLine("bash", "build.sh")
    onlyIf {
        val ndk = System.getenv("ANDROID_NDK_HOME") ?: System.getenv("ANDROID_NDK_ROOT")
        val sources = daemonDir.resolve("src").listFiles()?.toList().orEmpty()
        val stale = !asset.exists() || sources.any { it.lastModified() > asset.lastModified() }
        if (stale && ndk == null) {
            logger.warn("qcom-efsd asset is missing or stale and ANDROID_NDK_HOME is unset - " +
                    "build it with daemon/build.sh before packaging.")
        }
        ndk != null && stale
    }
}

tasks.named("preBuild") { dependsOn(buildDaemon) }
