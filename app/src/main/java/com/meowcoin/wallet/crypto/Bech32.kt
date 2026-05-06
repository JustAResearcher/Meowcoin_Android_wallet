package com.meowcoin.wallet.crypto

/**
 * Bech32 (BIP173) and Bech32m (BIP350) encoding/decoding for SegWit and Taproot addresses.
 *
 * Used by Meowcoin from the APEX upgrade (Meow_v30.2.0) onward, where SegWit and Taproot
 * are always active. Mainnet HRP is "mewc", testnet "tmewc" (see [MeowcoinNetwork]).
 *
 *   v0 program  → bech32   (BIP173): P2WPKH (20 bytes) and P2WSH (32 bytes)
 *   v1+ program → bech32m  (BIP350): P2TR  (32 bytes) and future witness versions
 */
object Bech32 {

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val CHARSET_REV = IntArray(128) { -1 }.also { rev ->
        CHARSET.forEachIndexed { i, c -> rev[c.code] = i }
    }

    private const val BECH32_CONST = 1
    private const val BECH32M_CONST = 0x2bc830a3

    enum class Encoding { BECH32, BECH32M }

    data class Decoded(val hrp: String, val data: ByteArray, val encoding: Encoding)

    data class WitnessAddress(val hrp: String, val witnessVersion: Int, val program: ByteArray)

    // ──────────────────────────────────────────────────────────────────
    //  Low-level bech32 / bech32m
    // ──────────────────────────────────────────────────────────────────

    /**
     * Encode an HRP + 5-bit data array into a bech32 / bech32m string.
     */
    fun encode(hrp: String, data: ByteArray, encoding: Encoding): String {
        val combined = data + createChecksum(hrp, data, encoding)
        val sb = StringBuilder(hrp.length + 1 + combined.size)
        sb.append(hrp).append('1')
        for (b in combined) sb.append(CHARSET[b.toInt() and 0x1F])
        return sb.toString()
    }

    /**
     * Decode a bech32 / bech32m string. Returns the HRP, the 5-bit data (no checksum),
     * and which encoding the checksum used. Throws if the string is malformed.
     */
    fun decode(input: String): Decoded {
        require(input.length in 8..90) { "Bech32 string length out of range" }

        var hasLower = false
        var hasUpper = false
        for (c in input) {
            require(c.code in 33..126) { "Invalid character in bech32 string" }
            if (c in 'a'..'z') hasLower = true
            if (c in 'A'..'Z') hasUpper = true
        }
        require(!(hasLower && hasUpper)) { "Mixed case bech32 string" }

        val lower = input.lowercase()
        val sep = lower.lastIndexOf('1')
        require(sep in 1..(lower.length - 7)) { "Invalid bech32 separator position" }

        val hrp = lower.substring(0, sep)
        val dataPart = lower.substring(sep + 1)
        for (c in hrp) require(c.code in 33..126) { "Invalid HRP character" }

        val data = ByteArray(dataPart.length)
        for (i in dataPart.indices) {
            val v = if (dataPart[i].code < 128) CHARSET_REV[dataPart[i].code] else -1
            require(v >= 0) { "Invalid data character: ${dataPart[i]}" }
            data[i] = v.toByte()
        }

        val polymod = polymod(hrpExpand(hrp) + data)
        val encoding = when (polymod) {
            BECH32_CONST -> Encoding.BECH32
            BECH32M_CONST -> Encoding.BECH32M
            else -> throw IllegalArgumentException("Invalid bech32 checksum")
        }

        return Decoded(hrp, data.copyOfRange(0, data.size - 6), encoding)
    }

    private fun polymod(values: ByteArray): Int {
        val gen = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        var chk = 1
        for (v in values) {
            val b = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor (v.toInt() and 0xff)
            for (i in 0 until 5) {
                if ((b ushr i) and 1 != 0) chk = chk xor gen[i]
            }
        }
        return chk
    }

