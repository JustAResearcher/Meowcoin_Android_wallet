package com.meowcoin.wallet.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CoinProfileTest {
    private val privateKeyOne = "1".padStart(64, '0')
    private val mnemonic =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Test
    fun phaseOneRegistryContainsAllApprovedProfilesAndOnlyLiveCoinsAreEnabled() {
        assertEquals(
            listOf("mewc", "btc", "ltc", "doge", "dgb", "via", "pep", "jkc"),
            CoinRegistry.all.map { it.id }
        )
        assertEquals(
            setOf("mewc", "btc", "ltc", "doge", "pep"),
            CoinRegistry.enabled.map { it.id }.toSet()
        )
        assertEquals(CoinRegistry.PEP, CoinRegistry.findByTicker("PEPE"))
        assertTrue(CoinRegistry.enabled.all { it.electrumServers.isNotEmpty() })
        assertFalse(CoinRegistry.DGB.enabled)
        assertFalse(CoinRegistry.VIA.enabled)
        assertFalse(CoinRegistry.JKC.enabled)
        assertThrows(IllegalArgumentException::class.java) {
            CoinRegistry.requireEnabled("jkc")
        }
    }

    @Test
    fun keysUseTheSelectedProfilesAddressAndWifVersions() {
        for (profile in CoinRegistry.all) {
            val key = MeowcoinKeyPair.fromPrivateKey(privateKeyOne, profile)
            val (addressVersion, _) = Base58.decodeChecked(key.toAddress())
            val (wifVersion, _) = Base58.decodeChecked(key.toWIF())

            assertEquals(profile.pubKeyAddressVersion, addressVersion)
            assertEquals(profile.wifVersion, wifVersion)
            assertEquals(privateKeyOne, MeowcoinKeyPair.fromWIF(key.toWIF(), profile).privateKeyHex())
        }
    }

    @Test
    fun uncompressedWifRoundTripsWithoutChangingItsAddress() {
        val wif = Base58.encodeChecked(CoinRegistry.BTC.wifVersion, privateKeyOne.hexToBytes())
        val key = MeowcoinKeyPair.fromWIF(wif, CoinRegistry.BTC)

        assertFalse(key.isCompressed)
        assertEquals(wif, key.toWIF())
        assertEquals(
            MeowcoinAddress.fromPublicKey(key.uncompressedPublicKey(), CoinRegistry.BTC),
            key.toAddress()
        )
    }

    @Test
    fun addressValidationIsCoinAwareWherePrefixesDiffer() {
        val bitcoinAddress = MeowcoinKeyPair.fromPrivateKey(privateKeyOne, CoinRegistry.BTC).toAddress()

        assertTrue(MeowcoinAddress.isValid(bitcoinAddress, CoinRegistry.BTC))
        assertFalse(MeowcoinAddress.isValid(bitcoinAddress, CoinRegistry.LTC))
        assertThrows(IllegalArgumentException::class.java) {
            MeowcoinKeyPair.fromWIF(
                MeowcoinKeyPair.fromPrivateKey(privateKeyOne, CoinRegistry.BTC).toWIF(),
                CoinRegistry.LTC
            )
        }
    }

    @Test
    fun sharedLegacyPrefixesAreNotClaimedAsCrossChainIsolation() {
        val dogeAddress = MeowcoinKeyPair.fromPrivateKey(privateKeyOne, CoinRegistry.DOGE).toAddress()
        val dgbAddress = MeowcoinKeyPair.fromPrivateKey(privateKeyOne, CoinRegistry.DGB).toAddress()

        assertEquals(dogeAddress, dgbAddress)
        assertTrue(MeowcoinAddress.isValid(dogeAddress, CoinRegistry.DGB))
    }

    @Test
    fun bip44CoinTypeIsolatesAddressesEvenWhenLegacyPrefixesCollide() {
        val dogeWallet = HdWallet.fromMnemonic(mnemonic, CoinRegistry.DOGE)
        val dgbWallet = HdWallet.fromMnemonic(mnemonic, CoinRegistry.DGB)

        assertEquals("m/44'/3'/0'/0/0", Bip32.bip44Path(CoinRegistry.DOGE))
        assertEquals("m/44'/20'/0'/0/0", Bip32.bip44Path(CoinRegistry.DGB))
        assertNotEquals(dogeWallet.deriveReceivingAddress(0), dgbWallet.deriveReceivingAddress(0))
    }

    @Test
    fun bitcoinBip44VectorMatchesKnownFirstAddress() {
        val wallet = HdWallet.fromMnemonic(mnemonic, CoinRegistry.BTC)
        assertEquals("1LqBGSKuX5yYUonjxT5qGfpUsXKYYWeabA", wallet.deriveReceivingAddress(0))
    }
}
