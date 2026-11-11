plugins {
    alias(libs.plugins.pocketpilot.android.application.compose)
    alias(libs.plugins.pocketpilot.hilt)
}

// The version follows the commit count, so each build installs over the one before it. Git and the repository's history
// must be there: a source archive without them fails here. CI sets POCKETPILOT_VERSION_CODE for a pull request's build to
// the count its squash merge will have (the base's count plus one). Counting the pull request's own commits would put
// its debug APK above the next builds from main, and Android refuses to install a lower version over a higher one.
val commitCount =
    providers
        .environmentVariable("POCKETPILOT_VERSION_CODE")
        .map { it.trim().toInt() }
        .orElse(
            providers
                .exec { commandLine("git", "rev-list", "--count", "HEAD") }
                .standardOutput.asText
                .map { it.trim().toIntOrNull() ?: 1 },
        )

android {
    namespace = "app.pocketpilot"

    defaultConfig {
        // The ".oss" ending was the oss flavor's suffix. It stays now that oss is the only build, so installed apps update.
        applicationId = "app.pocketpilot.oss"
        versionCode = commitCount.get()
        versionName = "0.1.${commitCount.get()}"
    }

    buildFeatures {
        buildConfig = true
    }

    // Every CI runner would otherwise make its own debug key, and Android refuses to update an app
    // signed with a different key. This key is public and signs debug builds only.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    // Release builds are signed with a key kept outside the repository: its file and passwords come from the environment.
    // Without them a release build stays unsigned.
    val releaseStore = System.getenv("POCKETPILOT_RELEASE_STORE_FILE")
    if (releaseStore != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = System.getenv("POCKETPILOT_RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("POCKETPILOT_RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("POCKETPILOT_RELEASE_KEY_PASSWORD")
            }
        }
        buildTypes {
            getByName("release") {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    packaging {
        resources {
            // Duplicate metadata from the Ktor and MCP SDK jars.
            excludes +=
                setOf(
                    "/META-INF/{AL2.0,LGPL2.1}",
                    "/META-INF/INDEX.LIST",
                    "/META-INF/io.netty.versions.properties",
                    "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                    "/META-INF/DEPENDENCIES",
                )
        }
    }
}

dependencies {
    implementation(projects.agent.modelRegistry)
    implementation(projects.agent.providers.anthropic)
    implementation(projects.agent.providers.gemini)
    implementation(projects.agent.providers.openai)
    implementation(projects.agent.providers.openaiCompatible)
    implementation(projects.agent.runtime)
    implementation(projects.capability.accessibility)
    implementation(projects.capability.api)
    implementation(projects.capability.apps)
    implementation(projects.capability.device)
    implementation(projects.capability.files)
    implementation(projects.capability.notifications)
    implementation(projects.capability.screencapture)
    implementation(projects.capability.shizuku)
    implementation(projects.core.audit)
    implementation(projects.core.automation)
    implementation(projects.core.capabilities)
    implementation(projects.core.common)
    implementation(projects.core.designsystem)
    implementation(projects.core.imaging)
    implementation(projects.core.model)
    implementation(projects.core.orchestrator)
    implementation(projects.core.policy)
    implementation(projects.core.tools)
    implementation(projects.feature.agent)
    implementation(projects.feature.approvals)
    implementation(projects.feature.automations)
    implementation(projects.feature.clients)
    implementation(projects.feature.network)
    implementation(projects.feature.onboarding)
    implementation(projects.network.api)
    implementation(projects.network.certificates)
    implementation(projects.network.tailscale)
    implementation(projects.network.wireguard)
    implementation(projects.server.http)
    implementation(projects.server.mcp)
    implementation(projects.server.oauth)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}
