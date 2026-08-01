package com.meowcoin.wallet.ashcats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AshCatsLinkTest {
    @Test
    fun `adds wallet source and public owner address`() {
        assertEquals(
            "https://www.mewccrypto.com/ash-cats/?source=android-wallet&owner=MCu8spT8vBqLw3x9mK7Y4pT2dN6aH1zF5R",
            buildAshCatsUrl(
                "https://www.mewccrypto.com/ash-cats/",
                "MCu8spT8vBqLw3x9mK7Y4pT2dN6aH1zF5R",
            ),
        )
    }

    @Test
    fun `preserves existing query and fragment`() {
        assertEquals(
            "https://example.com/forge?campaign=genesis&source=android-wallet&owner=MExample#forge",
            buildAshCatsUrl("https://example.com/forge?campaign=genesis#forge", "MExample"),
        )
    }

    @Test
    fun `rejects non-https launch targets`() {
        assertThrows(IllegalArgumentException::class.java) {
            buildAshCatsUrl("http://example.com/ash-cats/", "MExample")
        }
    }
}
