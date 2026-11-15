plugins {
    alias(libs.plugins.pocketpilot.android.library)
}

android {
    namespace = "app.pocketpilot.capability.shizuku"
    ndkVersion = libs.versions.ndk.get()
    buildFeatures {
        aidl = true
        // BoringSSL and libc++ for the adb pairing code come as prefab packages.
        prefab = true
    }
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        // The phones are arm64; x86_64 is for the emulator. The same pair the Go library is built for.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=none"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }
}

dependencies {
    api(projects.capability.api)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.boringssl.prefab)
    implementation(libs.libcxx.prefab)
    implementation(libs.hiddenapibypass)
}
