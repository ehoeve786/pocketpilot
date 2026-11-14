package app.pocketpilot.feature.automations

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.pocketpilot.core.automation.Category
import app.pocketpilot.core.automation.Choice
import app.pocketpilot.core.automation.ColorText
import app.pocketpilot.core.automation.FieldDef
import app.pocketpilot.core.automation.FieldKind
import app.pocketpilot.core.automation.PickOption
import app.pocketpilot.core.automation.PickSource
import app.pocketpilot.core.automation.Placeholders
import app.pocketpilot.core.automation.Rule
import app.pocketpilot.core.automation.RuleValidator
import app.pocketpilot.core.automation.isCredentialKey
import app.pocketpilot.core.model.RiskTier
import kotlin.math.roundToInt

/** One entry of a catalog picker. */
internal data class PickerItem(
    val type: String,
    val title: String,
    val description: String,
    val category: Category,
    val tier: RiskTier? = null,
)

/** A searchable list of triggers, conditions or actions, filtered by category. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CatalogPickerSheet(
    title: String,
    items: List<PickerItem>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<Category?>(null) }
    val categories = items.map { it.category }.distinct()
    val shown =
        items.filter { item ->
            (category == null || item.category == category) &&
                (query.isBlank() || item.title.contains(query, ignoreCase = true) || item.description.contains(query, ignoreCase = true))
        }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).fillMaxHeight(SHEET_HEIGHT), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.feature_automations_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = category == null,
                    onClick = { category = null },
                    label = { Text(stringResource(R.string.feature_automations_all)) },
                )
                categories.forEach { c ->
                    FilterChip(selected = category == c, onClick = { category = if (category == c) null else c }, label = { Text(c.title) })
                }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(shown, key = { it.type }) { item ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(item.type) }
                            .padding(vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(item.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
                            item.tier?.takeIf { it >= RiskTier.SENSITIVE }?.let { TierTag(it) }
                        }
                        Text(
                            item.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

private const val SHEET_HEIGHT = 0.9f

/** What a field picker may need from the app. */
internal data class FieldContext(
    val apps: List<AppOption>,
    val options: Map<PickSource, List<PickOption>>,
    val rules: List<Rule>,
    val tools: List<String>,
    val currentRuleId: String,
    val loadApps: () -> Unit,
    val loadOptions: (PickSource) -> Unit,
    val listDirectory: suspend (String) -> List<PickOption>,
)

