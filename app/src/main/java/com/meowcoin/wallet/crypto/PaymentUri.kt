package com.meowcoin.wallet.crypto

import java.math.BigDecimal
import java.net.URLDecoder
import java.net.URLEncoder

enum class PaymentRequestSource { URI, RAW_ADDRESS }

data class PaymentRequest(
    val profile: CoinProfile,
    val address: String,
    val amountAtomic: Long? = null,
    val label: String? = null,
    val message: String? = null,
    val source: PaymentRequestSource = PaymentRequestSource.URI
) {
    val amount: BigDecimal?
        get() = amountAtomic?.let { AmountCodec.fromAtomic(it, profile) }
}

/** Minimal BIP21-style payment URI support for every registered profile. */
object PaymentUriCodec {
    fun build(
        profile: CoinProfile,
        address: String,
        amount: BigDecimal? = null,
        label: String? = null,
        message: String? = null
    ): String = buildFromAtomic(
        profile = profile,
        address = address,
        amountAtomic = amount?.let { AmountCodec.toAtomic(it, profile) },
        label = label,
        message = message
    )

    fun buildFromAtomic(
        profile: CoinProfile,
        address: String,
        amountAtomic: Long? = null,
        label: String? = null,
        message: String? = null
    ): String {
        require(MeowcoinAddress.isValid(address, profile)) {
            "Invalid ${profile.ticker} payment address"
        }
        require(amountAtomic == null || amountAtomic > 0) { "Payment amount must be positive" }

        val parameters = mutableListOf<String>()
        amountAtomic?.let {
            parameters += "amount=${AmountCodec.formatAtomic(it, profile)}"
        }
        label?.takeIf(String::isNotEmpty)?.let { parameters += "label=${encodeComponent(it)}" }
        message?.takeIf(String::isNotEmpty)?.let { parameters += "message=${encodeComponent(it)}" }

        return buildString {
            append(profile.uriScheme)
            append(':')
            append(address)
            if (parameters.isNotEmpty()) {
                append('?')
                append(parameters.joinToString("&"))
            }
        }
    }

    fun parse(uri: String): PaymentRequest {
        val trimmed = uri.trim()
        require('#' !in trimmed) { "Payment URI fragments are not supported" }

        val separator = trimmed.indexOf(':')
        require(separator > 0) { "Payment URI is missing a scheme" }
        val scheme = trimmed.substring(0, separator).lowercase()
        val profile = CoinRegistry.findByUriScheme(scheme)
            ?: throw IllegalArgumentException("Unsupported payment URI scheme: $scheme")
        return parse(trimmed, profile)
    }

    fun parse(uri: String, profile: CoinProfile): PaymentRequest {
        val trimmed = uri.trim()
        require('#' !in trimmed) { "Payment URI fragments are not supported" }

        val separator = trimmed.indexOf(':')
        require(separator > 0) { "Payment URI is missing a scheme" }
        val scheme = trimmed.substring(0, separator)
        require(scheme.equals(profile.uriScheme, ignoreCase = true)) {
            "Expected ${profile.uriScheme}: payment URI"
        }

        val remainder = trimmed.substring(separator + 1)
        require(!remainder.startsWith("//")) { "Payment URI must not contain //" }
        val address = decodeComponent(remainder.substringBefore('?'))
        require(MeowcoinAddress.isValid(address, profile)) {
            "Invalid ${profile.ticker} payment address"
        }

        var amountAtomic: Long? = null
        var label: String? = null
        var message: String? = null
        val seen = mutableSetOf<String>()
        val rawQuery = remainder.substringAfter('?', missingDelimiterValue = "")
        if ('?' in remainder && rawQuery.isNotEmpty()) {
            for (part in rawQuery.split('&')) {
                require(part.isNotEmpty()) { "Payment URI contains an empty query parameter" }
                val rawKey = part.substringBefore('=')
                val rawValue = part.substringAfter('=', missingDelimiterValue = "")
                val key = decodeComponent(rawKey).lowercase()
                require(seen.add(key)) { "Duplicate payment URI parameter: $key" }

                when (key) {
                    "amount" -> {
                        require(rawValue.isNotEmpty()) { "Payment amount must not be empty" }
                        val amountText = decodeComponent(rawValue)
                        require(isFixedPointAmount(amountText, profile.decimals)) {
                            "Payment amount must use fixed-point decimal notation"
                        }
                        amountAtomic = AmountCodec.parseAtomic(amountText, profile)
                        require(amountAtomic > 0) { "Payment amount must be positive" }
                    }
                    "label" -> label = decodeComponent(rawValue)
                    "message" -> message = decodeComponent(rawValue)
                    else -> require(!key.startsWith("req-")) {
                        "Unsupported required payment URI parameter: $key"
                    }
                }
            }
        }

        return PaymentRequest(
            profile = profile,
            address = address,
            amountAtomic = amountAtomic,
            label = label,
            message = message,
            source = PaymentRequestSource.URI
        )
    }

    /**
     * Parses a destination entered on a send screen while retaining whether it came from a
     * coin-tagged payment URI or a bare address.
     *
     * A Base58Check address has no globally unique network identifier. If the same bare string
     * maps to different script types in registered profiles, selecting a coin would silently
     * change its locking script. Such raw input is rejected; a matching coin-specific URI is
     * required to carry the missing provenance.
     */
    fun parseSendTarget(input: String, profile: CoinProfile): PaymentRequest {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty()) { "Payment destination must not be empty" }

        if (':' in trimmed) {
            return parse(trimmed, profile)
        }

        require(MeowcoinAddress.isValid(trimmed, profile)) {
            "Invalid ${profile.ticker} payment address"
        }
        require(!hasCrossScriptBase58Ambiguity(trimmed)) {
            "Ambiguous Base58 address: use a ${profile.uriScheme}: payment URI"
        }

        return PaymentRequest(
            profile = profile,
            address = trimmed,
            source = PaymentRequestSource.RAW_ADDRESS
        )
    }

    private fun hasCrossScriptBase58Ambiguity(address: String): Boolean {
        val decoded = runCatching { Base58.decodeChecked(address) }.getOrNull() ?: return false
        if (decoded.second.size != 20) return false

        val scriptTypes = CoinRegistry.all.mapNotNull { candidateProfile ->
            MeowcoinAddress.parse(address, candidateProfile)?.type?.takeIf {
                it == MeowcoinAddress.Type.P2PKH || it == MeowcoinAddress.Type.P2SH
            }
        }.toSet()
        return scriptTypes.size > 1
    }

    private fun encodeComponent(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun decodeComponent(value: String): String =
        URLDecoder.decode(value, Charsets.UTF_8.name())

    private fun isFixedPointAmount(value: String, decimals: Int): Boolean {
        val pattern = if (decimals == 0) {
            Regex("[0-9]+")
        } else {
            Regex("[0-9]+(?:\\.[0-9]{1,$decimals})?")
        }
        return value.matches(pattern)
    }
}
