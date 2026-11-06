plugins {
    alias(libs.plugins.pocketpilot.jvm.library)
}

dependencies {
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
}
