package app.pocketpilot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pocketpilot.R
import app.pocketpilot.capability.api.BackendIds
import app.pocketpilot.capability.shizuku.ShizukuState
import app.pocketpilot.core.capabilities.CapabilityStatus
import app.pocketpilot.core.model.CapabilityId
import app.pocketpilot.designsystem.LiquidGlassBentoCard
import app.pocketpilot.feature.onboarding.SetupChecklist
import app.pocketpilot.feature.onboarding.SetupStep

/**
 * Doctor v1: which backend serves each capability right now, and what Shizuku needs. A capability
 * lists its backends in priority order, so a check mark on a later one means it is the fallback.
 */
@Composable
internal fun DoctorCard(
    capabilities: List<CapabilityStatus>,
    shizukuState: ShizukuState,
    onGrantShizuku: () -> Unit,
    /** Allows PocketPilot's notification listener, through Shizuku when it is connected. */
    onAllowNotifications: () -> Unit = {},
    /** Every setup step, re-checked with its one-tap fix. */
    setupSteps: List<SetupStep> = emptyList(),
    /** PocketPilot's own privileged process is running; Shizuku is then not needed. */
    builtInRunning: Boolean = false,
) {
    LiquidGlassBentoCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.doctor_title), style = MaterialTheme.typography.titleMedium)
            // With every check passing, one line says so; the details are a tap away.
            val allSet =
                setupSteps.all { it.done || it.optional } &&
                    capabilities.all { it.active != null } &&
                    shizukuState == ShizukuState.CONNECTED
            var details by rememberSaveable { mutableStateOf(false) }
            if (allSet && !details) {
                Text(
                    stringResource(R.string.doctor_all_set),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(onClick = { details = true }) { Text(stringResource(R.string.doctor_show_details)) }
                return@Column
            }
            SetupChecklist(setupSteps)
            capabilities.forEach { status ->
                Column {
                    Text(capabilityLabel(status.capability), style = MaterialTheme.typography.bodyMedium)
                    // map is inline, so the composable label lookup is allowed inside it; joinToString is not.
                    val backends =
                        status.backends
                            .map { backend -> (if (backend.available) "✓ " else "✗ ") + backendLabel(backend.backendId) }
                            .joinToString("  ")
                    // Coloured like the setup steps' "Done", so every passing check reads the same.
                    Text(
                        backends,
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (status.active !=
                                null
                            ) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                    if (status.active == null) {
                        Text(
                            stringResource(R.string.doctor_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (status.active == null && status.capability == CapabilityId.READ_NOTIFICATIONS) {
                        Button(onClick = onAllowNotifications) { Text(stringResource(R.string.doctor_allow_notifications)) }
                    }
                }
            }
            Text(stringResource(R.string.doctor_shizuku_title), style = MaterialTheme.typography.bodyMedium)
            if (builtInRunning) {
                Text(stringResource(R.string.doctor_builtin_running), style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            Text(
                stringResource(
                    when (shizukuState) {
                        ShizukuState.NOT_RUNNING -> R.string.shizuku_not_running
                        ShizukuState.UNSUPPORTED -> R.string.shizuku_unsupported
                        ShizukuState.PERMISSION_NEEDED -> R.string.shizuku_permission_needed
                        ShizukuState.CONNECTING -> R.string.shizuku_connecting
                        ShizukuState.CONNECTED -> R.string.shizuku_connected
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (shizukuState == ShizukuState.PERMISSION_NEEDED) {
                Button(onClick = onGrantShizuku) { Text(stringResource(R.string.shizuku_grant)) }
            }
        }
    }
}

@Composable
private fun capabilityLabel(id: CapabilityId): String =
    stringResource(
        when (id) {
            CapabilityId.READ_UI -> R.string.capability_read_ui
            CapabilityId.INJECT_INPUT -> R.string.capability_inject_input
            CapabilityId.CAPTURE_SCREEN -> R.string.capability_capture_screen
            CapabilityId.LAUNCH_APPS -> R.string.capability_launch_apps
            CapabilityId.WRITE_SETTINGS -> R.string.capability_write_settings
            CapabilityId.RUN_SHELL -> R.string.capability_run_shell
            CapabilityId.RUN_TERMUX -> R.string.capability_run_termux
            CapabilityId.READ_NOTIFICATIONS -> R.string.capability_read_notifications
            CapabilityId.READ_FILES -> R.string.capability_read_files
            CapabilityId.HOME_ASSISTANT -> R.string.capability_home_assistant
            else -> R.string.capability_other
        },
    )

@Composable
private fun backendLabel(id: String): String =
    when (id) {
        BackendIds.ACCESSIBILITY -> stringResource(R.string.backend_accessibility)
        BackendIds.SHIZUKU -> stringResource(R.string.backend_shizuku)
        BackendIds.PACKAGE_MANAGER -> stringResource(R.string.backend_package_manager)
        BackendIds.NOTIFICATION_LISTENER -> stringResource(R.string.backend_notification_listener)
        BackendIds.SHARED_FOLDERS -> stringResource(R.string.backend_shared_folders)
        BackendIds.HOME_ASSISTANT -> stringResource(R.string.backend_home_assistant)
        else -> id
    }
