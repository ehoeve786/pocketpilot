plugins {
    alias(libs.plugins.pocketpilot.android.library)
}

android {
    namespace = "app.pocketpilot.network.ssh"
}

dependencies {
    api(projects.network.api)
    implementation(projects.network.gonet)
    implementation(libs.kotlinx.coroutines.core)
}
