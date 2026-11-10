plugins {
    alias(libs.plugins.pocketpilot.android.library)
}

android {
    namespace = "app.pocketpilot.network.wireguard"
}

dependencies {
    api(projects.network.api)
    implementation(projects.network.certificates)
    // WireGuardProvider calls the gomobile bindings itself, not only through certificates.
    implementation(projects.network.gonet)
    implementation(libs.kotlinx.coroutines.core)
}
