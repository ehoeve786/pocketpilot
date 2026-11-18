package app.pocketpilot.feature.automations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** One SSH server as the settings show and edit it; the password is never shown back. */
data class SshServerItem(
    val id: String? = null,
    val name: String = "",
    val host: String = "",
    val mac: String = "",
    val port: Int = 22,
    val user: String = "",
    val usePassword: Boolean = false,
    val hostKeySaved: Boolean = false,
)

/** What the SSH servers section can ask for. */
class SshActions(
    /** Saves the server; a non-null password replaces the saved one. Returns a problem in plain words, or null. */
    val save: suspend (SshServerItem, String?) -> String? = { _, _ -> null },
    val delete: (String) -> Unit = {},
    val forgetHostKey: (String) -> Unit = {},
    /** PocketPilot's public key as an `authorized_keys` line. */
    val publicKey: suspend () -> String = { "" },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SshSettings(
    servers: List<SshServerItem>,
    actions: SshActions,
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<SshServerItem?>(null) }
    var publicKey by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<SshServerItem?>(null) }

    Text(stringResource(R.string.feature_automations_ssh), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.feature_automations_ssh_text), style = MaterialTheme.typography.bodyMedium)
    servers.forEach { server ->
        Column(Modifier.fillMaxWidth()) {
            Text(server.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${server.user}@${server.host.ifEmpty { server.mac }}" + if (server.port != 22) ":${server.port}" else "",
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { editing = server }) { Text(stringResource(R.string.feature_automations_ssh_edit)) }
                if (server.hostKeySaved) {
                    TextButton(onClick = { server.id?.let(actions.forgetHostKey) }) {
                        Text(stringResource(R.string.feature_automations_ssh_forget_key))
                    }
                }
                TextButton(onClick = { deleting = server }) { Text(stringResource(R.string.feature_automations_ssh_delete)) }
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { editing = SshServerItem() }) { Text(stringResource(R.string.feature_automations_ssh_add)) }
        OutlinedButton(
            onClick = {
                scope.launch {
                    val key = runCatching { actions.publicKey() }.getOrNull() ?: return@launch
                    publicKey = key
                    clipboard.setText(AnnotatedString(key))
                }
            },
        ) { Text(stringResource(R.string.feature_automations_ssh_copy_key)) }
    }
    publicKey?.let { key ->
        Text(stringResource(R.string.feature_automations_ssh_key_copied), style = MaterialTheme.typography.bodySmall)
        Text(key, style = MaterialTheme.typography.bodySmall)
    }

    editing?.let { server -> SshServerDialog(server, actions, onDone = { editing = null }) }
    deleting?.let { server ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.feature_automations_ssh_delete_title, server.name)) },
            text = { Text(stringResource(R.string.feature_automations_ssh_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    server.id?.let(actions.delete)
                    deleting = null
                }) { Text(stringResource(R.string.feature_automations_ssh_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
private fun SshServerDialog(
    initial: SshServerItem,
    actions: SshActions,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Kept out of saved state: the password is a secret, and the rest is quick to type again.
    var name by remember { mutableStateOf(initial.name) }
    var host by remember { mutableStateOf(initial.host) }
    var mac by remember { mutableStateOf(initial.mac) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var user by remember { mutableStateOf(initial.user) }
    var usePassword by remember { mutableStateOf(initial.usePassword) }
    var password by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDone,
        title = {
            Text(
                stringResource(
                    if (initial.id == null) R.string.feature_automations_ssh_add else R.string.feature_automations_ssh_edit,
                ),
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.feature_automations_ssh_name)) },
                    placeholder = { Text("C3X") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.feature_automations_ssh_host)) },
                    placeholder = { Text("192.168.1.20") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = mac,
                    onValueChange = { mac = it },
                    label = { Text(stringResource(R.string.feature_automations_ssh_mac)) },
                    supportingText = { Text(stringResource(R.string.feature_automations_ssh_mac_hint)) },
                    placeholder = { Text("aa:bb:cc:dd:ee:ff") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = user,
                        onValueChange = { user = it },
                        label = { Text(stringResource(R.string.feature_automations_ssh_user)) },
                        placeholder = { Text("comma") },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit).take(5) },
                        label = { Text(stringResource(R.string.feature_automations_ssh_port)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.feature_automations_ssh_use_password), Modifier.weight(1f))
                    Switch(checked = usePassword, onCheckedChange = { usePassword = it })
                }
                if (usePassword) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.feature_automations_ssh_password)) },
                        placeholder = {
                            if (initial.usePassword) Text(stringResource(R.string.feature_automations_ha_token_saved))
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(stringResource(R.string.feature_automations_ssh_key_hint), style = MaterialTheme.typography.bodySmall)
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        val server =
                            initial.copy(
                                name = name,
                                host = host,
                                mac = mac,
                                port = port.toIntOrNull() ?: 0,
                                user = user,
                                usePassword = usePassword,
                            )
                        problem = actions.save(server, password.takeIf { usePassword && it.isNotEmpty() })
                        saving = false
                        if (problem == null) onDone()
                    }
                },
            ) { Text(stringResource(R.string.feature_automations_save)) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(android.R.string.cancel)) } },
    )
}
