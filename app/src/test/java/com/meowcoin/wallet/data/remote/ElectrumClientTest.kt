package com.meowcoin.wallet.data.remote

import com.meowcoin.wallet.crypto.CoinRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ElectrumClientTest {
    @Test
    fun `genesis hash uses double sha256 and display byte order`() {
        val bitcoinGenesisHeader =
            "01000000" +
                "0000000000000000000000000000000000000000000000000000000000000000" +
                "3ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4a" +
                "29ab5f49" +
                "ffff001d" +
                "1dac2b7c"

        assertEquals(
            "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f",
            ElectrumClient.genesisHashFromHeader(bitcoinGenesisHeader)
        )
    }

    @Test
    fun `genesis hash accepts uppercase hexadecimal`() {
        val bitcoinGenesisHeader =
            "0100000000000000000000000000000000000000000000000000000000000000" +
                "000000003BA3EDFD7A7B12B27AC72C3E67768F617FC81BC3888A51323A9FB8AA4B1E5E4A" +
                "29AB5F49FFFF001D1DAC2B7C"

        assertEquals(
            "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f",
            ElectrumClient.genesisHashFromHeader(bitcoinGenesisHeader)
        )
    }

    @Test
    fun `genesis hash rejects a non-header payload`() {
        assertThrows(IllegalArgumentException::class.java) {
            ElectrumClient.genesisHashFromHeader("00")
        }
    }

    @Test
    fun `genesis hash rejects non-hexadecimal header bytes`() {
        assertThrows(IllegalArgumentException::class.java) {
            ElectrumClient.genesisHashFromHeader("z".repeat(160))
        }
    }

    @Test
    fun `MEWC genesis pins raw header because its official id is not double sha256`() {
        val mewcGenesisHeader =
            "0400000000000000000000000000000000000000000000000000000000000000" +
                "0000000090739a9ddd9c782daf939db397a9d21f74a960fea5c398d533842c59f66c91e8" +
                "1b000c63ffff001e565d0500"

        assertTrue(
            ElectrumClient.genesisHeaderMatchesProfile(CoinRegistry.MEWC, mewcGenesisHeader)
        )
        assertNotEquals(
            CoinRegistry.MEWC.genesisHash,
            ElectrumClient.genesisHashFromHeader(mewcGenesisHeader)
        )
    }

    @Test
    fun `MEWC genesis rejects a changed raw header`() {
        val changedHeader =
            "0400000000000000000000000000000000000000000000000000000000000000" +
                "0000000090739a9ddd9c782daf939db397a9d21f74a960fea5c398d533842c59f66c91e8" +
                "1b000c63ffff001e565d0501"

        assertFalse(
            ElectrumClient.genesisHeaderMatchesProfile(CoinRegistry.MEWC, changedHeader)
        )
    }
}
