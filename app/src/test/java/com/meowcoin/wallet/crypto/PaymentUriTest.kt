package com.meowcoin.wallet.crypto

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentUriTest {
    private val key = MeowcoinKeyPair.fromPrivateKey("2".padStart(64, '0'), CoinRegistry.BTC)
    private val address = key.toAddress()

    @Test
    fun buildsAndParsesExactAmountAndMetadata() {
        val uri = PaymentUriCodec.build(
            profile = CoinRegistry.BTC,
            address = address,
            amount = BigDecimal("0.29"),
            label = "Coffee & cats",
            message = "Thanks!"
        )

        assertTrue(uri.startsWith("bitcoin:$address?amount=0.29&"))
        val parsed = PaymentUriCodec.parse(uri)
        assertEquals(CoinRegistry.BTC, parsed.profile)
        assertEquals(29_000_000L, parsed.amountAtomic)
        assertEquals(BigDecimal("0.29000000"), parsed.amount)
        assertEquals("Coffee & cats", parsed.label)
        assertEquals("Thanks!", parsed.message)
    }

    @Test
    fun parsesThreeAtomicUnitsWithoutDoubleRounding() {
        val parsed = PaymentUriCodec.parse("bitcoin:$address?amount=0.00000003")
        assertEquals(3L, parsed.amountAtomic)
    }

    @Test
    fun rejectsWrongProfileAndFractionalAtomicUnits() {
        assertThrows(IllegalArgumentException::class.java) {
            PaymentUriCodec.parse("litecoin:$address?amount=1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PaymentUriCodec.parse("bitcoin:$address?amount=0.000000001")
        }
    }

    @Test
    fun rejectsUnknownRequiredParameters() {
        assertThrows(IllegalArgumentException::class.java) {
            PaymentUriCodec.parse("bitcoin:$address?req-extra=yes")
        }
    }

    @Test
    fun rejectsNonFixedPointAmountSyntax() {
        listOf("1e2", "1E-2", "+1", "%2B1", "-1").forEach { amount ->
            assertThrows(IllegalArgumentException::class.java) {
                PaymentUriCodec.parse("bitcoin:$address?amount=$amount")
            }
        }
    }

    @Test
    fun acceptsRawBase58AddressUsingTheSelectedCoin() {
        val ambiguousAddress = MeowcoinKeyPair.fromPrivateKey(
            "3".padStart(64, '0'),
            CoinRegistry.MEWC
        ).toAddress()

        assertEquals(
            MeowcoinAddress.Type.P2PKH,
            MeowcoinAddress.parse(ambiguousAddress, CoinRegistry.MEWC)?.type
        )
        assertEquals(
            MeowcoinAddress.Type.P2SH,
            MeowcoinAddress.parse(ambiguousAddress, CoinRegistry.LTC)?.type
        )
        for (profile in listOf(CoinRegistry.MEWC, CoinRegistry.LTC)) {
            val request = PaymentUriCodec.parseSendTarget("  $ambiguousAddress  ", profile)

            assertEquals(profile, request.profile)
            assertEquals(ambiguousAddress, request.address)
            assertEquals(PaymentRequestSource.RAW_ADDRESS, request.source)
        }
    }

    @Test
    fun sharedBase58PrefixUsesTheSelectedCoinsLockingScript() {
        val payloadHex = "00112233445566778899aabbccddeeff00112233"
        val sharedAddress = Base58.encodeChecked(50, payloadHex.hexToBytes())
        val mewcRequest = PaymentUriCodec.parseSendTarget(sharedAddress, CoinRegistry.MEWC)
        val ltcRequest = PaymentUriCodec.parseSendTarget(sharedAddress, CoinRegistry.LTC)

        assertEquals(
            "76a914${payloadHex}88ac",
            MeowcoinAddress.toScriptPubKey(mewcRequest.address, mewcRequest.profile).toHex()
        )
        assertEquals(
            "a914${payloadHex}87",
            MeowcoinAddress.toScriptPubKey(ltcRequest.address, ltcRequest.profile).toHex()
        )
    }

    @Test
    fun rejectsInvalidChecksumLengthAndOtherCoinsRawAddresses() {
        val mewcAddress = MeowcoinKeyPair.fromPrivateKey(
            "3".padStart(64, '0'), CoinRegistry.MEWC
        ).toAddress()
        val invalidChecksum = mewcAddress.dropLast(1) +
            if (mewcAddress.last() == '1') "2" else "1"
        val wrongLength = Base58.encodeChecked(50, ByteArray(19))
        val litecoinSegwit = MeowcoinKeyPair.fromPrivateKey(
            "3".padStart(64, '0'), CoinRegistry.LTC
        ).toP2WPKHAddress()

        for (input in listOf("", "  ", invalidChecksum, wrongLength, address, litecoinSegwit)) {
            assertThrows(IllegalArgumentException::class.java) {
                PaymentUriCodec.parseSendTarget(input, CoinRegistry.MEWC)
            }
        }
    }

    @Test
    fun acceptsRawMeowcoinScriptAndSegwitAddresses() {
        val scriptAddress = Base58.encodeChecked(122, ByteArray(20) { it.toByte() })
        val segwitAddress = MeowcoinKeyPair.fromPrivateKey(
            "3".padStart(64, '0'), CoinRegistry.MEWC
        ).toP2WPKHAddress()

        for (input in listOf(scriptAddress, segwitAddress)) {
            val request = PaymentUriCodec.parseSendTarget(input, CoinRegistry.MEWC)
            assertEquals(CoinRegistry.MEWC, request.profile)
            assertEquals(input, request.address)
            assertEquals(PaymentRequestSource.RAW_ADDRESS, request.source)
        }
    }

    @Test
    fun acceptsMatchingCoinUriAndPreservesItsProvenance() {
        val ambiguousAddress = MeowcoinKeyPair.fromPrivateKey(
            "4".padStart(64, '0'),
            CoinRegistry.MEWC
        ).toAddress()

        val mewcRequest = PaymentUriCodec.parseSendTarget(
            "meowcoin:$ambiguousAddress",
            CoinRegistry.MEWC
        )
        assertEquals(CoinRegistry.MEWC, mewcRequest.profile)
        assertEquals(PaymentRequestSource.URI, mewcRequest.source)
        assertEquals(ambiguousAddress, mewcRequest.address)

        val litecoinRequest = PaymentUriCodec.parseSendTarget(
            "litecoin:$ambiguousAddress",
            CoinRegistry.LTC
        )
        assertEquals(CoinRegistry.LTC, litecoinRequest.profile)
        assertEquals(PaymentRequestSource.URI, litecoinRequest.source)
        assertEquals(ambiguousAddress, litecoinRequest.address)
    }

    @Test
    fun rejectsPaymentUriForAnotherCoin() {
        val ambiguousAddress = MeowcoinKeyPair.fromPrivateKey(
            "5".padStart(64, '0'),
            CoinRegistry.MEWC
        ).toAddress()

        assertThrows(IllegalArgumentException::class.java) {
            PaymentUriCodec.parseSendTarget("meowcoin:$ambiguousAddress", CoinRegistry.LTC)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PaymentUriCodec.parseSendTarget("litecoin:$ambiguousAddress", CoinRegistry.MEWC)
        }
    }

    @Test
    fun acceptsUnambiguousRawAddressAndPreservesItsProvenance() {
        val request = PaymentUriCodec.parseSendTarget(address, CoinRegistry.BTC)

        assertEquals(CoinRegistry.BTC, request.profile)
        assertEquals(PaymentRequestSource.RAW_ADDRESS, request.source)
        assertEquals(address, request.address)
    }

    @Test
    fun allowsRawBase58CollisionWhenScriptMeaningIsTheSame() {
        val sharedP2shAddress = Base58.encodeChecked(5, ByteArray(20) { it.toByte() })

        assertEquals(
            MeowcoinAddress.Type.P2SH,
            MeowcoinAddress.parse(sharedP2shAddress, CoinRegistry.BTC)?.type
        )
        assertEquals(
            MeowcoinAddress.Type.P2SH,
            MeowcoinAddress.parse(sharedP2shAddress, CoinRegistry.LTC)?.type
        )
        val request = PaymentUriCodec.parseSendTarget(sharedP2shAddress, CoinRegistry.BTC)
        assertEquals(PaymentRequestSource.RAW_ADDRESS, request.source)
    }
}
