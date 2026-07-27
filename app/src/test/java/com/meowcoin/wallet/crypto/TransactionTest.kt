package com.meowcoin.wallet.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TransactionTest {
    private lateinit var keyPair: MeowcoinKeyPair
    private lateinit var address: String
    private lateinit var scriptPubKey: String

    @Before
    fun setUp() {
        keyPair = MeowcoinKeyPair.generate()
        address = keyPair.toAddress()
        scriptPubKey = MeowcoinAddress.toScriptPubKey(address)
            .joinToString("") { "%02x".format(it) }
    }

    @Test
    fun consolidationSpendsTheSuppliedBatch() {
        val inputs = (1..3).map { utxo(it, 100_000_000L) }

        val transaction = MeowcoinTransaction.buildConsolidationTransaction(
            keyPair = keyPair,
            utxos = inputs,
            destinationAddress = address
        )

        assertEquals(64, transaction.txId.length)
        assertEquals(transaction.size * 2, transaction.txHex.length)
        assertTrue(transaction.size > 400)
    }

    @Test
    fun consolidationRequiresAtLeastTwoInputs() {
        assertThrows(IllegalArgumentException::class.java) {
            MeowcoinTransaction.buildConsolidationTransaction(
                keyPair = keyPair,
                utxos = listOf(utxo(1, 100_000_000L)),
                destinationAddress = address
            )
        }
    }

    @Test
    fun consolidationRejectsAnUneconomicBatch() {
        assertThrows(IllegalArgumentException::class.java) {
            MeowcoinTransaction.buildConsolidationTransaction(
                keyPair = keyPair,
                utxos = listOf(utxo(1, 100_000L), utxo(2, 100_000L)),
                destinationAddress = address
            )
        }
    }

    @Test
    fun consolidationCapsInputCount() {
        val inputs = (1..MeowcoinTransaction.MAX_TX_INPUTS + 1)
            .map { utxo(it, 1_000_000L) }

        assertThrows(IllegalArgumentException::class.java) {
            MeowcoinTransaction.buildConsolidationTransaction(
                keyPair = keyPair,
                utxos = inputs,
                destinationAddress = address
            )
        }
    }

    private fun utxo(index: Int, value: Long) = MeowcoinTransaction.UTXO(
        txHash = index.toString(16).padStart(64, '0'),
        outputIndex = 0,
        value = value,
        scriptPubKey = scriptPubKey
    )
}
