package app.pocketpilot.core.orchestrator

import app.pocketpilot.core.model.Scope
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SessionFactoryTest {
    @Test
    fun localTokenLeavesOutShellUntilTheOwnerTurnsItOn() {
        var shellOn = false
        val sessions = SessionFactory(localExtraScopes = { if (shellOn) setOf(Scope.SHELL_EXEC) else emptySet() })

        val before = sessions.localTokenSession().grantedScopes
        assertFalse(Scope.SHELL_EXEC in before)
        assertFalse(Scope.TERMUX_RUN in before)

        shellOn = true
        val after = sessions.localTokenSession().grantedScopes
        assertTrue(Scope.SHELL_EXEC in after)
        assertFalse(Scope.TERMUX_RUN in after)
    }

    @Test
    fun remoteClientsGetShellOnlyWhileTheOwnersSwitchIsOn() {
        var remoteShell = false
        val sessions =
            SessionFactory(
                localExtraScopes = { setOf(Scope.SHELL_EXEC) },
                remoteExtraScopes = { if (remoteShell) setOf(Scope.SHELL_EXEC) else emptySet() },
            )
        val granted = setOf(Scope.SCREEN_READ)

        assertFalse(Scope.SHELL_EXEC in sessions.clientSession("Claude", granted).grantedScopes)

        remoteShell = true
        val on = sessions.clientSession("Claude", granted).grantedScopes
        assertTrue(Scope.SHELL_EXEC in on)
        assertTrue(Scope.SCREEN_READ in on)
        assertFalse(Scope.TERMUX_RUN in on)

        remoteShell = false
        assertFalse(Scope.SHELL_EXEC in sessions.clientSession("Claude", granted).grantedScopes)
        assertNotEquals(SessionFactory.LOCAL_PRINCIPAL, sessions.clientSession(SessionFactory.LOCAL_PRINCIPAL, granted).principal)
    }
}
