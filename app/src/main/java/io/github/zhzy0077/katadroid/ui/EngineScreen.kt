package io.github.zhzy0077.katadroid.ui

import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import android.content.res.Resources
import io.github.zhzy0077.katadroid.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zhzy0077.katadroid.NpuProbe
import io.github.zhzy0077.katadroid.engine.*
import io.github.zhzy0077.katadroid.ui.theme.GoColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun EngineScreen(
    config: EngineConfig,
    benchmark: BenchmarkUiState,
    onConfig: (EngineConfig) -> Unit,
    onStart: (Int) -> Unit,
    onCancel: () -> Unit,
    onDiagnostics: () -> Unit,
    onBack: () -> Unit,
) {
    val resources = LocalResources.current
    val context = LocalContext.current
    var visits by rememberSaveable { mutableIntStateOf(500) }
    val selectedResult = benchmark.resultFor(config)
    val supportsNpu = NpuProbe.hasRuntimeLibraries(context)
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)).testTag("engine-screen")) {
        PageHeader(resources.getString(R.string.engines_models), onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp).testTag("engine-scroll"),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsCard {
                SectionTitle(resources.getString(R.string.katago_models))
                Column(Modifier.selectableGroup()) {
                    KataGoModel.entries.forEach { model ->
                        val result = benchmark.resultFor(config.copy(model = model))
                        Row(Modifier.fillMaxWidth().testTag("model-${model.id}")
                            .selectable(config.model == model, role = Role.RadioButton, onClick = {
                                if (config.model != model) onConfig(config.copy(model = model))
                            })
                            .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = config.model == model, onClick = null)
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(resources.getString(model.titleRes), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = GoColors.Ink)
                                Hint(resources.getString(model.descriptionRes))
                                Hint(result?.let { resources.getString(R.string.previous_speed, decimal(it.visitsPerSecond), it.samples.size, it.visitsPerPosition, it.config.backend.name) }
                                    ?: resources.getString(R.string.model_untested))
                            }
                        }
                    }
                }
                HorizontalDivider(color = GoColors.Outline)
                Hint(resources.getString(R.string.model_strength_hint))
                Hint(resources.getString(R.string.model_weights, config.model.sourceName))
            }
            SettingsCard {
                SectionTitle(resources.getString(R.string.compute_device))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EngineBackend.entries.forEach { backend ->
                        FilterChip(selected = config.backend == backend,
                            enabled = backend != EngineBackend.NPU || supportsNpu,
                            onClick = { if (config.backend != backend) onConfig(config.copy(backend = backend)) },
                            label = { Text(resources.getString(backend.labelRes)) }, modifier = Modifier.testTag("backend-${backend.name}"))
                    }
                }
                Hint(resources.getString(R.string.backend_hint))
            }
            SettingsCard {
                SectionTitle(resources.getString(R.string.search_benchmark))
                Text(config.displayLabel(resources), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = GoColors.Ink)
                Hint(resources.getString(R.string.benchmark_cases))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(100, 500, 1000).forEach { count ->
                        FilterChip(selected = visits == count, enabled = !benchmark.running, onClick = { visits = count },
                            label = { Text(count.toString(), maxLines = 1) }, modifier = Modifier.testTag("benchmark-limit-$count"))
                    }
                }
                Hint(resources.getString(R.string.benchmark_visits_hint, visits, visits * SearchBenchmark.positions.size))
                if (benchmark.running) {
                    LinearProgressIndicator(progress = { benchmark.completedVisits.toFloat() / benchmark.totalVisits },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("benchmark-progress"))
                    Text(when (benchmark.phase) {
                        BenchmarkPhase.STARTING -> resources.getString(R.string.benchmark_loading, benchmark.config.model.id)
                        BenchmarkPhase.WARMING -> resources.getString(R.string.benchmark_warming, SearchBenchmark.WARMUP_VISITS)
                        else -> "${benchmarkCaseLabel(resources, benchmark.caseLabel)} · ${benchmark.completedVisits} / ${benchmark.totalVisits} visits"
                    }, fontSize = 12.sp, color = GoColors.Primary, modifier = Modifier.testTag("benchmark-status"))
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().testTag("cancel-benchmark")) { Text(resources.getString(R.string.cancel_benchmark)) }
                } else {
                    Button(onClick = { onStart(visits) }, enabled = config.backend != EngineBackend.NPU || supportsNpu,
                        modifier = Modifier.fillMaxWidth().testTag("start-benchmark")) { Text(if (selectedResult == null) resources.getString(R.string.run_benchmark) else resources.getString(R.string.rerun_benchmark)) }
                    if (benchmark.phase == BenchmarkPhase.CANCELLED) Hint(resources.getString(R.string.benchmark_cancelled))
                    if (benchmark.phase == BenchmarkPhase.ERROR) Text(resources.getString(R.string.benchmark_failed, benchmark.error ?: ""), color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp, modifier = Modifier.testTag("benchmark-error"))
                }
                selectedResult?.let { result ->
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = GoColors.Outline)
                    Text("${decimal(result.visitsPerSecond)} visits/s", fontSize = 28.sp, color = GoColors.Primary,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("benchmark-rate"))
                    Hint(resources.getString(R.string.benchmark_completed, SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(result.timestamp)), result.config.displayLabel(resources)))
                    Hint(resources.getString(R.string.benchmark_search_time, result.visits, decimal(result.searchNanos / 1e9, 2)))
                    Hint(resources.getString(R.string.benchmark_setup_time, decimal(result.initializationNanos / 1e6), decimal(result.warmupNanos / 1e6)))
                    result.samples.forEach { sample ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(benchmarkCaseLabel(resources, sample.label), fontSize = 12.sp, color = GoColors.Muted)
                            Text("${decimal(sample.visitsPerSecond)} visits/s", fontSize = 12.sp, color = GoColors.Ink)
                        }
                    }
                    Hint(result.device)
                }
                Hint(resources.getString(R.string.benchmark_method))
                Hint(resources.getString(R.string.benchmark_caveat))
            }
            TextButton(onClick = onDiagnostics, enabled = !benchmark.running, modifier = Modifier.fillMaxWidth().testTag("engine-diagnostics")) {
                Text(resources.getString(R.string.model_validation))
            }
        }
    }
}

private fun decimal(value: Double, digits: Int = 1) = String.format(Locale.ROOT, "%.${digits}f", value)

internal fun EngineConfig.displayLabel(resources: Resources) = "${model.id} · ${resources.getString(backend.labelRes)}"

internal fun benchmarkCaseLabel(resources: Resources, label: String) = resources.getString(when (label) {
    "empty" -> R.string.case_empty
    "opening" -> R.string.case_opening
    "capture" -> R.string.case_capture
    else -> R.string.applying
})
