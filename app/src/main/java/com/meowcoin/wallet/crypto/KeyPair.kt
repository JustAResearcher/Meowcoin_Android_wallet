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
 *
 * Supported address types after the APEX upgrade (Meow_v30.2.0):
 *   - Base58Check P2PKH:  version 50  → 'M...'
 *   - Base58Check P2SH:   version 122 → 'm...'
 *   - Bech32 P2WPKH:      witness v0, 20-byte hash → 'mewc1q...'
 *   - Bech32 P2WSH:       witness v0, 32-byte hash → 'mewc1q...'
 *   - Bech32m P2TR:       witness v1, 32-byte key  → 'mewc1p...'
 */
object MeowcoinAddress {
    // Meowcoin Base58 version bytes (chainparams.cpp)
    const val PUBKEY_ADDRESS_VERSION = 50  // 0x32 → 'M'
    const val SCRIPT_ADDRESS_VERSION = 122 // 0x7A → 'm' (P2SH)

    /** Address type, used by callers that need to build the right scriptPubKey. */
    enum class Type { P2PKH, P2SH, P2WPKH, P2WSH, P2TR }

    /** Parsed view of a Meowcoin address with everything callers need to build a scriptPubKey. */
    data class Parsed(val type: Type, val payload: ByteArray, val witnessVersion: Int = -1)

    /**
     * Derive a P2PKH address from a compressed public key.
     * (Receive addresses stay P2PKH for now; SegWit/Taproot are supported as send targets only.)
     */
    fun fromPublicKey(compressedPubKey: ByteArray): String {
        val sha256 = MessageDigest.getInstance("SHA-256").digest(compressedPubKey)
        val ripemd160 = ripemd160(sha256)
        return Base58.encodeChecked(PUBKEY_ADDRESS_VERSION, ripemd160)
    }

    /**
     * Parse a Meowcoin address (base58 or bech32) on the given network HRP.
     * Returns null if the string is not a valid Meowcoin address.
     */
    fun parse(address: String, hrp: String = MeowcoinNetwork.BECH32_HRP): Parsed? {
        // Try base58check first.
        try {
            val (version, payload) = Base58.decodeChecked(address)
            if (payload.size == 20) {
                when (version) {
                    PUBKEY_ADDRESS_VERSION -> return Parsed(Type.P2PKH, payload)
                    SCRIPT_ADDRESS_VERSION -> return Parsed(Type.P2SH, payload)
                }
            }
        } catch (_: Exception) {
            // fall through to bech32
        }

        // Try bech32 / bech32m.
        val witness = Bech32.decodeSegwitAddress(hrp, address) ?: return null
        return when {
            witness.witnessVersion == 0 && witness.program.size == 20 ->
                Parsed(Type.P2WPKH, witness.program, 0)
            witness.witnessVersion == 0 && witness.program.size == 32 ->
                Parsed(Type.P2WSH, witness.program, 0)
            witness.witnessVersion == 1 && witness.program.size == 32 ->
                Parsed(Type.P2TR, witness.program, 1)
            else -> null
        }
    }

    /**
     * Validate a Meowcoin address (any supported type).
     */
    fun isValid(address: String): Boolean = parse(address) != null

    /**
     * Build the scriptPubKey that locks an output to the given address.
     */
    fun toScriptPubKey(address: String, hrp: String = MeowcoinNetwork.BECH32_HRP): ByteArray {
        val parsed = parse(address, hrp)
            ?: throw IllegalArgumentException("Invalid Meowcoin address: $address")
        return scriptPubKeyFor(parsed)
    }

    fun scriptPubKeyFor(parsed: Parsed): ByteArray = when (parsed.type) {
        Type.P2PKH -> byteArrayOf(
            0x76.toByte(),                       // OP_DUP
            0xA9.toByte(), 0x14.toByte()         // OP_HASH160, push 20
        ) + parsed.payload + byteArrayOf(
            0x88.toByte(), 0xAC.toByte()         // OP_EQUALVERIFY, OP_CHECKSIG
        )
        Type.P2SH -> byteArrayOf(
            0xA9.toByte(), 0x14.toByte()         // OP_HASH160, push 20
        ) + parsed.payload + byteArrayOf(
            0x87.toByte()                        // OP_EQUAL
        )
        Type.P2WPKH, Type.P2WSH, Type.P2TR -> {
            val opVersion = if (parsed.witnessVersion == 0) 0x00.toByte() else (0x50 + parsed.witnessVersion).toByte()
            byteArrayOf(opVersion, parsed.payload.size.toByte()) + parsed.payload
        }
    }

    /**
     * Extract the hash160 (pubkey hash) from a base58 P2PKH/P2SH address.
     * Throws for bech32 addresses — callers expecting a 20-byte hash160 must check the
     * address type with [parse] first.
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
