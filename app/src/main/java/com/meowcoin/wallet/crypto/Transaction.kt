package com.meowcoin.wallet.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Meowcoin transaction building.
 * Meowcoin uses a UTXO model identical to Bitcoin/Ravencoin.
 *
 * Transaction format:
 *   version (4 bytes LE)
 *   input count (varint)
 *   inputs[]
 *   output count (varint)
 *   outputs[]
 *   locktime (4 bytes LE)
 */
object MeowcoinTransaction {

    // Dust threshold in satoshis (0.001 MEWC)
    const val DUST_THRESHOLD = 100_000L

    // Default fee rate in sat/byte
    const val DEFAULT_FEE_RATE = 1000L

    data class UTXO(
        val txHash: String,     // Transaction hash (hex, 32 bytes)
        val outputIndex: Int,   // Output index in the transaction
        val value: Long,        // Value in satoshis
        val scriptPubKey: String // Locking script (hex)
    )

    data class TxOutput(
        val address: String,
        val value: Long         // Value in satoshis
    )

    data class SignedTransaction(
        val txHex: String,
        val txId: String,
        val size: Int
    )

    /**
     * Build and sign a transaction.
     *
     * @param keyPair The signing key pair
     * @param utxos Available UTXOs to spend
     * @param outputs Desired outputs (address + amount)
     * @param changeAddress Address to send change to
     * @param feeRate Fee rate in satoshis per byte
     * @return Signed transaction ready for broadcast
     */
    fun buildTransaction(
        keyPair: MeowcoinKeyPair,
        utxos: List<UTXO>,
        outputs: List<TxOutput>,
        changeAddress: String,
        feeRate: Long = DEFAULT_FEE_RATE
    ): SignedTransaction {
        require(utxos.isNotEmpty()) { "No UTXOs available" }
        require(outputs.isNotEmpty()) { "No outputs specified" }

        val totalOutput = outputs.sumOf { it.value }
        require(totalOutput > 0) { "Total output must be positive" }

        // Select UTXOs (simple greedy algorithm)
        val selectedUtxos = selectUtxos(utxos, totalOutput, feeRate)
        val totalInput = selectedUtxos.sumOf { it.value }

        // Estimate transaction size for fee calculation
        val estimatedSize = estimateSize(selectedUtxos.size, outputs.size + 1)
        val fee = estimatedSize * feeRate

        val change = totalInput - totalOutput - fee
        require(change >= 0) { "Insufficient funds. Need ${totalOutput + fee}, have $totalInput" }

        // Build output list with optional change
        val allOutputs = outputs.toMutableList()
        if (change > DUST_THRESHOLD) {
            allOutputs.add(TxOutput(changeAddress, change))
        }

        // Build unsigned transaction
        val rawTx = buildRawTransaction(selectedUtxos, allOutputs)

        // Sign each input
        val signedTx = signTransaction(rawTx, selectedUtxos, keyPair)

        val txId = doubleSha256(signedTx).reversedArray().toHex()

        return SignedTransaction(
            txHex = signedTx.toHex(),
            txId = txId,
            size = signedTx.size
        )
    }

    /**
     * Estimate the fee for a transaction in satoshis.
     */
    fun estimateFee(inputCount: Int, outputCount: Int, feeRate: Long = DEFAULT_FEE_RATE): Long {
        return estimateSize(inputCount, outputCount) * feeRate
    }

    private fun selectUtxos(utxos: List<UTXO>, targetAmount: Long, feeRate: Long): List<UTXO> {
        val sorted = utxos.sortedByDescending { it.value }
        val selected = mutableListOf<UTXO>()
        var total = 0L

        for (utxo in sorted) {
            selected.add(utxo)
            total += utxo.value
            val estFee = estimateSize(selected.size, 2) * feeRate
            if (total >= targetAmount + estFee) break
        }

        val finalFee = estimateSize(selected.size, 2) * feeRate
        require(total >= targetAmount + finalFee) {
            "Insufficient funds. Need ${targetAmount + finalFee}, have $total"
        }

        return selected
    }

