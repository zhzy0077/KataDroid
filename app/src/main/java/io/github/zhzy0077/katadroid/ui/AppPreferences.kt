package io.github.zhzy0077.katadroid.ui

import android.content.Context
import androidx.core.content.edit
import io.github.zhzy0077.katadroid.engine.GoRules
import io.github.zhzy0077.katadroid.engine.MAX_VISITS
import io.github.zhzy0077.katadroid.engine.validKomi
import io.github.zhzy0077.katadroid.engine.EngineBackend
import io.github.zhzy0077.katadroid.engine.EngineConfig
import io.github.zhzy0077.katadroid.engine.KataGoModel

data class AppPreferences(
    val coordinates: Boolean = true,
    val candidates: Boolean = true,
    val engineEnabled: Boolean = true,
    val maxVisits: Int = 500,
    val touchOffsetPx: Int = 0,
    val autoBlack: Boolean = false,
    val autoWhite: Boolean = false,
    val rules: GoRules = GoRules.CHINESE,
    val komi: Float = 7.5f,
    val model: KataGoModel = KataGoModel.B6,
    val backend: EngineBackend = EngineBackend.AUTO,
) {
    val engineConfig get() = EngineConfig(model, backend)
    fun automatic(color: Int) = if (color == 1) autoBlack else autoWhite
    val hasAutomaticPlayer get() = autoBlack || autoWhite
    val showCandidateMoves get() = candidates && !hasAutomaticPlayer

    fun save(context: Context) = context.getSharedPreferences("board_preferences", Context.MODE_PRIVATE).edit {
        putBoolean("coordinates", coordinates); putBoolean("candidates", candidates)
        putBoolean("engine_enabled", engineEnabled)
        putInt("max_visits", maxVisits); putInt("touch_offset_px", touchOffsetPx)
        putBoolean("auto_black", autoBlack); putBoolean("auto_white", autoWhite)
        putString("rules", rules.id); putFloat("komi", komi)
        putString("model", model.id); putString("backend", backend.name)
    }

    companion object {
        fun load(context: Context): AppPreferences {
            val p = context.getSharedPreferences("board_preferences", Context.MODE_PRIVATE)
            return AppPreferences(p.getBoolean("coordinates", true), p.getBoolean("candidates", true),
                p.getBoolean("engine_enabled", true),
                p.getInt("max_visits", 500).coerceIn(1, MAX_VISITS), p.getInt("touch_offset_px", 0).coerceIn(0, 300),
                p.getBoolean("auto_black", false), p.getBoolean("auto_white", false),
                runCatching { GoRules.fromId(p.getString("rules", "chinese")!!) }.getOrDefault(GoRules.CHINESE),
                p.getFloat("komi", 7.5f).takeIf(::validKomi) ?: 7.5f,
                KataGoModel.fromId(p.getString("model", null)), EngineBackend.fromId(p.getString("backend", null)))
        }
    }
}