/** Edits [fields] of one trigger, condition or action, plus any [extra] settings, in a bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FieldsSheet(
    title: String,
    description: String,
    fields: List<FieldDef>,
    params: Map<String, String>,
    context: FieldContext,
    onDone: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    var values by remember { mutableStateOf(params) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            fields.forEach { field ->
                FieldEditor(field, values[field.key] ?: field.default.orEmpty(), context) { values = values + (field.key to it) }
            }
            extra()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_automations_cancel)) }
                Button(
                    // A cleared range bound is kept as blank, which means "no limit"; other blanks fall back to defaults.
                    onClick = { onDone(values.filter { (key, value) -> value.isNotEmpty() || key in RuleValidator.OPEN_WHEN_CLEARED }) },
                ) { Text(stringResource(R.string.feature_automations_done)) }
            }
        }
    }
}

/** A text field with a searchable list of suggested values; typing still works. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceField(
    label: String,
    value: String,
    source: PickSource,
    context: FieldContext,
    onChange: (String) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    val selected = value.split(',').map(String::trim).filter(String::isNotEmpty)
    LaunchedEffect(source) { context.loadOptions(source) }
    val options = context.options[source].orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { picking = true }) { Text(stringResource(R.string.feature_automations_choose)) }
    }
    if (picking) {
        var query by remember { mutableStateOf("") }
        var chosen by remember { mutableStateOf(selected.toSet()) }
        ModalBottomSheet(onDismissRequest = { picking = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.padding(horizontal = 16.dp).fillMaxHeight(SHEET_HEIGHT), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.feature_automations_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (options.isEmpty()) {
                    Text(
                        stringResource(R.string.feature_automations_no_suggestions),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(
                        // Two options can share a value, such as two Bluetooth devices of one name; a list key must be unique.
                        options
                            .distinctBy { it.value }
                            .filter {
                                query.isBlank() || it.label.contains(query, ignoreCase = true) ||
                                    it.value.contains(query, ignoreCase = true)
                            },
                        key = { it.value },
                    ) { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (!source.multiple) {
                                        onChange(option.value)
                                        picking = false
                                    } else {
                                        chosen = if (option.value in chosen) chosen - option.value else chosen + option.value
                                    }
                                }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (source.multiple) {
                                Checkbox(checked = option.value in chosen, onCheckedChange = null)
                            }
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(option.label, style = MaterialTheme.typography.bodyLarge)
                                option.detail?.let {
                                    Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                    TextButton(onClick = { picking = false }) { Text(stringResource(R.string.feature_automations_cancel)) }
                    if (source.multiple) {
                        Button(
                            onClick = {
                                onChange(chosen.joinToString(","))
                                picking = false
                            },
                        ) { Text(stringResource(R.string.feature_automations_done)) }
                    }
                }
            }
        }
    }
}

private const val DEFAULT_BROWSE_FOLDER = "/storage/emulated/0"

/** A path text field with a browse button for folders and files; typing still works. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileField(
    label: String,
    value: String,
    context: FieldContext,
    onChange: (String) -> Unit,
) {
    var browsing by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf(DEFAULT_BROWSE_FOLDER) }
    var entries by remember { mutableStateOf<List<PickOption>>(emptyList()) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            onClick = {
                folder = value.substringBeforeLast('/', "").ifBlank { DEFAULT_BROWSE_FOLDER }
                browsing = true
            },
        ) { Text(stringResource(R.string.feature_automations_browse)) }
    }
    if (browsing) {
        LaunchedEffect(folder) { entries = context.listDirectory(folder) }
        ModalBottomSheet(
            onDismissRequest = { browsing = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.padding(horizontal = 16.dp).fillMaxHeight(SHEET_HEIGHT), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(folder, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                if (entries.isEmpty()) {
                    Text(
                        stringResource(R.string.feature_automations_no_files),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(entries, key = { it.value }) { entry ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (entry.value.endsWith("/")) {
                                        folder = entry.value.trimEnd('/')
                                    } else {
                                        onChange(entry.value)
                                        browsing = false
                                    }
                                }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.label, style = MaterialTheme.typography.bodyLarge)
                                entry.detail?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                    }
                }
                TextButton(onClick = { browsing = false }) { Text(stringResource(R.string.feature_automations_cancel)) }
            }
        }
    }
}

private val DAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FieldEditor(
    field: FieldDef,
    value: String,
    context: FieldContext,
    onChange: (String) -> Unit,
) {
    val label = field.label + if (field.required) "" else " " + stringResource(R.string.feature_automations_optional)
    field.source?.let { source ->
        SourceField(field.label, value, source, context, onChange)
        return
    }
    if (field.browse) {
        FileField(field.label, value, context, onChange)
        return
    }
    // A time or days field can hold a variable such as {run_from}, which the clock and the day chips cannot show.
    if (field.kind == FieldKind.TIME || field.kind == FieldKind.DAYS) {
        var typed by remember(field.key) { mutableStateOf(Placeholders.contains(value)) }
        if (typed) {
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            return
        }
        TextButton(onClick = { typed = true }) { Text(stringResource(R.string.feature_automations_use_variable)) }
    }
    when (field.kind) {
        FieldKind.CHOICE -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    field.choices.forEach { choice ->
                        FilterChip(selected = value == choice.value, onClick = { onChange(choice.value) }, label = { Text(choice.label) })
                    }
                }
            }
        }

        FieldKind.ICON -> {
            IconField(label, value, field.choices, onChange)
        }

        FieldKind.BOOLEAN -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = value == "true", onCheckedChange = { onChange(it.toString()) })
            }
        }

        FieldKind.PERCENT -> {
            val percent = value.toFloatOrNull()?.coerceIn(0f, 100f) ?: 100f
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("$label: ${percent.roundToInt()}%", style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = percent,
                    onValueChange = { onChange(it.roundToInt().toString()) },
                    valueRange = 0f..100f,
                )
            }
        }

        FieldKind.COLOR -> {
            ColorField(label, value, onChange)
        }

        FieldKind.DAYS -> {
            val selected =
                value
                    .split(',')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .toSet()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DAYS.forEach { day ->
                        FilterChip(
                            selected = day in selected,
                            onClick = {
                                val next = if (day in selected) selected - day else selected + day
                                onChange(DAYS.filter { it in next }.joinToString(","))
                            },
                            label = { Text(day.replaceFirstChar(Char::uppercase)) },
                        )
                    }
                }
            }
        }

        FieldKind.APP -> {
            AppField(label, value, context, field.single, onChange)
        }

        FieldKind.RULE -> {
            val others = context.rules.filter { it.id != context.currentRuleId && !it.pending }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                if (others.isEmpty()) {
                    Text(
                        stringResource(R.string.feature_automations_no_other_rules),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    others.forEach { rule ->
                        FilterChip(selected = value == rule.id, onClick = { onChange(rule.id) }, label = { Text(rule.name) })
                    }
                }
            }
        }

        FieldKind.TOOL -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onChange,
                    label = { Text(label) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    context.tools
                        .filter { value.isBlank() || (it.contains(value, ignoreCase = true) && it != value) }
                        .take(TOOL_SUGGESTIONS)
                        .forEach { tool -> FilterChip(selected = false, onClick = { onChange(tool) }, label = { Text(tool) }) }
                }
            }
        }

        FieldKind.TIME -> {
            TimeField(label, value, onChange)
        }

        else -> {
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                label = { Text(label) },
                // A credential such as an Authorization header is masked while it is typed, as the token fields are.
                visualTransformation = if (isCredentialKey(field.key)) PasswordVisualTransformation() else VisualTransformation.None,
                placeholder = (field.hint ?: if (field.kind == FieldKind.TIME) "HH:mm" else null)?.let { hint -> { Text(hint) } },
                singleLine = field.kind != FieldKind.MULTILINE && field.kind != FieldKind.JSON,
                minLines = if (field.kind == FieldKind.MULTILINE || field.kind == FieldKind.JSON) 3 else 1,
                textStyle =
                    if (field.kind == FieldKind.JSON) {
                        MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                    } else {
                        MaterialTheme.typography.bodyLarge
                    },
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType =
                            when (field.kind) {
                                FieldKind.NUMBER -> KeyboardType.Decimal
                                FieldKind.TIME -> KeyboardType.Number
                                else -> KeyboardType.Text
                            },
                    ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private const val TOOL_SUGGESTIONS = 8

/** Common colours for a floating text, its background and its words, with a see-through black for backgrounds. */
private val SWATCHES =
    listOf(
        "#000000",
        "#80000000",
        "#424242",
        "#9E9E9E",
        "#FFFFFF",
        "#E53935",
        "#FB8C00",
        "#FDD835",
        "#43A047",
        "#00897B",
        "#1E88E5",
        "#3949AB",
        "#8E24AA",
        "#D81B60",
        "#6D4C41",
    )

