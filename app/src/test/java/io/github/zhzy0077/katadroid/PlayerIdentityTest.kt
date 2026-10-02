package io.github.zhzy0077.katadroid

import io.github.zhzy0077.katadroid.sgf.SgfCodec
import io.github.zhzy0077.katadroid.ui.record.PlayerIdentity
import org.junit.Assert.*
import org.junit.Test

class PlayerIdentityTest {
    @Test fun importedNamesAndRanksStayAssociatedWithTheirColors() {
        for (rank in listOf("5k", "3d", "9p")) {
            val properties = SgfCodec.parse("(;GM[1]FF[4]SZ[19]PB[Black Player]PW[白方姓名]BR[$rank]WR[1d];B[pd])").single().properties
            assertEquals(PlayerIdentity("Black Player", rank), PlayerIdentity.from(properties, true))
            assertEquals(PlayerIdentity("白方姓名", "1d"), PlayerIdentity.from(properties, false))
        }
    }
    @Test fun missingOrBlankMetadataAllowsLocalizedColorFallbackWithoutInventingRanks() {
        assertEquals(PlayerIdentity(null, null), PlayerIdentity.from(emptyMap(), true))
        assertEquals(PlayerIdentity(null, "2k"), PlayerIdentity.from(mapOf("PW" to listOf("  "), "WR" to listOf(" 2k ")), false))
    }
}