    private fun hrpExpand(hrp: String): ByteArray {
        val out = ByteArray(hrp.length * 2 + 1)
        for (i in hrp.indices) out[i] = (hrp[i].code ushr 5).toByte()
        out[hrp.length] = 0
        for (i in hrp.indices) out[hrp.length + 1 + i] = (hrp[i].code and 0x1f).toByte()
        return out
    }

    private fun createChecksum(hrp: String, data: ByteArray, encoding: Encoding): ByteArray {
        val constant = if (encoding == Encoding.BECH32M) BECH32M_CONST else BECH32_CONST
        val values = hrpExpand(hrp) + data + ByteArray(6)
        val mod = polymod(values) xor constant
        val out = ByteArray(6)
        for (i in 0 until 6) out[i] = ((mod ushr (5 * (5 - i))) and 0x1f).toByte()
        return out
    }

    // ──────────────────────────────────────────────────────────────────
    //  Witness program ↔ bech32 address
    // ──────────────────────────────────────────────────────────────────

    /**
     * Encode a SegWit or Taproot witness program as a bech32(/bech32m) address.
     *
     *   witnessVersion = 0  → bech32   (P2WPKH / P2WSH)
     *   witnessVersion ≥ 1  → bech32m  (P2TR  / future)
     */
    fun encodeSegwitAddress(hrp: String, witnessVersion: Int, program: ByteArray): String {
        require(witnessVersion in 0..16) { "Invalid witness version: $witnessVersion" }
        require(program.size in 2..40) { "Invalid witness program length: ${program.size}" }
        if (witnessVersion == 0) {
            require(program.size == 20 || program.size == 32) {
                "v0 witness program must be 20 (P2WPKH) or 32 (P2WSH) bytes"
            }
        }
        val encoding = if (witnessVersion == 0) Encoding.BECH32 else Encoding.BECH32M
        val data = byteArrayOf(witnessVersion.toByte()) + convertBits(program, 8, 5, true)
        return encode(hrp, data, encoding)
    }

    /**
     * Decode a SegWit or Taproot bech32 address. Verifies the encoding matches the
     * witness version (v0 → bech32, v1+ → bech32m) per BIP350.
     *
     * @return the HRP, witness version, and witness program bytes; or `null` if the
     *         string is not a valid SegWit address with this HRP.
     */
    fun decodeSegwitAddress(expectedHrp: String, address: String): WitnessAddress? {
        val decoded = try {
            decode(address)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (decoded.hrp != expectedHrp.lowercase()) return null
        if (decoded.data.isEmpty()) return null

        val witnessVersion = decoded.data[0].toInt() and 0xff
        if (witnessVersion > 16) return null

        val program = try {
            convertBits(decoded.data.copyOfRange(1, decoded.data.size), 5, 8, false)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (program.size !in 2..40) return null
        if (witnessVersion == 0 && program.size != 20 && program.size != 32) return null

        val expectedEncoding = if (witnessVersion == 0) Encoding.BECH32 else Encoding.BECH32M
        if (decoded.encoding != expectedEncoding) return null

        return WitnessAddress(decoded.hrp, witnessVersion, program)
    }

    /**
     * Convert between bit groupings (e.g. 8-bit bytes ↔ 5-bit bech32 symbols).
     * Mirrors the reference implementation in BIP173.
     */
    fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val out = ArrayList<Byte>(data.size * fromBits / toBits + 1)
        val maxv = (1 shl toBits) - 1
        val maxAcc = (1 shl (fromBits + toBits - 1)) - 1
        for (b in data) {
            val value = b.toInt() and 0xff
            require(value ushr fromBits == 0) { "Invalid value for $fromBits-bit conversion" }
            acc = ((acc shl fromBits) or value) and maxAcc
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                out.add(((acc ushr bits) and maxv).toByte())
            }
        }
        if (pad) {
            if (bits > 0) out.add(((acc shl (toBits - bits)) and maxv).toByte())
        } else {
            require(bits < fromBits && ((acc shl (toBits - bits)) and maxv) == 0) {
                "Non-zero padding in conversion"
            }
        }
        return out.toByteArray()
    }
}
