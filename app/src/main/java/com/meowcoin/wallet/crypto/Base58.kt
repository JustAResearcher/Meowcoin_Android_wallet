package com.meowcoin.wallet.crypto

import java.security.MessageDigest

/**
 * Base58 and Base58Check encoding/decoding used by Meowcoin addresses.
 * Meowcoin uses the same Base58Check as Bitcoin/Ravencoin.
 */
object Base58 {

    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val ENCODED_ZERO = ALPHABET[0]
    private val INDEXES = IntArray(128) { -1 }.also { indexes ->
        ALPHABET.forEachIndexed { i, c -> indexes[c.code] = i }
    }

    /**
     * Encodes the given bytes as a Base58 string (no checksum).
     */
    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""

        // Count leading zeros
        var zeros = 0
        while (zeros < input.size && input[zeros].toInt() == 0) zeros++

        // Convert base-256 digits to base-58 digits
        val encoded = CharArray(input.size * 2) // upper bound
        var outputStart = encoded.size
        var inputStart = zeros

        while (inputStart < input.size) {
            val remainder = divmod(input, inputStart, 256, 58)
            if (input[inputStart].toInt() == 0) inputStart++
            encoded[--outputStart] = ALPHABET[remainder]
        }

        // Preserve leading zeros as '1's
        while (outputStart < encoded.size && encoded[outputStart] == ENCODED_ZERO) outputStart++
        repeat(zeros) { encoded[--outputStart] = ENCODED_ZERO }

        return String(encoded, outputStart, encoded.size - outputStart)
    }

    /**
     * Decodes a Base58 string into bytes.
     */
    fun decode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)

        val input58 = ByteArray(input.length)
        for (i in input.indices) {
            val c = input[i]
            val digit = if (c.code < 128) INDEXES[c.code] else -1
            require(digit >= 0) { "Invalid Base58 character: $c" }
            input58[i] = digit.toByte()
        }

        // Count leading zeros
        var zeros = 0
        while (zeros < input58.size && input58[zeros].toInt() == 0) zeros++

        // Convert base-58 digits to base-256 digits
        val decoded = ByteArray(input.length)
        var outputStart = decoded.size
        var inputStart = zeros

        while (inputStart < input58.size) {
            val remainder = divmod(input58, inputStart, 58, 256)
            if (input58[inputStart].toInt() == 0) inputStart++
            decoded[--outputStart] = remainder.toByte()
        }

        // Skip extra leading zeros from conversion
        while (outputStart < decoded.size && decoded[outputStart].toInt() == 0) outputStart++

        return ByteArray(zeros + (decoded.size - outputStart)).also {
            System.arraycopy(decoded, outputStart, it, zeros, decoded.size - outputStart)
        }
    }

    /**
     * Encodes with a 4-byte checksum appended (Base58Check).
     */
    fun encodeChecked(version: Int, payload: ByteArray): String {
        val addressBytes = ByteArray(1 + payload.size + 4)
        addressBytes[0] = version.toByte()
        System.arraycopy(payload, 0, addressBytes, 1, payload.size)

        val checksum = doubleHash(addressBytes, 0, payload.size + 1)
        System.arraycopy(checksum, 0, addressBytes, payload.size + 1, 4)

        return encode(addressBytes)
    }

    /**
     * Decodes a Base58Check string, validates the checksum, and returns (version, payload).
     */
    fun decodeChecked(address: String): Pair<Int, ByteArray> {
        val decoded = decode(address)
        require(decoded.size >= 5) { "Address too short" }

        val checksum = doubleHash(decoded, 0, decoded.size - 4)
        for (i in 0 until 4) {
            require(decoded[decoded.size - 4 + i] == checksum[i]) {
                "Invalid checksum"
            }
        }

        val version = decoded[0].toInt() and 0xFF
        val payload = ByteArray(decoded.size - 5)
        System.arraycopy(decoded, 1, payload, 0, payload.size)
        return Pair(version, payload)
    }

    private fun doubleHash(data: ByteArray, offset: Int, length: Int): ByteArray {
        val sha256 = MessageDigest.getInstance("SHA-256")
        sha256.update(data, offset, length)
        return sha256.digest(sha256.digest())
    }

    private fun divmod(number: ByteArray, firstDigit: Int, base: Int, divisor: Int): Int {
        var remainder = 0
        for (i in firstDigit until number.size) {
            val digit = number[i].toInt() and 0xFF
            val temp = remainder * base + digit
            number[i] = (temp / divisor).toByte()
            remainder = temp % divisor
        }
        return remainder
    }
}
