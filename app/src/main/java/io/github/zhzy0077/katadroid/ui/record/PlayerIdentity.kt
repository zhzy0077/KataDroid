package io.github.zhzy0077.katadroid.ui.record

internal data class PlayerIdentity(val name: String?, val rank: String?) {
    companion object {
        fun from(properties: Map<String, List<String>>, black: Boolean): PlayerIdentity {
            fun value(key: String) = properties[key]?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            return PlayerIdentity(value(if (black) "PB" else "PW"), value(if (black) "BR" else "WR"))
        }
    }
}
