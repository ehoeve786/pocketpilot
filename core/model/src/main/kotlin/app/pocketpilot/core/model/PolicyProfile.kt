package app.pocketpilot.core.model

/**
 * What a build distribution is allowed to ship. The app has no product flavors any more and always binds [OSS];
 * [PRO] and [PLAY] remain only so the profile-dependent code paths can still be tested.
 */
data class PolicyProfile(
    val flavor: Flavor,
    /** `shell.exec` is offered; false only in the old [PLAY] profile. */
    val shellExecAvailable: Boolean,
) {
    companion object {
        val OSS = PolicyProfile(Flavor.OSS, shellExecAvailable = true)
        val PRO = PolicyProfile(Flavor.PRO, shellExecAvailable = true)
        val PLAY = PolicyProfile(Flavor.PLAY, shellExecAvailable = false)
    }
}
