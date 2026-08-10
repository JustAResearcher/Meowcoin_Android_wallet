package com.meowcoin.wallet.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RawTransactionTest {
    @Test
    fun parsesTheBitcoinGenesisLegacyTransaction() {
        val transaction = RawTransactionParser.parse(GENESIS_TRANSACTION)

        assertEquals("4a5e1e4baab89f3a32518a88c31bc87f618f76673e2cc77ab2127b7afdeda33b", transaction.txId)
        assertEquals(transaction.txId, transaction.witnessTxId)
        assertFalse(transaction.hasWitness)
        assertEquals(1, transaction.version)
        assertEquals(1, transaction.inputs.size)
        assertTrue(transaction.inputs.single().isCoinbase)
        assertEquals(1, transaction.outputs.size)
        assertEquals(5_000_000_000L, transaction.outputAt(0).value)
        assertEquals(GENESIS_OUTPUT_SCRIPT, transaction.outputAt(0).scriptPubKeyHex)
        assertEquals(GENESIS_TRANSACTION.length / 2, transaction.serializedSize)
        assertEquals(transaction.serializedSize, transaction.strippedSize)
    }

    @Test
    fun parsesSegwitAndComputesTheCanonicalNonWitnessTxid() {
        val transaction = RawTransactionParser.parse(SEGWIT_TRANSACTION)

        assertEquals("2862bc0c69d2af55da7284d1b16a7cddc03971b77e5a97939cca7631add83bf5", transaction.txId)
        assertEquals("651431f85e6e1ea3603d7e6a9e8e5966eab659fad5261882ae6232b845f35443", transaction.witnessTxId)
        assertTrue(transaction.hasWitness)
        assertEquals(1L, transaction.outputAt(0).value)
        assertArrayEquals(byteArrayOf(), transaction.outputAt(0).scriptPubKey)
        assertEquals(3, transaction.inputs.single().witness.size)
        assertEquals(SEGWIT_TRANSACTION.length / 2, transaction.serializedSize)
        assertTrue(transaction.strippedSize < transaction.serializedSize)
    }

    @Test
    fun witnessChangesDoNotChangeTheCanonicalTxid() {
        val original = RawTransactionParser.parse(SEGWIT_TRANSACTION)
        val changedWitness = SEGWIT_TRANSACTION.replaceFirst("487fb382", "497fb382")
        val changed = RawTransactionParser.parse(changedWitness)

        assertEquals(original.txId, changed.txId)
        assertTrue(original.witnessTxId != changed.witnessTxId)
    }

    @Test
    fun verifiesTheServerTransactionAgainstTheExpectedOutpoint() {
        val transaction = RawTransactionParser.verifyOutput(
            transactionHex = GENESIS_TRANSACTION,
            expectedTxId = "4A5E1E4BAAB89F3A32518A88C31BC87F618F76673E2CC77AB2127B7AFDEDA33B",
            outputIndex = 0,
            expectedValue = 5_000_000_000L,
            expectedScriptPubKeyHex = GENESIS_OUTPUT_SCRIPT
        )

        assertEquals(5_000_000_000L, transaction.outputAt(0).value)
    }

    @Test
    fun rejectsAnyOutpointMismatch() {
        val txid = "4a5e1e4baab89f3a32518a88c31bc87f618f76673e2cc77ab2127b7afdeda33b"

        assertThrows(IllegalArgumentException::class.java) {
            RawTransactionParser.verifyOutput(
                GENESIS_TRANSACTION,
                "0".repeat(64),
                0,
                5_000_000_000L,
                GENESIS_OUTPUT_SCRIPT
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RawTransactionParser.verifyOutput(
                GENESIS_TRANSACTION,
                txid,
                0,
                4_999_999_999L,
                GENESIS_OUTPUT_SCRIPT
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RawTransactionParser.verifyOutput(
                GENESIS_TRANSACTION,
                txid,
                0,
                5_000_000_000L,
                "00"
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RawTransactionParser.verifyOutput(
                GENESIS_TRANSACTION,
                txid,
                1,
                5_000_000_000L,
                GENESIS_OUTPUT_SCRIPT
            )
        }
    }

    @Test
    fun rejectsMalformedAndNonCanonicalTransactions() {
        val nonCanonicalInputCount =
            GENESIS_TRANSACTION.take(8) + "fd0100" + GENESIS_TRANSACTION.drop(10)
        val unknownWitnessFlag = SEGWIT_TRANSACTION.replaceRange(10, 12, "02")

        listOf(
            GENESIS_TRANSACTION + "00",
            GENESIS_TRANSACTION.dropLast(2),
            nonCanonicalInputCount,
            unknownWitnessFlag,
            " $GENESIS_TRANSACTION",
            "not hex"
        ).forEach { rawTransaction ->
            assertThrows(IllegalArgumentException::class.java) {
                RawTransactionParser.parse(rawTransaction)
            }
        }
    }

    @Test
    fun rejectsLitecoinMwebSerializationWithAnExplicitError() {
        val mwebFlag = SEGWIT_TRANSACTION.replaceRange(10, 12, "08")

        val error = assertThrows(IllegalArgumentException::class.java) {
            RawTransactionParser.parse(mwebFlag)
        }

        assertTrue(error.message.orEmpty().contains("MWEB"))
    }

    companion object {
        private const val GENESIS_TRANSACTION =
            "01000000010000000000000000000000000000000000000000000000000000000000000000" +
                "ffffffff4d04ffff001d0104455468652054696d65732030332f4a616e2f323030392043" +
                "68616e63656c6c6f72206f6e206272696e6b206f66207365636f6e64206261696c6f" +
                "757420666f722062616e6b73ffffffff0100f2052a01000000434104678afdb0fe5548" +
                "271967f1a67130b7105cd6a828e03909a67962e0ea1f61deb649f6bc3f4cef38c4f3" +
                "5504e51ec112de5c384df7ba0b8d578a4c702b6bf11d5fac00000000"

        private const val GENESIS_OUTPUT_SCRIPT =
            "4104678afdb0fe5548271967f1a67130b7105cd6a828e03909a67962e0ea1f61deb" +
                "649f6bc3f4cef38c4f35504e51ec112de5c384df7ba0b8d578a4c702b6bf11d5fac"

        private const val SEGWIT_TRANSACTION =
            "0100000000010169c12106097dc2e0526493ef67f21269fe888ef05c7a3a5dacab38" +
                "e1ac8387f14c1d000000ffffffff01010000000000000000034830450220487fb382" +
                "c4974de3f7d834c1b617fe15860828c7f96454490edd6d891556dcc9022100baf95f" +
                "eb48f845d5bfc9882eb6aeefa1bc3790e39f59eaa46ff7f15ae626c53e012102a978" +
                "1d66b61fb5a7ef00ac5ad5bc6ffc78be7b44a566e3c87870e1079368df4c4aad48" +
                "30450220487fb382c4974de3f7d834c1b617fe15860828c7f96454490edd6d891556" +
                "dcc9022100baf95feb48f845d5bfc9882eb6aeefa1bc3790e39f59eaa46ff7f15ae6" +
                "26c53e0100000000"
    }
}
