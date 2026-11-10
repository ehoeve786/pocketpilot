package app.pocketpilot.core.model

/**
 * What a build distribution is allowed to ship. The app has no product flavors any more and always binds [OSS];
 * [PRO] and [PLAY] remain only so the profile-dependent code paths can still be tested.
 */
data class PolicyProfile(
    val flavor: Flavor,
    /** `shell.exec` is offered; false only in the old [PLAY] profile. */
    val shellExecAvailable: Boolean,
    /** False only in the old [PLAY] profile, which used targeted `<queries>` instead of QUERY_ALL_PACKAGES. */
    val queryAllPackages: Boolean,
    val proFeatures: Boolean,
) {
    companion object {
        val OSS = PolicyProfile(Flavor.OSS, shellExecAvailable = true, queryAllPackages = true, proFeatures = false)
        val PRO = PolicyProfile(Flavor.PRO, shellExecAvailable = true, queryAllPackages = true, proFeatures = true)
        val PLAY = PolicyProfile(Flavor.PLAY, shellExecAvailable = false, queryAllPackages = false, proFeatures = true)
    }
}
