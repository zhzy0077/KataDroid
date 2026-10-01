package com.example.katadroid.engine

import android.util.Log
import com.google.ai.edge.litert.Accelerator
import kotlinx.coroutines.CancellationException

/** No device allowlist: the packaged runtime and model compilation determine support. */
internal fun openSelectedSession(
    config: EngineConfig,
    npuRuntimePackaged: Boolean,
    open: (Accelerator) -> AnalysisSession,
): AnalysisSession {
    if (config.backend != EngineBackend.AUTO) return open(config.accelerator)
    if (npuRuntimePackaged) {
        try {
            return open(Accelerator.NPU)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w("KataDroidEngine", "NPU initialization unavailable; AUTO is using CPU", error)
        } catch (error: LinkageError) {
            Log.w("KataDroidEngine", "NPU runtime could not load; AUTO is using CPU", error)
        }
    }
    return open(Accelerator.CPU)
}
