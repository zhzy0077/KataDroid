package com.example.katadroid.ui

import androidx.compose.ui.platform.LocalResources
import com.example.katadroid.R
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.katadroid.DiagnosticsScreen
import com.example.katadroid.engine.EnginePhase
import com.example.katadroid.engine.KataGoViewModel
import com.example.katadroid.ui.record.RecordScreen
import com.example.katadroid.ui.record.RecordViewModel
import com.example.katadroid.ui.record.TreeViewportState
import com.example.katadroid.ui.theme.GoColors
import kotlinx.coroutines.delay

@Composable
fun KataDroidApp() {
    val resources = LocalResources.current
    val document: RecordViewModel = viewModel()
    val record = document.record
    val settings = document.preferences
    val viewport = rememberSaveable(saver = TreeViewportState.Saver) { TreeViewportState() }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var diagnosticsReturnPage by rememberSaveable { mutableIntStateOf(1) }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    val engine: KataGoViewModel = viewModel()
    val engineState by engine.controller.state.collectAsState()
    val benchmark by engine.controller.benchmark.collectAsState()
    val owner = LocalLifecycleOwner.current
    var foreground by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    val latestSettings by rememberUpdatedState(settings)
    val position = record.position
    val visible = foreground && page == 0 && !document.busy && !pickerOpen && document.importChoices.isEmpty()
    val autoTurn = settings.hasAutomaticPlayer && settings.automatic(position.nextPlayer) && record.autoPlayArmed && record.preview == null
    val snackbar = remember { SnackbarHostState() }

    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pickerOpen = false
        if (uri != null) { document.importSgf(uri); viewport.initialized = false; page = 0 }
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-go-sgf")) { uri ->
        pickerOpen = false
        if (uri != null) document.exportSgf(uri) else document.cancelExport()
    }
    fun suspendForPicker() {
        record.pauseAutomatic(); pickerOpen = true
        engine.controller.setTarget(record.position, settings.engineEnabled, visible = false, maxVisits = settings.maxVisits, config = settings.engineConfig)
    }
    val onImport: () -> Unit = {
        if (!document.busy) {
            suspendForPicker()
            importFile.launch(arrayOf("*/*"))
        }
    }
    val onExport: () -> Unit = {
        if (!document.busy) {
            suspendForPicker(); document.prepareExport()
            exportFile.launch("KataDroid.sgf")
        }
    }

    LaunchedEffect(position, settings.engineEnabled, settings.maxVisits, settings.engineConfig, visible, page, foreground) {
        engine.controller.setTarget(position, settings.engineEnabled, visible, maxVisits = settings.maxVisits, config = settings.engineConfig)
    }
    LaunchedEffect(autoTurn, visible, settings.engineEnabled, settings.maxVisits, settings.engineConfig, position, engineState.phase, engineState.completedAnalysis) {
        val result = engineState.completedAnalysis
        if (autoTurn && visible && settings.engineEnabled && engineState.phase == EnginePhase.READY &&
            engineState.config == settings.engineConfig && engineState.maxVisits == settings.maxVisits && result?.position == position && !result.gameFinished) {
            // Give navigation, stop and a newly selected position time to cancel this turn.
            delay(180)
            if (record.position == position && record.autoPlayArmed && latestSettings.engineEnabled && latestSettings.engineConfig == engineState.config) {
                val move = result.bestMove
                if (move == null || !record.play(move)) record.automaticFailed(R.string.auto_move_failed)
            }
        }
    }
    LaunchedEffect(document.message) {
        document.message?.let { snackbar.showSnackbar(it); document.message = null }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) foreground = true
            if (event == Lifecycle.Event.ON_STOP) {
                foreground = false
                engine.controller.setTarget(record.position, latestSettings.engineEnabled, visible = false, maxVisits = latestSettings.maxVisits, config = latestSettings.engineConfig)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    fun goBack() {
        when (page) { 3 -> page = 1; 2 -> page = diagnosticsReturnPage; 1 -> page = 0; else -> record.exitPreview() }
    }
    BackHandler(page != 0 || record.preview != null, onBack = ::goBack)

    Box(Modifier.fillMaxSize().background(GoColors.Background)
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        when (page) {
            1 -> SettingsScreen(settings, record.rules, record.komi, document.busy,
                onApply = document::applySettings,
                onBack = ::goBack, onDiagnostics = { diagnosticsReturnPage = 1; page = 2 }, onEngines = { page = 3 })
            2 -> DiagnosticsScreen(onBack = ::goBack, model = settings.model)
            3 -> EngineScreen(settings.engineConfig, benchmark,
                onConfig = { config ->
                    document.updatePreferences(settings.copy(model = config.model, backend = config.backend))
                    engine.controller.setTarget(position, settings.engineEnabled, visible = false, maxVisits = settings.maxVisits, config = config)
                }, onStart = { engine.controller.startBenchmark(settings.engineConfig, it) }, onCancel = engine.controller::cancelBenchmark,
                onDiagnostics = { diagnosticsReturnPage = 3; page = 2 }, onBack = ::goBack)
            else -> RecordScreen(record, viewport, settings, engineState,
                manualInput = !(autoTurn && settings.engineEnabled) && !document.busy,
                onEngineEnabled = {
                    document.updatePreferences(settings.copy(engineEnabled = it))
                    engine.controller.setTarget(record.position, it, visible, maxVisits = settings.maxVisits, config = settings.engineConfig)
                },
                onRetry = { engine.controller.setTarget(record.position, settings.engineEnabled, visible, retry = true, maxVisits = settings.maxVisits, config = settings.engineConfig) },
                onSettings = { page = 1 }, onImport = onImport, onExport = onExport,
                onNewGame = { record.newGame(); viewport.initialized = false },
                onResumeAutomatic = { record.resumeAutomatic(); document.updatePreferences(settings.copy(engineEnabled = true)) })
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = if (page == 1) 96.dp else 0.dp).navigationBarsPadding())
        if (document.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter).testTag("document-progress"))
    }
    if (document.importChoices.isNotEmpty()) {
        AlertDialog(onDismissRequest = document::cancelImportChoice, title = { Text(resources.getString(R.string.choose_game)) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    document.importChoices.forEachIndexed { index, root ->
                        val props = root.properties
                        val name = props["GN"]?.firstOrNull() ?: "${props["PB"]?.firstOrNull() ?: resources.getString(R.string.black)} / ${props["PW"]?.firstOrNull() ?: resources.getString(R.string.white)}"
                        TextButton(onClick = { document.chooseImport(index) }, modifier = Modifier.fillMaxWidth()) { Text("${index + 1}. $name") }
                    }
                }
            }, confirmButton = { TextButton(onClick = document::cancelImportChoice) { Text(resources.getString(R.string.cancel)) } })
    }
}
