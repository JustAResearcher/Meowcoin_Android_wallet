package com.meowcoin.wallet.crypto

import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERSequenceGenerator
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec
import org.bouncycastle.math.ec.ECPoint
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security

/**
 * Manages ECDSA key pair on secp256k1 for Meowcoin.
 * Meowcoin (like Ravencoin and Bitcoin) uses secp256k1 elliptic curve.
 */
class MeowcoinKeyPair private constructor(
    val privateKey: BigInteger,
    val publicKey: ECPoint
) {
    companion object {
        private val CURVE_PARAMS: ECNamedCurveParameterSpec =
            ECNamedCurveTable.getParameterSpec("secp256k1")

        init {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }

        /**
         * Generate a new random key pair.
         */
        fun generate(): MeowcoinKeyPair {
            val secureRandom = SecureRandom()
            val privKeyBytes = ByteArray(32)
            var privKey: BigInteger

            do {
                secureRandom.nextBytes(privKeyBytes)
                privKey = BigInteger(1, privKeyBytes)
            } while (privKey == BigInteger.ZERO || privKey >= CURVE_PARAMS.n)

            val pubKey = CURVE_PARAMS.g.multiply(privKey).normalize()
            return MeowcoinKeyPair(privKey, pubKey)
        }

        /**
         * Restore a key pair from a private key hex string.
         */
        fun fromPrivateKey(privateKeyHex: String): MeowcoinKeyPair {
            val privKey = BigInteger(privateKeyHex, 16)
            require(privKey > BigInteger.ZERO && privKey < CURVE_PARAMS.n) {
                "Private key out of range"
            }
            val pubKey = CURVE_PARAMS.g.multiply(privKey).normalize()
            return MeowcoinKeyPair(privKey, pubKey)
        }

        /**
         * Import from Wallet Import Format (WIF) private key.
         * Meowcoin WIF uses version byte 0x80 (same as Bitcoin mainnet).
         */
        fun fromWIF(wif: String): MeowcoinKeyPair {
            val (version, payload) = Base58.decodeChecked(wif)
            require(version == 0x70) { "Invalid WIF version: $version (expected 0x70)" }

            val keyBytes = if (payload.size == 33 && payload[32].toInt() == 1) {
                // Compressed key indicator
                payload.copyOfRange(0, 32)
            } else {
                require(payload.size == 32) { "Invalid WIF payload length: ${payload.size}" }
                payload
            }

            return fromPrivateKey(keyBytes.toHex())
        }
    }

    /**
     * Get the private key as hex string.
     */
    fun privateKeyHex(): String {
        return privateKey.toByteArray().let { bytes ->
            // Remove potential leading zero byte from BigInteger encoding
            if (bytes.size == 33 && bytes[0].toInt() == 0) {
                bytes.copyOfRange(1, 33)
            } else {
                bytes
            }.toHex()
        }
    }

    /**
     * Get the compressed public key (33 bytes).
     */
    fun compressedPublicKey(): ByteArray {
        return publicKey.getEncoded(true)
    }

    /**
     * Get the uncompressed public key (65 bytes).
     */
    fun uncompressedPublicKey(): ByteArray {
        return publicKey.getEncoded(false)
    }

    /**
     * Export private key in Wallet Import Format (WIF) with compressed flag.
     */
    fun toWIF(): String {
        val privKeyBytes = privateKey.toByteArrayUnsigned(32)
        val payload = ByteArray(33)
        System.arraycopy(privKeyBytes, 0, payload, 0, 32)
        payload[32] = 0x01 // compressed flag
        return Base58.encodeChecked(0x70, payload) // Meowcoin WIF version = 112 (0x70)
    }

    /**
     * Derive the Meowcoin address from this key pair.
     * Meowcoin uses version byte 50 (0x32) for P2PKH addresses → starts with 'M'.
     */
    fun toAddress(): String {
        return MeowcoinAddress.fromPublicKey(compressedPublicKey())
    }

    /**
     * Sign a message hash (32 bytes) with this private key.
     * Returns DER-encoded signature.
     */
    fun sign(messageHash: ByteArray): ByteArray {
        require(messageHash.size == 32) { "Message hash must be 32 bytes" }

        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        val privKeyParams = ECPrivateKeyParameters(
            privateKey,
            org.bouncycastle.crypto.params.ECDomainParameters(
                CURVE_PARAMS.curve,
                CURVE_PARAMS.g,
                CURVE_PARAMS.n,
                CURVE_PARAMS.h
            )
        )
        signer.init(true, privKeyParams)
        val sig = signer.generateSignature(messageHash)
        val r = sig[0]
        var s = sig[1]

        // Enforce low-S for malleability protection
        val halfN = CURVE_PARAMS.n.shiftRight(1)
        if (s > halfN) {
            s = CURVE_PARAMS.n.subtract(s)
        }

        return derEncode(r, s)
    }

    /**
     * Verify a DER-encoded signature against a message hash.
     */
    fun verify(messageHash: ByteArray, derSignature: ByteArray): Boolean {
        val signer = ECDSASigner()
        val pubKeyParams = ECPublicKeyParameters(
            publicKey,
            org.bouncycastle.crypto.params.ECDomainParameters(
                CURVE_PARAMS.curve,
                CURVE_PARAMS.g,
                CURVE_PARAMS.n,
                CURVE_PARAMS.h
            )
        )
        signer.init(false, pubKeyParams)

        val decoded = decodeDER(derSignature)
        return signer.verifySignature(messageHash, decoded.first, decoded.second)
    }

    private fun derEncode(r: BigInteger, s: BigInteger): ByteArray {
        val bos = ByteArrayOutputStream(72)
        val seq = DERSequenceGenerator(bos)
        seq.addObject(ASN1Integer(r))
        seq.addObject(ASN1Integer(s))
        seq.close()
        return bos.toByteArray()
    }

    private fun decodeDER(der: ByteArray): Pair<BigInteger, BigInteger> {
        val seq = org.bouncycastle.asn1.ASN1InputStream(der).readObject()
            as org.bouncycastle.asn1.ASN1Sequence
        val r = (seq.getObjectAt(0) as ASN1Integer).value
        val s = (seq.getObjectAt(1) as ASN1Integer).value
        return Pair(r, s)
    }
}

