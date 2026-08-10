package com.meowcoin.wallet.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Bip39Test {
    private val mnemonic =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Test
    fun mnemonicWhitespaceFormsValidateAndDeriveTheSameSeed() {
        val doubleSpaced = mnemonic.replace(" ", "  ")
        val lineSeparated = mnemonic.split(' ').joinToString("\n\t")
        val unicodeSpaced = mnemonic.split(' ').joinToString("\u0085\u00a0\u2003")

        for (variant in listOf(mnemonic, doubleSpaced, lineSeparated, unicodeSpaced)) {
            assertTrue(Bip39.validateMnemonic(variant))
            assertEquals(mnemonic, Bip39.canonicalizeMnemonic(variant))
            assertEquals(Bip39.mnemonicToSeed(mnemonic).toHex(), Bip39.mnemonicToSeed(variant).toHex())
        }
    }

    @Test
    fun officialBip39PassphraseVectorMatches() {
        assertEquals(
            "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e5349553" +
                "1f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04",
            Bip39.mnemonicToSeed(mnemonic, "TREZOR").toHex()
        )
    }

    @Test
    fun passphraseUsesUnicodeNfkd() {
        val composed = "passphr\u00e9"
        val decomposed = "passphre\u0301"

        assertEquals(
            Bip39.mnemonicToSeed(mnemonic, decomposed).toHex(),
            Bip39.mnemonicToSeed(mnemonic, composed).toHex()
        )
    }

    @Test
    fun mnemonicUsesUnicodeNfkd() {
        val composed = "$mnemonic caf\u00e9"
        val decomposed = "$mnemonic cafe\u0301"

        assertEquals(
            Bip39.mnemonicToSeed(decomposed).toHex(),
            Bip39.mnemonicToSeed(composed).toHex()
        )
    }

    @Test
    fun legacyWhitespaceDerivationRemainsAvailableForExistingWallets() {
        val legacyStoredMnemonic = mnemonic.replaceFirst("abandon abandon", "abandon  abandon")

        assertNotEquals(
            Bip39.mnemonicToSeed(legacyStoredMnemonic).toHex(),
            Bip39.mnemonicToSeedLegacy(legacyStoredMnemonic).toHex()
        )

        val legacyAddress = HdWallet.fromMnemonic(
            legacyStoredMnemonic,
            CoinRegistry.MEWC,
            derivationVersion = Bip39.LEGACY_DERIVATION_VERSION
        ).deriveReceivingAddress(0)
        val canonicalAddress = HdWallet.fromMnemonic(
            legacyStoredMnemonic,
            CoinRegistry.MEWC,
            derivationVersion = Bip39.CANONICAL_DERIVATION_VERSION
        ).deriveReceivingAddress(0)

        assertNotEquals(canonicalAddress, legacyAddress)
        assertEquals(
            legacyAddress,
            HdWallet.fromMnemonic(
                legacyStoredMnemonic,
                CoinRegistry.MEWC,
                derivationVersion = Bip39.LEGACY_DERIVATION_VERSION
            ).deriveReceivingAddress(0)
        )
    }
}
