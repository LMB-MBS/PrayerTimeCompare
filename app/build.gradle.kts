plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.abang.prayerzoneslite"
    compileSdk {
        version = release(36)
    }

    buildFeatures {
        viewBinding = true
    }

    defaultConfig {
        applicationId = "com.abang.prayerzoneslite"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "2.00"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }


    buildTypes {
        getByName("debug") {
            isDebuggable = true
            manifestPlaceholders["android:testOnly"] = false
            enableAndroidTestCoverage = false
            enableUnitTestCoverage = false
        }
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }



    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this
            if (output is com.android.build.gradle.internal.api.ApkVariantOutputImpl) {
                // Example: PrayerTimeCompare-1.0-release.apk
                output.outputFileName = "${rootProject.name}-${variant.versionName}-${variant.buildType.name}.apk"
            }
        }
    }

}



dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// Custom task to push/copy APK to phone
tasks.register("pushApkToPhone") {
    dependsOn("assembleDebug")

    doLast {
        // Adjusted to your personalized APK name
        val apkPath = file("$projectDir/build/outputs/apk/debug/prayerzoneslite-1.25-debug.apk")
        val adbPath = "D:/Android/Sdk/platform-tools/adb.exe"

        println("📦 Built APK: ${apkPath.absolutePath}")
        if (apkPath.exists()) {
            val result = project.exec {
                commandLine(adbPath, "push", apkPath.absolutePath, "/storage/emulated/0/apk/${apkPath.name}")
                isIgnoreExitValue = true
            }
            println("adb exit code: ${result.exitValue}")
        } else {
            println("⚠️ APK not found at ${apkPath.absolutePath}")
        }
    }
}
// Run automatically after build
tasks.named("build").configure {
    finalizedBy("pushApkToPhone")
}



