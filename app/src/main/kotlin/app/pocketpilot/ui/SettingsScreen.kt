package app.pocketpilot.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.pocketpilot.R
import app.pocketpilot.capability.files.SharedFolder
import app.pocketpilot.capability.shizuku.BuiltInStatus
import app.pocketpilot.capability.shizuku.ShizukuState
import app.pocketpilot.core.capabilities.CapabilityStatus
import app.pocketpilot.core.model.PolicyProfile
import app.pocketpilot.designsystem.LiquidGlassBentoCard
import app.pocketpilot.feature.clients.ClientRow
import app.pocketpilot.feature.clients.ClientsCard
import app.pocketpilot.feature.onboarding.SetupStep
import app.pocketpilot.network.api.NetworkState
import app.pocketpilot.network.api.PublicAccessState
import app.pocketpilot.core.designsystem.R as DsR

/** What the Settings screen shows. */
internal class SettingsState(
    val policyProfile: PolicyProfile,
    val versionName: String,
    val accessibilityOn: Boolean,
    val remoteState: NetworkState,
    val publicState: PublicAccessState,
    val clients: List<ClientRow>,
    val token: String,
    val eventKey: String,
    val sharedFolders: List<SharedFolder>,
    val biometricApprovals: Boolean,
    val capabilities: List<CapabilityStatus>,
    val shizukuState: ShizukuState,
    val setupSteps: List<SetupStep>,
    val screenLockMissing: Boolean = false,
    val secretsLost: Boolean = false,
    val secretsLocked: Boolean = true,
    /** PocketPilot's own privileged process serves calls, so Doctor does not ask for Shizuku. */
    val builtInRunning: Boolean = false,
    /** Where PocketPilot's own privileged process stands, for its row in the section list. */
    val builtInPhase: BuiltInStatus.Phase = BuiltInStatus.Phase.NOT_PAIRED,
)

/** The Settings screen's callbacks. */
internal class SettingsActions(
    val onOpenAccessibilitySettings: () -> Unit,
    val onOpenAppInfo: () -> Unit,
    val remoteActions: RemoteActions,
    val onRevokeClient: (String) -> Unit,
    val onKillSwitch: () -> Unit,
    val onRotateToken: () -> Unit,
    val onAddFolder: () -> Unit,
    val onRemoveFolder: (SharedFolder) -> Unit,
    val onBiometricChange: (Boolean) -> Unit,
    val onOpenSetup: () -> Unit,
    val onGrantShizuku: () -> Unit,
    val onAllowNotifications: () -> Unit,
    val onSecretsLockChange: (Boolean) -> Unit,
)

/** Settings' sections; the list shows one row each, and a tap opens that section on its own page. */
private enum class SettingsSection(
    @param:StringRes val title: Int,
) {
    SCREEN(R.string.accessibility_title),
    PRIVILEGED(R.string.builtin_title),
    REMOTE(R.string.settings_section_remote),
    APPS(R.string.settings_section_apps),
    AGENT(R.string.settings_section_agent),
    AUTOMATIONS(R.string.settings_section_automations),
    SECURITY(R.string.settings_section_security),
    FOLDERS(R.string.folders_title),
    DOCTOR(R.string.doctor_title),
}

