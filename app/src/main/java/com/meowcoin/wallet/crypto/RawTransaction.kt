package com.meowcoin.wallet.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

data class RawTransactionInput(
    val previousTxId: String,
    val outputIndex: Long,
    val scriptSignature: ByteArray,
    val sequence: Long,
    val witness: List<ByteArray>
) {
    val isCoinbase: Boolean
        get() = previousTxId.all { it == '0' } && outputIndex == 0xFFFFFFFFL
}

data class RawTransactionOutput(
    val value: Long,
    val scriptPubKey: ByteArray
) {
    val scriptPubKeyHex: String
        get() = scriptPubKey.toHex()
}

data class ParsedRawTransaction(
    val version: Int,
    val inputs: List<RawTransactionInput>,
    val outputs: List<RawTransactionOutput>,
    val lockTime: Long,
    val hasWitness: Boolean,
    val txId: String,
    val witnessTxId: String,
    val serializedSize: Int,
    val strippedSize: Int
) {
    fun outputAt(index: Int): RawTransactionOutput {
        require(index in outputs.indices) { "Transaction output index $index does not exist" }
        return outputs[index]
    }
}

/**
 * Strict parser used to verify Electrum-provided previous transactions before signing.
 * Supports classic Bitcoin-family legacy and SegWit serialization. Litecoin MWEB optional data
 * is deliberately rejected until its additional consensus serialization is implemented.
 */
object RawTransactionParser {
    private const val MAX_TRANSACTION_BYTES = 4_000_000
    private const val MAX_COLLECTION_ITEMS = 1_000_000

    fun parse(transactionHex: String): ParsedRawTransaction {
        val normalized = transactionHex
        require(normalized.isNotEmpty() && normalized.length % 2 == 0) {
            "Raw transaction must be non-empty, even-length hex"
        }
        require(normalized.length <= MAX_TRANSACTION_BYTES * 2) { "Raw transaction is too large" }
        require(normalized.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "Raw transaction contains non-hex characters"
        }

        val serialized = normalized.hexToBytes()
        val reader = RawTransactionReader(serialized)
        val stripped = ByteArrayBuilder()

        val versionBytes = reader.readBytes(4)
        val version = ByteBuffer.wrap(versionBytes).order(ByteOrder.LITTLE_ENDIAN).int
        stripped.writeBytes(versionBytes)

        val hasWitness = if (reader.remaining > 0 && reader.peekByte() == 0) {
            require(reader.remaining >= 2) { "Truncated witness marker" }
            reader.readByte() // marker
            val flag = reader.readByte()
            require(flag and 0x08 == 0) {
                "Litecoin MWEB transaction serialization is not supported"
            }
            require(flag == 1) { "Unsupported transaction optional-data flag: $flag" }
            true
        } else {
            false
        }

        val inputCount = reader.readCount("inputs", minimumItemBytes = 41)
        require(inputCount > 0) { "Transaction must contain at least one input" }
        stripped.writeVarInt(inputCount.toLong())

        data class InputParts(
            val previousHashLittleEndian: ByteArray,
            val outputIndex: Long,
            val scriptSignature: ByteArray,
            val sequence: Long
        )

        val inputParts = ArrayList<InputParts>(inputCount)
        repeat(inputCount) {
            val previousHash = reader.readBytes(32)
            val outputIndex = reader.readUInt32()
            val scriptSignature = reader.readCompactBytes("input script")
            val sequence = reader.readUInt32()
            inputParts += InputParts(previousHash, outputIndex, scriptSignature, sequence)

            stripped.writeBytes(previousHash)
            stripped.writeInt32LE(outputIndex.toInt())
            stripped.writeVarInt(scriptSignature.size.toLong())
            stripped.writeBytes(scriptSignature)
            stripped.writeInt32LE(sequence.toInt())
        }

        val outputCount = reader.readCount("outputs", minimumItemBytes = 9)
        require(outputCount > 0) { "Transaction must contain at least one output" }
        stripped.writeVarInt(outputCount.toLong())

        val outputs = ArrayList<RawTransactionOutput>(outputCount)
        repeat(outputCount) {
            val value = reader.readInt64()
            require(value >= 0) { "Transaction output value cannot be negative" }
            val scriptPubKey = reader.readCompactBytes("output script")
            outputs += RawTransactionOutput(value, scriptPubKey)

            stripped.writeInt64LE(value)
            stripped.writeVarInt(scriptPubKey.size.toLong())
            stripped.writeBytes(scriptPubKey)
        }

        val witnesses = if (hasWitness) {
            var hasWitnessStack = false
            List(inputCount) {
                val itemCount = reader.readCount("witness items", minimumItemBytes = 1, allowZero = true)
                if (itemCount > 0) hasWitnessStack = true
                List(itemCount) { reader.readCompactBytes("witness item") }
            }.also {
                require(hasWitnessStack) { "Superfluous witness serialization" }
            }
        } else {
            List(inputCount) { emptyList() }
        }

        val lockTimeBytes = reader.readBytes(4)
        val lockTime = ByteBuffer.wrap(lockTimeBytes).order(ByteOrder.LITTLE_ENDIAN)
            .int.toLong() and 0xFFFFFFFFL
        stripped.writeBytes(lockTimeBytes)
        require(reader.remaining == 0) { "Raw transaction contains trailing data" }

        val inputs = inputParts.mapIndexed { index, input ->
            RawTransactionInput(
                previousTxId = input.previousHashLittleEndian.reversedArray().toHex(),
                outputIndex = input.outputIndex,
                scriptSignature = input.scriptSignature,
                sequence = input.sequence,
                witness = witnesses[index]
            )
        }
        val strippedBytes = stripped.toByteArray()
        return ParsedRawTransaction(
            version = version,
            inputs = inputs,
            outputs = outputs,
            lockTime = lockTime,
            hasWitness = hasWitness,
            txId = transactionId(strippedBytes),
            witnessTxId = transactionId(serialized),
            serializedSize = serialized.size,
            strippedSize = strippedBytes.size
        )
    }

