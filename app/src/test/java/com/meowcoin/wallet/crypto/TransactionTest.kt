package com.meowcoin.wallet.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
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
        assertEquals(inputs.map { it.outPoint }, transaction.selectedOutpoints)
        assertEquals(inputs.sumOf { it.value } - readSingleOutputValue(transaction.txHex), transaction.actualFee)
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

    @Test
    fun returnsOnlyTheOutpointsActuallySelected() {
        val large = utxo(1, 1_000_000L)
        val small = utxo(2, 500_000L)

        val transaction = MeowcoinTransaction.buildTransaction(
            keyPair = keyPair,
            utxos = listOf(small, large),
            outputs = listOf(MeowcoinTransaction.TxOutput(address, 600_000L)),
            changeAddress = address,
            feeRate = 1L
        )

        assertEquals(listOf(large.outPoint), transaction.selectedOutpoints)
        assertEquals(227L, transaction.actualFee)
    }

    @Test
    fun moreThanFiveHundredAvailableUtxosAreAllowedWhenSelectionNeedsOnlyOne() {
        val large = utxo(1, 1_000_000L)
        val small = (2..MeowcoinTransaction.MAX_TX_INPUTS + 1).map { utxo(it, 1L) }

        val transaction = MeowcoinTransaction.buildTransaction(
            keyPair = keyPair,
            utxos = small + large,
            outputs = listOf(MeowcoinTransaction.TxOutput(address, 600_000L)),
            changeAddress = address,
            feeRate = 1L
        )

        assertEquals(listOf(large.outPoint), transaction.selectedOutpoints)
    }

    @Test
    fun sendAllSubtractsTheFeeAndCreatesNoChangeOutput() {
        val input = utxo(7, 1_000_000L)

        val transaction = MeowcoinTransaction.buildSendAllTransaction(
            keyPair = keyPair,
            utxos = listOf(input),
            destinationAddress = address,
            feeRate = 1L
        )

        assertEquals(listOf(input.outPoint), transaction.selectedOutpoints)
        assertEquals(193L, transaction.actualFee)
        assertEquals(1, readOutputCount(transaction.txHex))
        assertEquals(input.value - transaction.actualFee, readSingleOutputValue(transaction.txHex))
    }

    @Test
    fun eachSelectedInputIsSignedByItsOwningKey() {
        val firstKey = fixedKey(11)
        val secondKey = fixedKey(12)
        val destination = fixedKey(13).toAddress()
        val first = ownedUtxo(11, 70_000L, firstKey)
        val second = ownedUtxo(12, 60_000L, secondKey)

        val transaction = MeowcoinTransaction.buildTransaction(
            spendableUtxos = listOf(second, first),
            outputs = listOf(MeowcoinTransaction.TxOutput(destination, 129_000L)),
            changeAddress = destination,
            feeRate = 1L
        )

        assertEquals(listOf(first.utxo.outPoint, second.utxo.outPoint), transaction.selectedOutpoints)
        val parsed = parseSignedTransaction(transaction.txHex)
        val unsigned = parsed.toUnsignedTransaction()
        val owners = listOf(first, second)

        parsed.inputs.forEachIndexed { index, input ->
            val (signature, publicKey) = parseScriptSignature(input.scriptSignature)
            val owner = owners[index]
            assertArrayEquals(owner.ownerKey.compressedPublicKey(), publicKey)
            val signatureHash = createSignatureHash(
                unsigned,
                index,
                owner.utxo.scriptPubKey.hexToBytes()
            )
            assertTrue(owner.ownerKey.verify(signatureHash, signature))
        }
    }

    @Test
    fun rejectsAnOwnerKeyThatDoesNotMatchTheUtxoScript() {
        val actualOwner = fixedKey(20)
        val wrongOwner = fixedKey(21)
        val spendable = ownedUtxo(20, 1_000_000L, actualOwner)

        assertThrows(IllegalArgumentException::class.java) {
            MeowcoinTransaction.buildSendAllTransaction(
                spendableUtxos = listOf(spendable.copy(ownerKey = wrongOwner)),
                destinationAddress = actualOwner.toAddress(),
                feeRate = 1L
            )
        }
    }

    @Test
    fun transactionVersionComesFromTheCoinProfile() {
        val dogeKey = fixedKey(30, CoinRegistry.DOGE)
        val spendable = ownedUtxo(30, 100_000_000L, dogeKey)

        val transaction = MeowcoinTransaction.buildSendAllTransaction(
            spendableUtxos = listOf(spendable),
            destinationAddress = dogeKey.toAddress(),
            profile = CoinRegistry.DOGE,
            feeRate = 1L
        )

        val version = ByteBuffer.wrap(transaction.txHex.hexToBytes(), 0, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
        assertEquals(CoinRegistry.DOGE.transactionVersion, version)
    }

    @Test
    fun singleKeyCompatibilityApiUsesTheKeysProfileFeeRate() {
        val btcKey = fixedKey(32, CoinRegistry.BTC)
        val script = MeowcoinAddress.toScriptPubKey(btcKey.toAddress(), CoinRegistry.BTC).toHex()
        val input = MeowcoinTransaction.UTXO(
            txHash = "32".padStart(64, '0'),
            outputIndex = 0,
            value = 1_000_000L,
            scriptPubKey = script
        )

        val transaction = MeowcoinTransaction.buildSendAllTransaction(
            keyPair = btcKey,
            utxos = listOf(input),
            destinationAddress = btcKey.toAddress()
        )

        assertEquals(193L, transaction.actualFee)
    }

    @Test
    fun uncompressedWifOwnersUseTheMatchingPublicKeyAndLargerFeeEstimate() {
        val owner = MeowcoinKeyPair.fromPrivateKey(
            "31".padStart(64, '0'),
            CoinRegistry.MEWC,
            compressed = false
        )
        val spendable = ownedUtxo(31, 1_000_000L, owner)

        val transaction = MeowcoinTransaction.buildSendAllTransaction(
            spendableUtxos = listOf(spendable),
            destinationAddress = owner.toAddress(),
            feeRate = 1L
        )

        val (_, publicKey) = parseScriptSignature(
            parseSignedTransaction(transaction.txHex).inputs.single().scriptSignature
        )
        assertArrayEquals(owner.uncompressedPublicKey(), publicKey)
        assertEquals(225L, transaction.actualFee)
    }

    private fun utxo(index: Int, value: Long) = MeowcoinTransaction.UTXO(
        txHash = index.toString(16).padStart(64, '0'),
        outputIndex = 0,
        value = value,
        scriptPubKey = scriptPubKey
    )

    private fun fixedKey(value: Int, profile: CoinProfile = CoinRegistry.MEWC): MeowcoinKeyPair =
        MeowcoinKeyPair.fromPrivateKey(value.toString(16).padStart(64, '0'), profile)

    private fun ownedUtxo(
        index: Int,
        value: Long,
        owner: MeowcoinKeyPair
    ): MeowcoinTransaction.SpendableUTXO {
        val script = MeowcoinAddress.toScriptPubKey(owner.toAddress(), owner.profile).toHex()
        return MeowcoinTransaction.SpendableUTXO(
            utxo = MeowcoinTransaction.UTXO(
                txHash = index.toString(16).padStart(64, '0'),
                outputIndex = 0,
                value = value,
                scriptPubKey = script
            ),
            ownerKey = owner
        )
    }

    private data class ParsedInput(
        val outpoint: ByteArray,
        val scriptSignature: ByteArray,
        val sequence: ByteArray
    )

    private data class ParsedTransaction(
        val version: ByteArray,
        val inputs: List<ParsedInput>,
        val outputsAndLockTime: ByteArray
    ) {
        fun toUnsignedTransaction(): ByteArray = ByteArrayBuilder().apply {
            writeBytes(version)
            writeVarInt(inputs.size.toLong())
            inputs.forEach { input ->
                writeBytes(input.outpoint)
                writeVarInt(0)
                writeBytes(input.sequence)
            }
            writeBytes(outputsAndLockTime)
        }.toByteArray()
    }

    private fun parseSignedTransaction(txHex: String): ParsedTransaction {
        val reader = ByteArrayReader(txHex.hexToBytes())
        val version = reader.readBytes(4)
        val inputCount = reader.readVarInt().toInt()
        val inputs = (0 until inputCount).map {
            val outpoint = reader.readBytes(36)
            val script = reader.readBytes(reader.readVarInt().toInt())
            val sequence = reader.readBytes(4)
            ParsedInput(outpoint, script, sequence)
        }
        return ParsedTransaction(version, inputs, reader.readRemaining())
    }

    private fun parseScriptSignature(script: ByteArray): Pair<ByteArray, ByteArray> {
        val reader = ByteArrayReader(script)
        val signatureWithHashType = reader.readBytes(reader.readVarInt().toInt())
        val publicKey = reader.readBytes(reader.readVarInt().toInt())
        assertEquals(script.size, reader.position)
        assertEquals(1, signatureWithHashType.last().toInt())
        return signatureWithHashType.copyOf(signatureWithHashType.size - 1) to publicKey
    }

    private fun createSignatureHash(
        unsignedTransaction: ByteArray,
        signingIndex: Int,
        subscript: ByteArray
    ): ByteArray {
        val source = ByteArrayReader(unsignedTransaction)
        val target = ByteArrayBuilder()
        target.writeBytes(source.readBytes(4))
        val inputCount = source.readVarInt()
        target.writeVarInt(inputCount)
        repeat(inputCount.toInt()) { index ->
            target.writeBytes(source.readBytes(36))
            source.readBytes(source.readVarInt().toInt())
            if (index == signingIndex) {
                target.writeVarInt(subscript.size.toLong())
                target.writeBytes(subscript)
            } else {
                target.writeVarInt(0)
            }
            target.writeBytes(source.readBytes(4))
        }
        target.writeBytes(source.readRemaining())
        target.writeInt32LE(1)
        return doubleSha256(target.toByteArray())
    }

    private fun readOutputCount(txHex: String): Int {
        val parsed = parseSignedTransaction(txHex)
        return ByteArrayReader(parsed.outputsAndLockTime).readVarInt().toInt()
    }

    private fun readSingleOutputValue(txHex: String): Long {
        val parsed = parseSignedTransaction(txHex)
        val reader = ByteArrayReader(parsed.outputsAndLockTime)
        assertEquals(1L, reader.readVarInt())
        return ByteBuffer.wrap(reader.readBytes(8)).order(ByteOrder.LITTLE_ENDIAN).long
    }

    private fun doubleSha256(data: ByteArray): ByteArray {
        val sha256 = MessageDigest.getInstance("SHA-256")
        return sha256.digest(sha256.digest(data))
    }
}
