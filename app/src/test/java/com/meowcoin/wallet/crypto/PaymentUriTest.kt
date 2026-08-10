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
    fun rejectsRawBase58AddressWithDifferentScriptMeanings() {
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
        assertThrows(IllegalArgumentException::class.java) {
            PaymentUriCodec.parseSendTarget(ambiguousAddress, CoinRegistry.MEWC)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PaymentUriCodec.parseSendTarget(ambiguousAddress, CoinRegistry.LTC)
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
