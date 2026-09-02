plugins {
    id("com.android.application")
}

android {
    namespace = "com.afusekt.lsp"
    compileSdk = 34
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.zoevip.lsp"
        minSdk = 26
        targetSdk = 34
        versionCode = 245
        versionName = "3.9.93"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cFlags += listOf("-Wall", "-O2")
            }
        }
    }

    // Native lib built manually (gradle's CMake+ninja pipe chain fails in this
    // environment). libzoevippatch.so ships via jniLibs; rebuild it with:
    //   clang --target=aarch64-linux-android26 --sysroot=<ndk>/.../sysroot
    //         -O2 -fPIC -shared -L<sysroot>/usr/lib/aarch64-linux-android/26
    //         -o app/src/main/jniLibs/arm64-v8a/libzoevippatch.so
    //         app/src/main/cpp/zoevippatch.c -llog
    // externalNativeBuild {
    //     cmake {
    //         path = file("src/main/cpp/CMakeLists.txt")
    //     }
    // }

    signingConfigs {
        create("release") {
            val keystore = file(System.getProperty("user.home") + "/.android/debug.keystore")
            if (keystore.exists()) {
                storeFile = keystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val releaseSigning = signingConfigs.findByName("release")
            if (releaseSigning?.storeFile?.exists() == true) {
                signingConfig = releaseSigning
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        disable += "BlockedPrivateApi"
    }

    lint {
        disable += "BlockedPrivateApi"
    }

    lint {
        disable += "BlockedPrivateApi"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    compileOnly("de.robv.android.xposed:api:82:sources")
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("androidx.annotation:annotation:1.8.2")
}

tasks.register<Copy>("copyReleaseApk") {
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(layout.projectDirectory.dir("../output"))
    rename { "zoevip-lsp.apk" }
}

afterEvaluate {
    tasks.named("assembleRelease") {
        finalizedBy("copyReleaseApk")
    }
}
