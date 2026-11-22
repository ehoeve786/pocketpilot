package app.pocketpilot.feature.approvals

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.pocketpilot.designsystem.LiquidGlassBentoCard
import app.pocketpilot.core.designsystem.R as DsR

/** One permission on the consent screen. */
data class ScopeOption(
    /** The OAuth scope, such as `screen:read`. */
    val scope: String,
    /** Ticked when the screen opens. */
    val preselected: Boolean,
)

/** A client asking to connect, as the owner sees it. */
data class ConsentUi(
    val clientName: String,
    val redirectHost: String,
    val options: List<ScopeOption>,
    /** The code the browser must send before Allow works. */
    val pairingCode: String,
    /** True once the browser has sent [pairingCode]. */
    val paired: Boolean,
)

/**
 * The full-screen consent prompt (spec section 8): who is asking, where the browser returns, and
 * which permissions to grant. Nothing is granted until the owner taps Allow.
 */
@Composable
fun ConsentScreen(
    ui: ConsentUi,
    onAllow: (Set<String>) -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keyed on the request, not the whole ui, so pairing does not undo the owner's choices.
    var chosen by remember(ui.pairingCode, ui.options) {
        mutableStateOf(
            ui.options
                .filter { it.preselected }
                .map { it.scope }
                .toSet(),
        )
    }
    GlassDialog(modifier) {
        Column(
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DialogHeader(stringResource(R.string.feature_approvals_consent_title, ui.clientName), DsR.drawable.core_designsystem_ic_link)
            Text(stringResource(R.string.feature_approvals_consent_from, ui.redirectHost), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.feature_approvals_consent_explainer), style = MaterialTheme.typography.bodySmall)
            if (ui.paired) {
                Text(stringResource(R.string.feature_approvals_pairing_done), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    stringResource(R.string.feature_approvals_pairing_code, ui.pairingCode),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Text(stringResource(R.string.feature_approvals_pairing_wait), style = MaterialTheme.typography.bodySmall)
            }
            ui.options.forEach { option ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = option.scope in chosen,
                        onCheckedChange = { on -> chosen = if (on) chosen + option.scope else chosen - option.scope },
                    )
                    Column {
                        Text(scopeTitle(option.scope), style = MaterialTheme.typography.bodyLarge)
                        Text(option.scope, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text(stringResource(R.string.feature_approvals_consent_warning), style = MaterialTheme.typography.bodySmall)
        }
        DialogButtons(onDeny = onDeny, onAllow = { onAllow(chosen) }, allowEnabled = ui.paired && chosen.isNotEmpty())
    }
}

/** A tool call that needs the owner's yes (SENSITIVE and DESTRUCTIVE tools, or a confirm-all app). */
data class ConfirmUi(
    val principal: String,
    val toolTitle: String,
    val tool: String,
    val destructive: Boolean,
    val foregroundPackage: String?,
    /** Exactly what will run, such as a shell command. */
    val detail: String? = null,
)

/**
 * A tool call waiting for the owner, as a glass card over whatever is on screen: who asks, the tool, the app it
 * acts in, and exactly what will run, with Deny and Allow always in reach under a scrolling detail.
 */
@Composable
fun ConfirmScreen(
    ui: ConfirmUi,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassDialog(modifier) {
        DialogHeader(
            stringResource(R.string.feature_approvals_confirm_title, ui.principal),
            if (ui.destructive) DsR.drawable.core_designsystem_ic_lock else DsR.drawable.core_designsystem_ic_bolt,
        )
        Text(ui.toolTitle, style = MaterialTheme.typography.titleMedium)
        Text(ui.tool, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ui.foregroundPackage?.let {
            Text(stringResource(R.string.feature_approvals_confirm_in_app, it), style = MaterialTheme.typography.bodyMedium)
        }
        ui.detail?.let {
            // A long command scrolls inside its box, so Allow and Deny stay on screen.
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = DETAIL_MAX_HEIGHT)
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (ui.destructive) {
            Text(
                stringResource(R.string.feature_approvals_confirm_destructive),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        DialogButtons(onDeny = onDeny, onAllow = onAllow, destructive = ui.destructive)
    }
}

/** The glass card the approval screens sit in, centred over a soft scrim; the window behind is blurred. */
@Composable
private fun GlassDialog(
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = SCRIM_ALPHA))
            .safeDrawingPadding()
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        LiquidGlassBentoCard(
            modifier = Modifier.widthIn(max = DIALOG_MAX_WIDTH).fillMaxWidth(),
            contentPadding = PaddingValues(22.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable
private fun DialogHeader(
    title: String,
    icon: Int,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun DialogButtons(
    onDeny: () -> Unit,
    onAllow: () -> Unit,
    allowEnabled: Boolean = true,
    destructive: Boolean = false,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        OutlinedButton(onClick = onDeny, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.feature_approvals_deny)) }
        Button(
            onClick = onAllow,
            enabled = allowEnabled,
            modifier = Modifier.weight(1f),
            colors =
                if (destructive) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
        ) { Text(stringResource(R.string.feature_approvals_allow)) }
    }
}

private val DIALOG_MAX_WIDTH = 480.dp
private val DETAIL_MAX_HEIGHT = 220.dp
private const val SCRIM_ALPHA = 0.25f

@Composable
private fun scopeTitle(scope: String): String =
    when (scope) {
        "screen:read" -> stringResource(R.string.feature_approvals_scope_screen_read)
        "ui:interact" -> stringResource(R.string.feature_approvals_scope_ui_interact)
        "apps:read" -> stringResource(R.string.feature_approvals_scope_apps_read)
        "apps:control" -> stringResource(R.string.feature_approvals_scope_apps_control)
        "device:read" -> stringResource(R.string.feature_approvals_scope_device_read)
        "clipboard:rw" -> stringResource(R.string.feature_approvals_scope_clipboard)
        "notify:read" -> stringResource(R.string.feature_approvals_scope_notify_read)
        "notify:act" -> stringResource(R.string.feature_approvals_scope_notify_act)
        "settings:write" -> stringResource(R.string.feature_approvals_scope_settings_write)
        "files:read" -> stringResource(R.string.feature_approvals_scope_files_read)
        "files:write" -> stringResource(R.string.feature_approvals_scope_files_write)
        "task:run" -> stringResource(R.string.feature_approvals_scope_task_run)
        "home:read" -> stringResource(R.string.feature_approvals_scope_home_read)
        "home:control" -> stringResource(R.string.feature_approvals_scope_home_control)
        "location:read" -> stringResource(R.string.feature_approvals_scope_location_read)
        else -> scope
    }
