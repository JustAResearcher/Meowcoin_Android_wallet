package com.meowcoin.wallet.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * BIP39 mnemonic seed phrase implementation for Meowcoin.
 *
 * Generates and validates 12/24-word mnemonic phrases using the
 * standard BIP39 English word list and derives 512-bit seeds via PBKDF2.
 */
object Bip39 {

    /**
     * Generate a new mnemonic seed phrase.
     * @param wordCount 12 (128-bit) or 24 (256-bit) words
     * @return Space-separated mnemonic phrase
     */
    fun generateMnemonic(wordCount: Int = 12): String {
        require(wordCount == 12 || wordCount == 24) { "Word count must be 12 or 24" }

        val entropyBits = if (wordCount == 12) 128 else 256
        val entropyBytes = entropyBits / 8

        val entropy = ByteArray(entropyBytes)
        SecureRandom().nextBytes(entropy)

        return entropyToMnemonic(entropy)
    }

    /**
     * Convert entropy bytes to a mnemonic phrase.
     */
    fun entropyToMnemonic(entropy: ByteArray): String {
        require(entropy.size == 16 || entropy.size == 32) {
            "Entropy must be 16 or 32 bytes"
        }

        val sha256 = MessageDigest.getInstance("SHA-256").digest(entropy)
        val checksumBits = entropy.size / 4 // 4 bits for 128-bit, 8 bits for 256-bit

        // Convert entropy + checksum to a bit string
        val bits = StringBuilder()
        for (b in entropy) {
            bits.append(String.format("%8s", Integer.toBinaryString(b.toInt() and 0xFF)).replace(' ', '0'))
        }
        for (i in 0 until checksumBits) {
            bits.append((sha256[0].toInt() shr (7 - i)) and 1)
        }

        // Split into 11-bit groups and map to words
        val words = mutableListOf<String>()
        val wordList = Bip39WordList.ENGLISH
        for (i in bits.indices step 11) {
            val end = minOf(i + 11, bits.length)
            val index = Integer.parseInt(bits.substring(i, end), 2)
            words.add(wordList[index])
        }

        return words.joinToString(" ")
    }

    /**
     * Validate a mnemonic phrase.
     * Checks word count, word validity, and checksum.
     */
    fun validateMnemonic(mnemonic: String): Boolean {
        val words = mnemonic.trim().lowercase().split("\\s+".toRegex())
        if (words.size != 12 && words.size != 24) return false

        val wordList = Bip39WordList.ENGLISH
        val wordMap = wordList.withIndex().associate { (i, w) -> w to i }

        // Check all words are valid
        val indices = words.map { wordMap[it] ?: return false }

        // Reconstruct bits
        val bits = StringBuilder()
        for (index in indices) {
            bits.append(String.format("%11s", Integer.toBinaryString(index)).replace(' ', '0'))
        }

        val entropyBits = if (words.size == 12) 128 else 256
        val checksumBits = entropyBits / 32

        val entropyBitStr = bits.substring(0, entropyBits)
        val checksumBitStr = bits.substring(entropyBits, entropyBits + checksumBits)

        // Reconstruct entropy bytes
        val entropy = ByteArray(entropyBits / 8)
        for (i in entropy.indices) {
            entropy[i] = Integer.parseInt(entropyBitStr.substring(i * 8, i * 8 + 8), 2).toByte()
        }

        // Verify checksum
        val sha256 = MessageDigest.getInstance("SHA-256").digest(entropy)
        val expectedChecksum = StringBuilder()
        for (i in 0 until checksumBits) {
            expectedChecksum.append((sha256[0].toInt() shr (7 - i)) and 1)
        }

        return checksumBitStr == expectedChecksum.toString()
    }

    /**
     * Derive a 512-bit seed from a mnemonic phrase and optional passphrase.
     * Uses PBKDF2-HMAC-SHA512 with 2048 iterations as specified by BIP39.
     *
     * @param mnemonic The mnemonic phrase
     * @param passphrase Optional passphrase (defaults to empty string)
     * @return 64-byte seed
     */
    fun mnemonicToSeed(mnemonic: String, passphrase: String = ""): ByteArray {
        val normalizedMnemonic = mnemonic.trim().lowercase()
        val salt = "mnemonic$passphrase"

        val spec = PBEKeySpec(
            normalizedMnemonic.toCharArray(),
            salt.toByteArray(Charsets.UTF_8),
            2048,
            512
        )

        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        return factory.generateSecret(spec).encoded
    }
}
