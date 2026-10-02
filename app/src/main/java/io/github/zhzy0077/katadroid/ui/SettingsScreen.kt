package io.github.zhzy0077.katadroid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zhzy0077.katadroid.R
import io.github.zhzy0077.katadroid.engine.GoRules
import io.github.zhzy0077.katadroid.engine.MAX_VISITS
import io.github.zhzy0077.katadroid.engine.validKomi
import io.github.zhzy0077.katadroid.ui.theme.GoColors

@Composable
fun PageHeader(title: String, onBack: () -> Unit) {
    val resources = LocalResources.current
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = resources.getString(R.string.back) }) { GoIcon(GoSymbol.Back) }
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = GoColors.Ink, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
fun SettingsScreen(
    settings: AppPreferences, rules: GoRules, komi: Float, busy: Boolean,
    onApply: (AppPreferences, GoRules, Float) -> Unit,
    onBack: () -> Unit, onDiagnostics: () -> Unit, onEngines: () -> Unit,
) {
    val resources = LocalResources.current
    val focus = LocalFocusManager.current
    var draftRule by rememberSaveable(rules) { mutableStateOf(rules) }
    var draftKomi by rememberSaveable(komi) { mutableStateOf(komi.toString()) }
    var draftVisits by rememberSaveable(settings.maxVisits) { mutableStateOf(settings.maxVisits.toString()) }
    var draftOffset by rememberSaveable(settings.touchOffsetPx) { mutableStateOf(settings.touchOffsetPx.toString()) }
    var autoBlack by rememberSaveable(settings.autoBlack) { mutableStateOf(settings.autoBlack) }
    var autoWhite by rememberSaveable(settings.autoWhite) { mutableStateOf(settings.autoWhite) }
    var coordinates by rememberSaveable(settings.coordinates) { mutableStateOf(settings.coordinates) }
    var candidates by rememberSaveable(settings.candidates) { mutableStateOf(settings.candidates) }
    val parsedKomi = draftKomi.toFloatOrNull()?.takeIf(::validKomi)
    val parsedVisits = draftVisits.toIntOrNull()?.takeIf { it in 1..MAX_VISITS }
    val parsedOffset = draftOffset.toIntOrNull()?.takeIf { it in 0..300 }
    val valid = parsedKomi != null && parsedVisits != null && parsedOffset != null
    val changed = parsedKomi != komi || draftRule != rules || parsedVisits != settings.maxVisits ||
        parsedOffset != settings.touchOffsetPx || autoBlack != settings.autoBlack || autoWhite != settings.autoWhite ||
        coordinates != settings.coordinates || candidates != settings.candidates

    Column(Modifier.fillMaxSize().imePadding().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)).testTag("settings-screen")) {
        PageHeader(resources.getString(R.string.settings), onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp).testTag("settings-scroll"),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsCard {
                SectionTitle(resources.getString(R.string.search_automation))
                IntegerSetting(resources.getString(R.string.search_limit), "visits", draftVisits, 1..MAX_VISITS,
                    listOf(100, 500, 1000, 5000), "search-limit") { draftVisits = it }
                ToggleRow(resources.getString(R.string.auto_black), resources.getString(R.string.katago_black), autoBlack, { autoBlack = it }, "auto-black")
                ToggleRow(resources.getString(R.string.auto_white), resources.getString(R.string.katago_white), autoWhite, { autoWhite = it }, "auto-white")
                Hint(resources.getString(R.string.automatic_hint))
            }
            SettingsCard {
                SectionTitle(resources.getString(R.string.rules_komi))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GoRules.entries.forEach { option ->
                        FilterChip(selected = draftRule == option, onClick = {
                            if (draftKomi.toFloatOrNull() == draftRule.defaultKomi) draftKomi = option.defaultKomi.toString()
                            draftRule = option
                        }, label = { Text(resources.getString(option.labelRes)) }, modifier = Modifier.testTag("rule-${option.id}"))
                    }
                }
                OutlinedTextField(value = draftKomi, onValueChange = { draftKomi = it }, singleLine = true,
                    label = { Text(resources.getString(R.string.komi_label)) }, suffix = { Text(resources.getString(R.string.points)) },
                    leadingIcon = { TextButton(onClick = { draftKomi = if (draftKomi.startsWith('-')) draftKomi.drop(1) else "-$draftKomi" }) { Text("±") } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = parsedKomi == null, supportingText = { Text(resources.getString(R.string.komi_hint)) },
                    modifier = Modifier.fillMaxWidth().testTag("komi-input"))
                Hint(resources.getString(R.string.rules_hint))
            }
            SettingsCard {
                SectionTitle(resources.getString(R.string.board_touch))
                ToggleRow(resources.getString(R.string.coordinates), resources.getString(R.string.coordinates_hint), coordinates, { coordinates = it }, "setting-coordinates")
                ToggleRow(resources.getString(R.string.candidates), resources.getString(R.string.candidates_hint), candidates, { candidates = it }, "setting-candidates")
                Hint(resources.getString(R.string.candidate_color_hint))
                HorizontalDivider(color = GoColors.Outline)
                IntegerSetting(resources.getString(R.string.touch_offset), "px", draftOffset, 0..300,
                    listOf(0, 60, 120), "touch-offset") { draftOffset = it }
                Hint(resources.getString(R.string.touch_hint))
            }
            SettingsCard {
                SectionTitle(resources.getString(R.string.engine))
                SettingsLink(resources.getString(R.string.engines_models), resources.getString(R.string.engines_hint), "open-engines", onEngines)
                HorizontalDivider(color = GoColors.Outline)
                SettingsLink(resources.getString(R.string.model_validation), resources.getString(R.string.validation_hint), "open-diagnostics", onDiagnostics)
            }
            Hint(resources.getString(R.string.visits_hint))
        }
        // One persistent action applies the whole draft, including toggles and rules.
        Surface(color = GoColors.Surface, shadowElevation = 8.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (changed) Hint(resources.getString(if (valid) R.string.settings_pending else R.string.settings_invalid))
                Button(onClick = {
                    focus.clearFocus()
                    onApply(settings.copy(maxVisits = checkNotNull(parsedVisits), touchOffsetPx = checkNotNull(parsedOffset),
                        autoBlack = autoBlack, autoWhite = autoWhite, coordinates = coordinates, candidates = candidates),
                        draftRule, checkNotNull(parsedKomi))
                }, enabled = changed && valid && !busy, modifier = Modifier.fillMaxWidth().testTag("apply-settings")) {
                    Text(resources.getString(if (busy) R.string.applying else if (changed) R.string.apply_settings else R.string.settings_saved))
                }
            }
        }
    }
}

