package app.pocketpilot.feature.clients

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pocketpilot.designsystem.LiquidGlassBentoCard

/** Plain words for each scope a client can be granted; an unknown scope shows as it is. */
private val SCOPE_LABELS =
    mapOf(
        "apps:control" to R.string.feature_clients_scope_apps_control,
        "apps:read" to R.string.feature_clients_scope_apps_read,
        "clipboard:rw" to R.string.feature_clients_scope_clipboard,
        "device:read" to R.string.feature_clients_scope_device,
        "files:read" to R.string.feature_clients_scope_files_read,
        "files:write" to R.string.feature_clients_scope_files_write,
        "home:control" to R.string.feature_clients_scope_home_control,
        "home:read" to R.string.feature_clients_scope_home_read,
        "location:read" to R.string.feature_clients_scope_location,
        "notify:act" to R.string.feature_clients_scope_notify_act,
        "notify:read" to R.string.feature_clients_scope_notify_read,
        "screen:read" to R.string.feature_clients_scope_screen,
        "settings:write" to R.string.feature_clients_scope_settings,
        "shell:exec" to R.string.feature_clients_scope_shell,
        "task:run" to R.string.feature_clients_scope_task,
        "termux:run" to R.string.feature_clients_scope_termux,
        "ui:interact" to R.string.feature_clients_scope_ui,
    )

/** One connected client. */
data class ClientRow(
    val id: String,
    val name: String,
    val redirectHost: String,
    val scopes: List<String>,
    val lastUsedAtMillis: Long?,
)

/**
 * Remote clients the owner has approved (spec section 8), each removable on its own, plus the kill
 * switch that ends every client's access and turns public access off.
 */
@Composable
fun ClientsCard(
    clients: List<ClientRow>,
    onRevoke: (String) -> Unit,
    onKillSwitch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var removing by remember { mutableStateOf<ClientRow?>(null) }
    LiquidGlassBentoCard(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.feature_clients_title), style = MaterialTheme.typography.titleMedium)
            if (clients.isEmpty()) {
                Text(stringResource(R.string.feature_clients_none), style = MaterialTheme.typography.bodySmall)
            }
            // Most recently used first, so an old pairing that is never used sinks to the bottom.
            clients.sortedByDescending { it.lastUsedAtMillis ?: 0L }.forEach { client ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${client.name} (${client.redirectHost})", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            client.lastUsedAtMillis?.let {
                                stringResource(R.string.feature_clients_last_used, DateUtils.getRelativeTimeSpanString(it).toString())
                            } ?: stringResource(R.string.feature_clients_never_used),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(
                                R.string.feature_clients_can,
                                client.scopes.map { scope -> SCOPE_LABELS[scope]?.let { stringResource(it) } ?: scope }.joinToString(", "),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { removing = client }) { Text(stringResource(R.string.feature_clients_remove)) }
                }
            }
            OutlinedButton(onClick = onKillSwitch) { Text(stringResource(R.string.feature_clients_kill_switch)) }
            Text(stringResource(R.string.feature_clients_kill_switch_explainer), style = MaterialTheme.typography.bodySmall)
        }
        removing?.let { client ->
            AlertDialog(
                onDismissRequest = { removing = null },
                title = { Text(stringResource(R.string.feature_clients_remove_title, client.name)) },
                text = { Text(stringResource(R.string.feature_clients_remove_text)) },
                confirmButton = {
                    TextButton(onClick = {
                        removing = null
                        onRevoke(client.id)
                    }) { Text(stringResource(R.string.feature_clients_remove)) }
                },
                dismissButton = {
                    TextButton(onClick = { removing = null }) { Text(stringResource(R.string.feature_clients_keep)) }
                },
            )
        }
    }
}
