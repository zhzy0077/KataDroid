package com.example.katadroid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.katadroid.ui.PageHeader
import com.example.katadroid.engine.KataGoModel
import com.google.ai.edge.litert.Accelerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun DiagnosticsScreen(onBack: () -> Unit, model: KataGoModel = KataGoModel.B6) {
    val resources = LocalResources.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val supportsNpu = NpuProbe.hasRuntimeLibraries(context)
    var running by remember { mutableStateOf(false) }
    var report by rememberSaveable(model.id, resources.configuration.locales.toLanguageTags()) { mutableStateOf(resources.getString(R.string.validation_waiting)) }
    Scaffold(modifier = Modifier.fillMaxSize(), topBar = { PageHeader(resources.getString(R.string.diagnostics_title), onBack) }) { padding ->
        Column(Modifier.padding(padding).padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(resources.getString(R.string.local_inference), style = MaterialTheme.typography.headlineSmall)
            Text(NpuProbe.deviceDescription())
            Text(resources.getString(R.string.diagnostics_description, model.id, if (supportsNpu) "CPU / NPU" else "CPU"))
            if (!supportsNpu) Text(resources.getString(R.string.cpu_validation_only))
            Button(enabled = !running, onClick = {
                running = true
                report = resources.getString(R.string.katago_cpu_running)
                scope.launch {
                    try {
                        val cpu = KataGoProbe.run(context, Accelerator.CPU, model)
                        if (supportsNpu) {
                            report = cpu.display(context) + resources.getString(R.string.katago_npu_running)
                            val npu = KataGoProbe.run(context, Accelerator.NPU, model)
                            report = cpu.display(context) + "\n" + npu.display(context) +
                                resources.getString(R.string.katago_npu_done)
                        } else {
                            report = cpu.display(context) + resources.getString(R.string.katago_cpu_done)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        report += resources.getString(R.string.katago_validation_failed, e.message ?: "")
                    } catch (e: LinkageError) {
                        report += resources.getString(R.string.native_load_failed, e.message ?: "")
                    } finally {
                        running = false
                    }
                }
            }) { Text(if (running) resources.getString(R.string.validation_running) else resources.getString(R.string.validate_model)) }
            Button(enabled = !running, onClick = {
                running = true
                report = resources.getString(R.string.cpu_probe_running)
                scope.launch {
                    try {
                        val cpu = NpuProbe.run(context, Accelerator.CPU)
                        if (supportsNpu) {
                            report = cpu.display(context) + resources.getString(R.string.npu_probe_running)
                            val npu = NpuProbe.run(context, Accelerator.NPU)
                            report = cpu.display(context) + "\n\n" + npu.display(context) +
                                resources.getString(R.string.npu_probe_done)
                        } else {
                            report = cpu.display(context) + resources.getString(R.string.cpu_probe_done)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        report += resources.getString(R.string.validation_failed, e.message ?: "")
                    } catch (e: LinkageError) {
                        report += resources.getString(R.string.native_load_failed, e.message ?: "")
                    } finally {
                        running = false
                    }
                }
            }) { Text(resources.getString(R.string.conv_diagnostics)) }
            if (running) CircularProgressIndicator()
            Text(report)
        }
    }
}
