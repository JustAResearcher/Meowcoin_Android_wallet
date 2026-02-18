package com.meowcoin.wallet.crypto

import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * BIP32 Hierarchical Deterministic key derivation for Meowcoin.
 *
 * Derives child keys from a master seed using the standard BIP32 algorithm.
 * Supports both hardened and normal derivation.
 */
object Bip32 {

    private val CURVE_PARAMS = ECNamedCurveTable.getParameterSpec("secp256k1")
    private val CURVE_ORDER = CURVE_PARAMS.n

    /**
     * Represents an extended key (either public or private) in the BIP32 hierarchy.
     */
    data class ExtendedKey(
        val key: ByteArray,          // 32 bytes private key OR 33 bytes compressed public key
        val chainCode: ByteArray,    // 32 bytes chain code
        val depth: Int = 0,
        val parentFingerprint: Int = 0,
        val childIndex: Int = 0,
        val isPrivate: Boolean = true
    ) {
        /**
         * Convert to a MeowcoinKeyPair (only if this is a private key).
         */
        fun toKeyPair(): MeowcoinKeyPair {
            require(isPrivate) { "Cannot create key pair from public extended key" }
            return MeowcoinKeyPair.fromPrivateKey(key.toHex())
        }

        /**
         * Get the compressed public key for this extended key.
         */
        fun publicKeyBytes(): ByteArray {
            return if (isPrivate) {
                val privKey = BigInteger(1, key)
                CURVE_PARAMS.g.multiply(privKey).normalize().getEncoded(true)
            } else {
                key
            }
        }

        /**
         * Get the fingerprint (first 4 bytes of Hash160 of the public key).
         */
        fun fingerprint(): Int {
            val pubKey = publicKeyBytes()
            val hash160 = hash160(pubKey)
            return ByteBuffer.wrap(hash160, 0, 4).int
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ExtendedKey) return false
            return key.contentEquals(other.key) && chainCode.contentEquals(other.chainCode) &&
                    depth == other.depth && isPrivate == other.isPrivate
        }

        override fun hashCode(): Int {
            var result = key.contentHashCode()
            result = 31 * result + chainCode.contentHashCode()
            result = 31 * result + depth
            return result
        }
    }

    /**
     * Derive the master key from a BIP39 seed.
     * Uses HMAC-SHA512 with key "Bitcoin seed" (standard across all Bitcoin-derived coins).
     */
    fun masterKeyFromSeed(seed: ByteArray): ExtendedKey {
        require(seed.size == 64) { "Seed must be 64 bytes" }

        val hmacResult = hmacSha512("Bitcoin seed".toByteArray(), seed)
        val privateKey = hmacResult.copyOfRange(0, 32)
        val chainCode = hmacResult.copyOfRange(32, 64)

        // Validate the private key
        val keyInt = BigInteger(1, privateKey)
        require(keyInt > BigInteger.ZERO && keyInt < CURVE_ORDER) {
            "Invalid master key (out of range)"
        }

        return ExtendedKey(
            key = privateKey,
            chainCode = chainCode,
            depth = 0,
            parentFingerprint = 0,
            childIndex = 0,
            isPrivate = true
        )
    }

    /**
     * Derive a child key at the given index.
     *
     * @param parent The parent extended key
     * @param index Child index. Values >= 0x80000000 are hardened.
     * @return The derived child extended key
     */
    fun deriveChild(parent: ExtendedKey, index: Int): ExtendedKey {
        val isHardened = index < 0 // Negative int means bit 31 is set (>= 0x80000000)

        val data = if (isHardened) {
            require(parent.isPrivate) { "Cannot derive hardened child from public key" }
            // Hardened: 0x00 || private_key || index
            ByteArray(1) + parent.key + intToBytes(index)
        } else {
            // Normal: compressed_public_key || index
            parent.publicKeyBytes() + intToBytes(index)
        }

        val hmacResult = hmacSha512(parent.chainCode, data)
        val childKeyMaterial = hmacResult.copyOfRange(0, 32)
        val childChainCode = hmacResult.copyOfRange(32, 64)

        val childKeyInt = BigInteger(1, childKeyMaterial)
        require(childKeyInt < CURVE_ORDER) { "Child key derivation failed (key >= curve order)" }

        return if (parent.isPrivate) {
            val parentKeyInt = BigInteger(1, parent.key)
            val childPrivKey = childKeyInt.add(parentKeyInt).mod(CURVE_ORDER)
            require(childPrivKey != BigInteger.ZERO) { "Child key is zero" }

            ExtendedKey(
                key = childPrivKey.toByteArrayUnsigned(32),
                chainCode = childChainCode,
                depth = parent.depth + 1,
                parentFingerprint = parent.fingerprint(),
                childIndex = index,
                isPrivate = true
            )
        } else {
            val childKeyPoint = CURVE_PARAMS.g.multiply(childKeyInt)
            val parentPoint = CURVE_PARAMS.curve.decodePoint(parent.key)
            val childPubKey = parentPoint.add(childKeyPoint).normalize()
            require(!childPubKey.isInfinity) { "Child public key is at infinity" }

            ExtendedKey(
                key = childPubKey.getEncoded(true),
                chainCode = childChainCode,
                depth = parent.depth + 1,
                parentFingerprint = parent.fingerprint(),
                childIndex = index,
                isPrivate = false
            )
        }
    }

    /**
     * Derive a key along a BIP32 path string like "m/44'/1669'/0'/0/0".
     *
     * @param master The master extended key
     * @param path BIP32 derivation path (e.g., "m/44'/1669'/0'/0/0")
     * @return The derived extended key
     */
    fun derivePath(master: ExtendedKey, path: String): ExtendedKey {
        val components = path.trim().split("/")
        require(components[0] == "m") { "Path must start with 'm'" }

        var current = master
        for (i in 1 until components.size) {
            val component = components[i]
            val isHardened = component.endsWith("'") || component.endsWith("h")
            val indexStr = component.trimEnd('\'', 'h')
            val index = indexStr.toInt()

            val childIndex = if (isHardened) {
                index or 0x80000000.toInt() // Set the hardened bit
            } else {
                index
            }

            current = deriveChild(current, childIndex)
        }

        return current
    }

    /**
     * Derive a Meowcoin BIP44 key at a specific address index.
     * Path: m/44'/1669'/account'/change/addressIndex
     *
     * @param master The master extended key from seed
     * @param account Account index (default 0)
     * @param change 0 for external (receiving), 1 for internal (change)
     * @param addressIndex Address index within the account
     */
    fun deriveMeowcoinKey(
        master: ExtendedKey,
        account: Int = 0,
        change: Int = 0,
        addressIndex: Int = 0
    ): ExtendedKey {
        val path = "m/44'/1669'/$account'/$change/$addressIndex"
        return derivePath(master, path)
    }

    // ── Utility functions ──

    private fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
        val hmac = HMac(SHA512Digest())
        hmac.init(KeyParameter(key))
        hmac.update(data, 0, data.size)
        val result = ByteArray(64)
        hmac.doFinal(result, 0)
        return result
    }

    private fun hash160(input: ByteArray): ByteArray {
        val sha256 = MessageDigest.getInstance("SHA-256").digest(input)
        val ripemd = org.bouncycastle.crypto.digests.RIPEMD160Digest()
        ripemd.update(sha256, 0, sha256.size)
        val output = ByteArray(20)
        ripemd.doFinal(output, 0)
        return output
    }

    private fun intToBytes(value: Int): ByteArray {
        return ByteBuffer.allocate(4).putInt(value).array()
    }
}