@Composable
private fun IntegerSetting(label: String, unit: String, draft: String, range: IntRange, presets: List<Int>, tag: String, onChange: (String) -> Unit) {
    val resources = LocalResources.current
    val parsed = draft.toIntOrNull()?.takeIf { it in range }
    val focus = LocalFocusManager.current
    OutlinedTextField(value = draft, onValueChange = onChange, singleLine = true, label = { Text(label) },
        suffix = { Text(unit) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = parsed == null, supportingText = { Text(resources.getString(R.string.range_hint, range.first, range.last, unit)) },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("$tag-input"))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        presets.forEach { preset ->
            FilterChip(selected = parsed == preset, onClick = { onChange(preset.toString()); focus.clearFocus() },
                label = { Text(preset.toString(), fontSize = 12.sp) }, modifier = Modifier.testTag("$tag-$preset"))
        }
    }
}

@Composable
private fun SettingsLink(title: String, description: String, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 16.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = GoColors.Ink)
            Hint(description)
        }
        GoIcon(GoSymbol.Chevron, Modifier.size(20.dp), GoColors.Muted)
    }
}

@Composable
internal fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = GoColors.Surface, border = BorderStroke(1.dp, GoColors.Outline), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable
internal fun SectionTitle(title: String) { Text(title, fontSize = 12.sp, color = GoColors.Primary, fontWeight = FontWeight.SemiBold) }

@Composable
internal fun Hint(text: String) { Text(text, fontSize = 11.sp, lineHeight = 17.sp, color = GoColors.Muted) }

@Composable
private fun ToggleRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth().testTag(tag).toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = GoColors.Ink)
            Hint(description)
        }
        Switch(checked = checked, onCheckedChange = null, colors = SwitchDefaults.colors(
            uncheckedThumbColor = GoColors.Muted, uncheckedTrackColor = GoColors.Navigation,
            uncheckedBorderColor = GoColors.Outline))
    }
}