/**
 * Settings, opened from the gear: a short list of sections (screen access, remote access, connected apps,
 * Agent mode, automations, token and security, shared folders, Doctor), each opening on its own page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    state: SettingsState,
    actions: SettingsActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** WireGuard with the owner's relay, shown under Tailscale. */
    ownNetworkCard: @Composable () -> Unit = {},
    /** Agent mode's models. */
    agentModelsCard: @Composable () -> Unit = {},
    /** Automations' own settings. */
    automationSettingsCard: @Composable () -> Unit = {},
    /** PocketPilot's own privileged access over Wireless debugging, in its own section. */
    builtInAccessCard: @Composable () -> Unit = {},
    /** Counts up each time a setup step asks for remote access; Settings then opens on that section. */
    remoteAccessRequest: Int = 0,
    /** Clears [remoteAccessRequest] once handled, so a later open of Settings does not jump there again. */
    onRemoteAccessShown: () -> Unit = {},
) {
    var section by rememberSaveable { mutableStateOf<SettingsSection?>(null) }
    LaunchedEffect(remoteAccessRequest) {
        if (remoteAccessRequest > 0) {
            section = SettingsSection.REMOTE
            onRemoteAccessShown()
        }
    }
    BackHandler(enabled = section != null) { section = null }
    val open = section
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(open?.title ?: R.string.tab_settings)) },
                navigationIcon = {
                    IconButton(onClick = { if (open != null) section = null else onBack() }) {
                        Icon(
                            painterResource(DsR.drawable.core_designsystem_ic_arrow_back),
                            contentDescription = stringResource(R.string.nav_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (open == null) {
                if (state.secretsLost) {
                    Text(stringResource(R.string.settings_secrets_lost), style = MaterialTheme.typography.bodyMedium)
                }
                SectionList(state) { section = it }
                Text(stringResource(R.string.home_tagline), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(
                        R.string.home_build,
                        state.policyProfile.flavor.name
                            .lowercase(),
                        state.versionName,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SectionContent(open, state, actions, ownNetworkCard, agentModelsCard, automationSettingsCard, builtInAccessCard)
            }
        }
    }
}

/** One card with a row per section: its name, a line on its state, and a chevron. */
@Composable
private fun SectionList(
    state: SettingsState,
    onOpen: (SettingsSection) -> Unit,
) {
    LiquidGlassBentoCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        Column {
            SettingsSection.entries.forEach { section ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(section) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(section.title), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            sectionSummary(section, state),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

@Composable
private fun sectionSummary(
    section: SettingsSection,
    state: SettingsState,
): String =
    when (section) {
        SettingsSection.SCREEN -> {
            stringResource(if (state.accessibilityOn) R.string.settings_summary_on else R.string.settings_summary_off)
        }

        SettingsSection.PRIVILEGED -> {
            stringResource(
                when (state.builtInPhase) {
                    BuiltInStatus.Phase.NOT_PAIRED -> R.string.settings_summary_builtin_not_paired
                    BuiltInStatus.Phase.STOPPED -> R.string.settings_summary_builtin_stopped
                    BuiltInStatus.Phase.STARTING -> R.string.builtin_starting
                    BuiltInStatus.Phase.RUNNING -> R.string.settings_summary_builtin_running
                },
            )
        }

        SettingsSection.REMOTE -> {
            val connected = state.remoteState is NetworkState.Connected
            val tailnet = stringResource(if (connected) R.string.settings_summary_tailnet_on else R.string.settings_summary_off)
            if (state.publicState.enabled) stringResource(R.string.settings_summary_public, tailnet) else tailnet
        }

        SettingsSection.APPS -> {
            stringResource(R.string.settings_summary_apps, state.clients.size)
        }

        SettingsSection.AGENT -> {
            stringResource(R.string.settings_summary_agent)
        }

        SettingsSection.AUTOMATIONS -> {
            stringResource(R.string.settings_summary_automations)
        }

        SettingsSection.SECURITY -> {
            stringResource(R.string.settings_summary_security)
        }

        SettingsSection.FOLDERS -> {
            stringResource(R.string.settings_summary_folders, state.sharedFolders.size)
        }

        SettingsSection.DOCTOR -> {
            val allSet =
                state.setupSteps.all { it.done || it.optional } &&
                    state.capabilities.all { it.active != null } &&
                    state.shizukuState == ShizukuState.CONNECTED
            stringResource(if (allSet) R.string.doctor_all_set else R.string.settings_summary_doctor_problem)
        }
    }

/** The cards of one section, on their own page. */
@Composable
private fun SectionContent(
    section: SettingsSection,
    state: SettingsState,
    actions: SettingsActions,
    ownNetworkCard: @Composable () -> Unit,
    agentModelsCard: @Composable () -> Unit,
    automationSettingsCard: @Composable () -> Unit,
    builtInAccessCard: @Composable () -> Unit,
) {
    when (section) {
        SettingsSection.SCREEN -> {
            AccessibilityCard(state.accessibilityOn, actions.onOpenAccessibilitySettings, actions.onOpenAppInfo)
        }

        SettingsSection.PRIVILEGED -> {
            builtInAccessCard()
        }

        SettingsSection.REMOTE -> {
            RemoteAccessCard(
                state = state.remoteState,
                public = state.publicState,
                onTurnOn = actions.remoteActions.turnOn,
                onTurnOff = actions.remoteActions.turnOff,
                onSignIn = actions.remoteActions.signIn,
                onSignOut = actions.remoteActions.signOut,
                onPublicChange = actions.remoteActions.setPublic,
                onAutoOffChange = actions.remoteActions.setAutoOff,
                logs = actions.remoteActions.logs,
            )
            ownNetworkCard()
        }

        SettingsSection.APPS -> {
            // Revokes every client and stops the tunnel, so it asks first, as the Home screen's kill switch does.
            var confirmKill by rememberSaveable { mutableStateOf(false) }
            ClientsCard(state.clients, actions.onRevokeClient, onKillSwitch = { confirmKill = true })
            if (confirmKill) {
                AlertDialog(
                    onDismissRequest = { confirmKill = false },
                    title = { Text(stringResource(R.string.nav_kill_switch_title)) },
                    text = { Text(stringResource(R.string.nav_kill_switch_text)) },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmKill = false
                            actions.onKillSwitch()
                        }) {
                            Text(stringResource(R.string.nav_kill_switch_confirm), color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmKill = false }) { Text(stringResource(R.string.nav_cancel)) }
                    },
                )
            }
        }

        SettingsSection.AGENT -> {
            agentModelsCard()
        }

        SettingsSection.AUTOMATIONS -> {
            automationSettingsCard()
        }

        SettingsSection.SECURITY -> {
            BiometricCard(state.biometricApprovals, state.screenLockMissing, actions.onBiometricChange)
            SecretsLockCard(state.secretsLocked, actions.onSecretsLockChange)
            TokenCard(state.token, state.eventKey, actions.onRotateToken)
        }

        SettingsSection.FOLDERS -> {
            FoldersCard(state.sharedFolders, actions.onAddFolder, actions.onRemoveFolder)
        }

        SettingsSection.DOCTOR -> {
            DoctorCard(
                state.capabilities,
                state.shizukuState,
                actions.onGrantShizuku,
                actions.onAllowNotifications,
                state.setupSteps,
                builtInRunning = state.builtInRunning,
            )
            OutlinedButton(onClick = actions.onOpenSetup, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_setup_guide))
            }
        }
    }
}

@Composable
private fun AccessibilityCard(
    on: Boolean,
    onOpenSettings: () -> Unit,
    onOpenAppInfo: () -> Unit,
) {
    LiquidGlassBentoCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.accessibility_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(if (on) R.string.accessibility_on else R.string.accessibility_off))
            if (!on) {
                Text(stringResource(R.string.accessibility_explainer), style = MaterialTheme.typography.bodySmall)
                Button(onClick = onOpenSettings) { Text(stringResource(R.string.accessibility_open_settings)) }
                Text(stringResource(R.string.accessibility_restricted), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onOpenAppInfo) { Text(stringResource(R.string.accessibility_open_app_info)) }
            }
        }
    }
}