    fun verifyOutput(
        transactionHex: String,
        expectedTxId: String,
        outputIndex: Int,
        expectedValue: Long,
        expectedScriptPubKeyHex: String
    ): ParsedRawTransaction {
        require(expectedTxId.matches(Regex("[0-9a-fA-F]{64}"))) { "Expected txid must be 32-byte hex" }
        require(expectedValue >= 0) { "Expected output value must be non-negative" }
        val expectedScript = try {
            expectedScriptPubKeyHex.hexToBytes()
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Expected scriptPubKey must be hex", e)
        }

        val transaction = parse(transactionHex)
        require(transaction.txId.equals(expectedTxId, ignoreCase = true)) {
            "Previous transaction txid mismatch"
        }
        val output = transaction.outputAt(outputIndex)
        require(output.value == expectedValue) { "Previous transaction output value mismatch" }
        require(output.scriptPubKey.contentEquals(expectedScript)) {
            "Previous transaction scriptPubKey mismatch"
        }
        return transaction
    }

    private fun transactionId(serialized: ByteArray): String {
        val sha256 = MessageDigest.getInstance("SHA-256")
        return sha256.digest(sha256.digest(serialized)).reversedArray().toHex()
    }

    private class RawTransactionReader(private val data: ByteArray) {
        private var position = 0

        val remaining: Int
            get() = data.size - position

        fun peekByte(): Int {
            require(remaining > 0) { "Unexpected end of raw transaction" }
            return data[position].toInt() and 0xFF
        }

        fun readByte(): Int = readBytes(1)[0].toInt() and 0xFF

        fun readBytes(count: Int): ByteArray {
            require(count >= 0 && count <= remaining) { "Unexpected end of raw transaction" }
            val result = data.copyOfRange(position, position + count)
            position += count
            return result
        }

        fun readUInt32(): Long = ByteBuffer.wrap(readBytes(4)).order(ByteOrder.LITTLE_ENDIAN)
            .int.toLong() and 0xFFFFFFFFL

        fun readInt64(): Long = ByteBuffer.wrap(readBytes(8)).order(ByteOrder.LITTLE_ENDIAN).long

        fun readCompactBytes(label: String): ByteArray {
            val length = readCompactSize()
            require(length <= Int.MAX_VALUE.toLong() && length <= remaining.toLong()) {
                "$label length exceeds remaining transaction data"
            }
            return readBytes(length.toInt())
        }

        fun readCount(
            label: String,
            minimumItemBytes: Int,
            allowZero: Boolean = false
        ): Int {
            val count = readCompactSize()
            require(allowZero || count > 0) { "Transaction must contain $label" }
            require(count <= MAX_COLLECTION_ITEMS) { "Too many $label" }
            require(count <= remaining.toLong() / minimumItemBytes) {
                "$label count exceeds remaining transaction data"
            }
            return count.toInt()
        }

        private fun readCompactSize(): Long {
            return when (val first = readByte()) {
                in 0 until 0xFD -> first.toLong()
                0xFD -> {
                    val value = ByteBuffer.wrap(readBytes(2)).order(ByteOrder.LITTLE_ENDIAN)
                        .short.toLong() and 0xFFFF
                    require(value >= 0xFD) { "Non-canonical CompactSize integer" }
                    value
                }
                0xFE -> {
                    val value = readUInt32()
                    require(value > 0xFFFF) { "Non-canonical CompactSize integer" }
                    value
                }
                else -> {
                    val value = readInt64()
                    require(value >= 0 && value > 0xFFFFFFFFL) {
                        "Invalid or non-canonical CompactSize integer"
                    }
                    value
                }
            }
        }
    }
}
