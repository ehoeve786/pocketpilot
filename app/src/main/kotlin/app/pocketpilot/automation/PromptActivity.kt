package app.pocketpilot.automation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pocketpilot.designsystem.PocketPilotTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Shows the oldest open [Prompt] as a dialog over whatever is on screen, and closes when none are left. */
@AndroidEntryPoint
class PromptActivity : ComponentActivity() {
    @Inject lateinit var prompts: Prompts

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val first =
                prompts.pending
                    .collectAsStateWithLifecycle()
                    .value
                    .firstOrNull()
            LaunchedEffect(first == null) { if (first == null) finish() }
            PocketPilotTheme {
                if (first != null) PromptDialog(first) { value -> prompts.answer(first.id, value) }
            }
        }
    }
}

@Composable
private fun PromptDialog(
    prompt: Prompt,
    answer: (String?) -> Unit,
) {
    // Keyed by the prompt, so the next question starts fresh.
    var text by remember(prompt.id) { mutableStateOf(TextFieldValue(prompt.default, TextRange(prompt.default.length))) }
    var picked by remember(prompt.id) { mutableStateOf<String?>(null) }
    val focus = remember(prompt.id) { FocusRequester() }
    val choosing = prompt.options.isNotEmpty()
    val ok = if (choosing) picked != null else text.text.isNotBlank()
    AlertDialog(
        onDismissRequest = { answer(null) },
        title = { Text(prompt.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (prompt.message.isNotBlank()) Text(prompt.message, Modifier.padding(bottom = 8.dp))
                if (choosing) {
                    prompt.options.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { picked = option }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = picked == option, onClick = { picked = option })
                            Text(option)
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (ok) answer(text.text.trim()) }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                    LaunchedEffect(prompt.id) { runCatching { focus.requestFocus() } }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = { answer(if (choosing) picked else text.text.trim()) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = { answer(null) }) { Text(stringResource(android.R.string.cancel)) } },
    )
}