@Composable
private fun FoldersCard(
    folders: List<SharedFolder>,
    onAdd: () -> Unit,
    onRemove: (SharedFolder) -> Unit,
) {
    LiquidGlassBentoCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.folders_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.folders_explainer), style = MaterialTheme.typography.bodySmall)
            if (folders.isEmpty()) Text(stringResource(R.string.folders_none), style = MaterialTheme.typography.bodySmall)
            folders.forEach { folder ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(folder.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onRemove(folder) }) { Text(stringResource(R.string.folders_remove)) }
                }
            }
            Button(onClick = onAdd) { Text(stringResource(R.string.folders_add)) }
        }
    }
}

@Composable
private fun SecretsLockCard(
    locked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    LiquidGlassBentoCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.secrets_lock_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = locked, onCheckedChange = onChange)
            }
            Text(stringResource(R.string.secrets_lock_explainer), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun BiometricCard(
    enabled: Boolean,
    screenLockMissing: Boolean,
    onChange: (Boolean) -> Unit,
) {
    LiquidGlassBentoCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (screenLockMissing) {
                Text(stringResource(R.string.biometric_no_screen_lock), style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.biometric_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = enabled, onCheckedChange = onChange)
            }
            Text(stringResource(R.string.biometric_explainer), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TokenCard(
    token: String,
    eventKey: String,
    onRotateToken: () -> Unit,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    var confirmRotate by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    LiquidGlassBentoCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.token_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.token_explainer), style = MaterialTheme.typography.bodySmall)
            Text(
                if (visible) token else "•".repeat(MASKED_LENGTH),
                fontFamily = FontFamily.Monospace,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { visible = !visible }) {
                    Text(stringResource(if (visible) R.string.token_hide else R.string.token_show))
                }
                TextButton(onClick = { copySecret(context, token) }) { Text(stringResource(R.string.token_copy)) }
                TextButton(onClick = { confirmRotate = true }) { Text(stringResource(R.string.token_rotate)) }
            }
            Text(stringResource(R.string.event_key_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.event_key_explainer), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { copySecret(context, eventKey) }) { Text(stringResource(R.string.event_key_copy)) }
        }
    }
    if (confirmRotate) {
        // Every app that uses the token stops connecting until it is given the new one.
        AlertDialog(
            onDismissRequest = { confirmRotate = false },
            title = { Text(stringResource(R.string.token_rotate_title)) },
            text = { Text(stringResource(R.string.token_rotate_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRotate = false
                    onRotateToken()
                }) { Text(stringResource(R.string.token_rotate), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRotate = false }) { Text(stringResource(R.string.nav_cancel)) }
            },
        )
    }
}

private const val MASKED_LENGTH = 24