/**
 * Meowcoin address utilities.
 * Address version byte: 50 (0x32) → addresses start with 'M'
 */
object MeowcoinAddress {
    // Meowcoin P2PKH version byte
    const val PUBKEY_ADDRESS_VERSION = 50  // 0x32 → 'M'
    const val SCRIPT_ADDRESS_VERSION = 122 // 0x7A → 'm' (P2SH)

    /**
     * Derive a P2PKH address from a compressed public key.
     */
    fun fromPublicKey(compressedPubKey: ByteArray): String {
        val sha256 = MessageDigest.getInstance("SHA-256").digest(compressedPubKey)
        val ripemd160 = ripemd160(sha256)
        return Base58.encodeChecked(PUBKEY_ADDRESS_VERSION, ripemd160)
    }

    /**
     * Validate a Meowcoin address.
     */
    fun isValid(address: String): Boolean {
        return try {
            val (version, payload) = Base58.decodeChecked(address)
            (version == PUBKEY_ADDRESS_VERSION || version == SCRIPT_ADDRESS_VERSION) &&
                    payload.size == 20
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Extract the hash160 (pubkey hash) from an address.
     */
    fun toHash160(address: String): ByteArray {
        val (_, payload) = Base58.decodeChecked(address)
        return payload
    }

    private fun ripemd160(input: ByteArray): ByteArray {
        val digest = org.bouncycastle.crypto.digests.RIPEMD160Digest()
        digest.update(input, 0, input.size)
        val output = ByteArray(20)
        digest.doFinal(output, 0)
        return output
    }
}

// Extension functions
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Hex string must have even length" }
    return ByteArray(length / 2) {
        ((this[it * 2].digitToInt(16) shl 4) + this[it * 2 + 1].digitToInt(16)).toByte()
    }
}

fun BigInteger.toByteArrayUnsigned(length: Int): ByteArray {
    val bytes = toByteArray()
    return when {
        bytes.size == length -> bytes
        bytes.size == length + 1 && bytes[0].toInt() == 0 -> bytes.copyOfRange(1, bytes.size)
        bytes.size < length -> ByteArray(length - bytes.size) + bytes
        else -> throw IllegalArgumentException("BigInteger too large for $length bytes")
    }
}