/** A colour setting: tap a swatch, or type any "#RRGGBB" or "#AARRGGBB" below it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
) {
    val current = ColorText.argb(value)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SWATCHES.forEach { hex ->
                val argb = ColorText.argb(hex) ?: return@forEach
                val selected = argb == current
                Box(
                    modifier =
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(argb))
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                shape = CircleShape,
                            ).clickable { onChange(ColorText.format(argb)) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(current?.let { Color(it) } ?: Color.Transparent)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
            )
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                label = { Text(stringResource(R.string.feature_automations_color_custom)) },
                placeholder = { Text("#RRGGBB") },
                isError = value.isNotBlank() && current == null && !value.contains('{'),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Package names as text, with a searchable list of installed apps to tick. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppField(
    label: String,
    value: String,
    context: FieldContext,
    single: Boolean,
    onChange: (String) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    val selected = value.split(',').map(String::trim).filter(String::isNotEmpty)
    LaunchedEffect(Unit) { context.loadApps() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            supportingText = {
                if (selected.isNotEmpty()) {
                    Text(selected.joinToString { pkg -> context.apps.firstOrNull { it.packageName == pkg }?.label ?: pkg })
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { picking = true }) { Text(stringResource(R.string.feature_automations_choose_apps)) }
    }
    if (picking) {
        var query by remember { mutableStateOf("") }
        var chosen by remember { mutableStateOf(selected.toSet()) }
        ModalBottomSheet(onDismissRequest = { picking = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.padding(horizontal = 16.dp).fillMaxHeight(SHEET_HEIGHT), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.feature_automations_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (context.apps.isEmpty()) {
                    Text(
                        stringResource(R.string.feature_automations_loading_apps),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(
                        context.apps.filter {
                            query.isBlank() || it.label.contains(query, ignoreCase = true) ||
                                it.packageName.contains(query, ignoreCase = true)
                        },
                        key = { it.packageName },
                    ) { app ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (single) {
                                        onChange(app.packageName)
                                        picking = false
                                    } else {
                                        chosen = if (app.packageName in chosen) chosen - app.packageName else chosen + app.packageName
                                    }
                                }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (!single) {
                                Checkbox(checked = app.packageName in chosen, onCheckedChange = null)
                            }
                            app.icon?.let { icon ->
                                Image(bitmap = icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(32.dp))
                            }
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                Text(app.packageName, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                    TextButton(onClick = { picking = false }) { Text(stringResource(R.string.feature_automations_cancel)) }
                    if (!single) {
                        Button(
                            onClick = {
                                onChange(chosen.joinToString(","))
                                picking = false
                            },
                        ) { Text(stringResource(R.string.feature_automations_done)) }
                    }
                }
            }
        }
    }
}

/** Tab for icon choices with no [Choice.tab]. */
private const val STANDARD_TAB = "Standard"

