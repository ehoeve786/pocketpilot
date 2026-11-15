package app.pocketpilot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pocketpilot.R
import app.pocketpilot.capability.shizuku.BuiltInStatus
import app.pocketpilot.designsystem.LiquidGlassBentoCard

/** What the built-in access card can do. */
internal class BuiltInActions(
    /** Shows the pairing notification and opens Wireless debugging. */
    val onPair: () -> Unit,
    val onStart: () -> Unit,
    val onForget: () -> Unit,
    val onTcpModeChange: (Boolean) -> Unit,
)

/** PocketPilot's own privileged process: pair once, then its state, Start, TCP mode and Forget. */
@Composable
internal fun BuiltInAccessCard(
    status: BuiltInStatus,
    tcpMode: Boolean,
    actions: BuiltInActions,
) {
    LiquidGlassBentoCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.builtin_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(
                    when (status.phase) {
                        BuiltInStatus.Phase.NOT_PAIRED -> R.string.builtin_not_paired
                        BuiltInStatus.Phase.STOPPED -> R.string.builtin_stopped
                        BuiltInStatus.Phase.STARTING -> R.string.builtin_starting
                        BuiltInStatus.Phase.RUNNING -> R.string.builtin_running
                    },
                ),
                color =
                    if (status.phase == BuiltInStatus.Phase.RUNNING) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
            )
            status.problem?.let {
                Text(
                    stringResource(R.string.builtin_problem, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (status.phase == BuiltInStatus.Phase.NOT_PAIRED) {
                Text(stringResource(R.string.builtin_explainer), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.builtin_pair_steps), style = MaterialTheme.typography.bodySmall)
                Button(onClick = actions.onPair) { Text(stringResource(R.string.builtin_pair)) }
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.builtin_tcp), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.builtin_tcp_explainer), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = tcpMode, onCheckedChange = actions.onTcpModeChange)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status.phase == BuiltInStatus.Phase.STOPPED) {
                    OutlinedButton(onClick = actions.onStart) { Text(stringResource(R.string.builtin_start)) }
                }
                TextButton(onClick = actions.onForget) { Text(stringResource(R.string.builtin_forget)) }
            }
        }
    }
}