    private fun estimateSize(inputs: Int, outputs: Int): Int {
        // P2PKH: ~148 bytes per input, 34 bytes per output, 10 bytes overhead
        return 10 + (inputs * 148) + (outputs * 34)
    }

    private fun buildRawTransaction(inputs: List<UTXO>, outputs: List<TxOutput>): ByteArray {
        val buffer = ByteArrayBuilder()

        // Version (2 = current Meowcoin version)
        buffer.writeInt32LE(2)

        // Input count
        buffer.writeVarInt(inputs.size.toLong())

        // Inputs
        for (input in inputs) {
            // Previous tx hash (reversed)
            buffer.writeBytes(input.txHash.hexToBytes().reversedArray())
            // Previous output index
            buffer.writeInt32LE(input.outputIndex)
            // ScriptSig (empty for unsigned)
            buffer.writeVarInt(0)
            // Sequence
            buffer.writeInt32LE(0xFFFFFFFF.toInt())
        }

        // Output count
        buffer.writeVarInt(outputs.size.toLong())

        // Outputs
        for (output in outputs) {
            // Value in satoshis
            buffer.writeInt64LE(output.value)
            // ScriptPubKey (P2PKH)
            val scriptPubKey = buildP2PKHScript(output.address)
            buffer.writeVarInt(scriptPubKey.size.toLong())
            buffer.writeBytes(scriptPubKey)
        }

        // Locktime
        buffer.writeInt32LE(0)

        return buffer.toByteArray()
    }

    private fun signTransaction(
        rawTx: ByteArray,
        inputs: List<UTXO>,
        keyPair: MeowcoinKeyPair
    ): ByteArray {
        val buffer = ByteArrayBuilder()

        // Version
        buffer.writeInt32LE(2)

        // Input count
        buffer.writeVarInt(inputs.size.toLong())

        for ((index, input) in inputs.withIndex()) {
            // Previous tx hash (reversed)
            buffer.writeBytes(input.txHash.hexToBytes().reversedArray())
            // Previous output index
            buffer.writeInt32LE(input.outputIndex)

            // Create signature hash for this input
            val sigHash = createSigHash(rawTx, index, input.scriptPubKey.hexToBytes())
            val signature = keyPair.sign(sigHash)

            // Build scriptSig: <sig> <pubkey>
            val sigWithHashType = signature + byteArrayOf(0x01) // SIGHASH_ALL
            val pubKey = keyPair.compressedPublicKey()

            val scriptSig = ByteArrayBuilder()
            scriptSig.writePushData(sigWithHashType)
            scriptSig.writePushData(pubKey)
            val scriptSigBytes = scriptSig.toByteArray()

            buffer.writeVarInt(scriptSigBytes.size.toLong())
            buffer.writeBytes(scriptSigBytes)

            // Sequence
            buffer.writeInt32LE(0xFFFFFFFF.toInt())
        }

        // Re-read outputs from the raw transaction
        val outputStart = findOutputStart(rawTx, inputs.size)
        buffer.writeBytes(rawTx.copyOfRange(outputStart, rawTx.size))

        return buffer.toByteArray()
    }

    private fun createSigHash(
        rawTx: ByteArray,
        inputIndex: Int,
        subscript: ByteArray
    ): ByteArray {
        // For SIGHASH_ALL: replace each input's scriptSig with empty,
        // except the signing input which gets the subscript
        val buffer = ByteArrayBuilder()

        val reader = ByteArrayReader(rawTx)

        // Version
        buffer.writeBytes(reader.readBytes(4))

        // Input count
        val inputCount = reader.readVarInt()
        buffer.writeVarInt(inputCount)

        for (i in 0 until inputCount.toInt()) {
            // Previous outpoint (36 bytes)
            buffer.writeBytes(reader.readBytes(36))

            // Skip existing scriptSig
            val scriptLen = reader.readVarInt()
            reader.readBytes(scriptLen.toInt())

            if (i == inputIndex) {
                buffer.writeVarInt(subscript.size.toLong())
                buffer.writeBytes(subscript)
            } else {
                buffer.writeVarInt(0)
            }

            // Sequence
            buffer.writeBytes(reader.readBytes(4))
        }

        // Outputs + locktime
        buffer.writeBytes(reader.readRemaining())

        // Append SIGHASH_ALL (4 bytes LE)
        buffer.writeInt32LE(1)

        return doubleSha256(buffer.toByteArray())
    }