/** Icon choices as a grid under tabs, with the chosen one highlighted. */
@Composable
private fun IconField(
    label: String,
    value: String,
    choices: List<Choice>,
    onChange: (String) -> Unit,
) {
    val tabs = choices.map { it.tab ?: STANDARD_TAB }.distinct()
    var tab by remember { mutableStateOf(choices.firstOrNull { it.value == value }?.tab ?: STANDARD_TAB) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        ScrollableTabRow(selectedTabIndex = tabs.indexOf(tab).coerceAtLeast(0), edgePadding = 0.dp) {
            tabs.forEach { name ->
                Tab(selected = name == tab, onClick = { tab = name }, text = { Text(name) })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            choices.filter { (it.tab ?: STANDARD_TAB) == tab }.forEach { choice ->
                val selected = value == choice.value
                val iconRes = automationIconRes(choice.value)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            ).clickable { onChange(choice.value) },
                ) {
                    if (iconRes != null) {
                        Icon(painterResource(iconRes), contentDescription = choice.label, modifier = Modifier.size(28.dp))
                    } else {
                        Text(choice.label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                    }
                }
            }
        }
        Text(choices.firstOrNull { it.value == value }?.label.orEmpty(), style = MaterialTheme.typography.bodySmall)
    }
}

/** A time shown as "12:45 AM"; tapping it opens a clock with AM and PM. Saves HH:mm; a {variable} stays until a time is picked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val time = hourMinute(value)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(time?.let { (h, m) -> twelveHour(h, m) } ?: value, style = MaterialTheme.typography.titleMedium)
        }
    }
    if (open) {
        val state = rememberTimePickerState(time?.first ?: 0, time?.second ?: 0, is24Hour = false)
        AlertDialog(
            onDismissRequest = { open = false },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onChange("${state.hour.twoDigits()}:${state.minute.twoDigits()}")
                        open = false
                    },
                ) { Text(stringResource(R.string.feature_automations_save)) }
            },
            dismissButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.feature_automations_cancel)) }
            },
        )
    }
}

/** "HH:mm" as hour and minute, or null when it is not a time. */
private fun hourMinute(text: String): Pair<Int, Int>? {
    val parts = text.trim().split(':')
    val hour = parts.getOrNull(0)?.toIntOrNull()
    val minute = parts.getOrNull(1)?.toIntOrNull()
    if (parts.size != 2 || hour == null || minute == null || hour !in 0..23 || minute !in 0..59) return null
    return hour to minute
}

private fun twelveHour(
    hour: Int,
    minute: Int,
): String = "${if (hour % 12 == 0) 12 else hour % 12}:${minute.twoDigits()} ${if (hour < 12) "AM" else "PM"}"

private fun Int.twoDigits(): String = toString().padStart(2, '0')
