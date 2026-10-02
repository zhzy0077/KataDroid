package io.github.zhzy0077.katadroid.ui.record

import io.github.zhzy0077.katadroid.R
import android.app.Application
import android.util.AtomicFile
import android.util.Log
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.zhzy0077.katadroid.ui.AppPreferences
import io.github.zhzy0077.katadroid.engine.GoRules
import io.github.zhzy0077.katadroid.sgf.SgfCodec
import io.github.zhzy0077.katadroid.sgf.SgfGame
import io.github.zhzy0077.katadroid.sgf.SgfNode
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

/** Keep full SGF trees out of the Activity's size-limited saved-instance Bundle. */
class RecordViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application
    private val file = AtomicFile(File(application.filesDir, "current-record.json"))
    var preferences by mutableStateOf(AppPreferences.load(application))
        private set
    val record = runCatching { RecordUiState.restore(file.openRead().bufferedReader().use { it.readText() }) }
        .getOrElse { RecordUiState(preferences.rules, preferences.komi).apply { pauseAutomatic() } }
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var importChoices by mutableStateOf<List<SgfNode>>(emptyList())
        private set
    private var importName = context.getString(R.string.imported_record)
    private var exportSnapshot: String? = null

    fun updatePreferences(value: AppPreferences) {
        if (value.autoBlack != preferences.autoBlack || value.autoWhite != preferences.autoWhite) {
            if (value.hasAutomaticPlayer) record.resumeAutomatic() else record.pauseAutomatic()
        }
        preferences = value
        value.save(getApplication())
    }

    fun applySettings(value: AppPreferences, rules: GoRules, komi: Float) {
        if (busy) return
        val rulesChanged = record.rules != rules || record.komi != komi
        val game = record.gameWithRules(rules, komi)
        operation(context.getString(R.string.apply_failed)) {
            if (rulesChanged) withContext(Dispatchers.Default) { RecordUiState.validate(game) }
            // Do not partially commit the draft if validation fails.
            androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                if (rulesChanged) record.applyRules(rules, komi)
                // Engine selection can change on another page while a large record is validated.
                updatePreferences(value.copy(rules = rules, komi = komi, engineEnabled = preferences.engineEnabled,
                    model = preferences.model, backend = preferences.backend))
            }
        }
    }

    fun importSgf(uri: Uri) {
        if (busy) return
        record.pauseAutomatic()
        operation(context.getString(R.string.import_failed)) {
            val (name, roots) = withContext(Dispatchers.IO) {
                val resolver = getApplication<Application>().contentResolver
                val name = runCatching {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }
                }.getOrNull() ?: context.getString(R.string.imported_record)
                val bytes = checkNotNull(resolver.openInputStream(uri)) { context.getString(R.string.file_open_failed) }.use { it.readNBytes(SgfCodec.MAX_BYTES + 1) }
                name to SgfCodec.decode(bytes)
            }
            importName = name
            if (roots.size == 1) load(roots.single()) else importChoices = roots
        }
    }

    fun cancelImportChoice() { importChoices = emptyList() }
    fun chooseImport(index: Int) {
        val root = importChoices.getOrNull(index) ?: return
        importChoices = emptyList()
        operation(context.getString(R.string.import_failed)) { load(root) }
    }
    private suspend fun load(root: SgfNode) {
        val current = preferences
        val game = withContext(Dispatchers.Default) {
            SgfGame.from(root, current.rules, current.komi).also(RecordUiState::validate)
        }
        record.load(game, importName)
        updatePreferences(preferences.copy(rules = game.rules, komi = game.komi))
        message = context.getString(R.string.import_success, record.recordName, game.nodes.count { it.color != 0 })
    }

    fun prepareExport() { record.pauseAutomatic(); exportSnapshot = record.exportSgf() }
    fun cancelExport() { exportSnapshot = null }
    fun exportSgf(uri: Uri) {
        if (busy) return
        val snapshot = exportSnapshot ?: record.exportSgf()
        exportSnapshot = null
        operation(context.getString(R.string.export_failed)) {
            withContext(Dispatchers.IO) {
                val resolver = getApplication<Application>().contentResolver
                checkNotNull(resolver.openOutputStream(uri, "wt")) { context.getString(R.string.file_write_failed) }
                    .bufferedWriter(Charsets.UTF_8).use { it.write(snapshot) }
            }
            message = context.getString(R.string.export_success)
        }
    }

    private fun operation(failure: String, work: suspend () -> Unit) {
        busy = true
        viewModelScope.launch {
            try { work() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                message = context.getString(R.string.operation_failure, failure, error.message?.take(180) ?: context.getString(R.string.file_unreadable))
            } finally { busy = false }
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            // Save periodically even when fast self-play never becomes idle.
            snapshotFlow { record.serialize() }.conflate().collect { value ->
                var stream: java.io.FileOutputStream? = null
                try {
                    stream = file.startWrite()
                    stream.write(value.toByteArray(Charsets.UTF_8))
                    file.finishWrite(stream)
                } catch (error: Exception) {
                    file.failWrite(stream)
                    Log.w("KataDroidRecord", "Unable to save current record", error)
                }
                delay(250)
            }
        }
    }
}