    private fun findOutputStart(rawTx: ByteArray, inputCount: Int): Int {
        val reader = ByteArrayReader(rawTx)
        reader.readBytes(4) // version
        val count = reader.readVarInt()
        for (i in 0 until count.toInt()) {
            reader.readBytes(36) // outpoint
            val scriptLen = reader.readVarInt()
            reader.readBytes(scriptLen.toInt()) // scriptSig
            reader.readBytes(4) // sequence
        }
        return reader.position
    }

    private fun buildP2PKHScript(address: String): ByteArray {
        val hash160 = MeowcoinAddress.toHash160(address)
        return byteArrayOf(
            0x76.toByte(),       // OP_DUP
            0xA9.toByte(),       // OP_HASH160
            0x14.toByte(),       // Push 20 bytes
        ) + hash160 + byteArrayOf(
            0x88.toByte(),       // OP_EQUALVERIFY
            0xAC.toByte()        // OP_CHECKSIG
        )
    }

    private fun doubleSha256(data: ByteArray): ByteArray {
        val sha256 = MessageDigest.getInstance("SHA-256")
        return sha256.digest(sha256.digest(data))
    }
}

/**
 * Helper for building byte arrays for transaction serialization.
 */
class ByteArrayBuilder {
    private val buffer = mutableListOf<Byte>()

    fun writeBytes(bytes: ByteArray) {
        buffer.addAll(bytes.toList())
    }

    fun writeInt32LE(value: Int) {
        writeBytes(
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        )
    }

    fun writeInt64LE(value: Long) {
        writeBytes(
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
        )
    }

    fun writeVarInt(value: Long) {
        when {
            value < 0xFD -> writeBytes(byteArrayOf(value.toByte()))
            value <= 0xFFFF -> {
                writeBytes(byteArrayOf(0xFD.toByte()))
                writeBytes(
                    ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
                        .putShort(value.toShort()).array()
                )
            }
            value <= 0xFFFFFFFFL -> {
                writeBytes(byteArrayOf(0xFE.toByte()))
                writeInt32LE(value.toInt())
            }
            else -> {
                writeBytes(byteArrayOf(0xFF.toByte()))
                writeInt64LE(value)
            }
        }
    }

    fun writePushData(data: ByteArray) {
        when {
            data.size < 0x4C -> writeBytes(byteArrayOf(data.size.toByte()))
            data.size < 0xFF -> {
                writeBytes(byteArrayOf(0x4C.toByte(), data.size.toByte()))
            }
            else -> {
                writeBytes(byteArrayOf(0x4D.toByte()))
                writeBytes(
                    ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
                        .putShort(data.size.toShort()).array()
                )
            }
        }
        writeBytes(data)
    }

    fun toByteArray(): ByteArray = buffer.toByteArray()
}

class ByteArrayReader(private val data: ByteArray) {
    var position: Int = 0

    fun readBytes(count: Int): ByteArray {
        val result = data.copyOfRange(position, position + count)
        position += count
        return result
    }

    fun readVarInt(): Long {
        val first = data[position].toInt() and 0xFF
        position++
        return when {
            first < 0xFD -> first.toLong()
            first == 0xFD -> {
                val bytes = readBytes(2)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).short.toLong() and 0xFFFF
            }
            first == 0xFE -> {
                val bytes = readBytes(4)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            }
            else -> {
                val bytes = readBytes(8)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).long
            }
        }
    }

    fun readRemaining(): ByteArray = readBytes(data.size - position)
}
