package com.meowcoin.wallet.crypto

import java.math.BigDecimal
import java.math.RoundingMode

/** Exact conversion between display amounts and a coin's smallest atomic unit. */
object AmountCodec {
    fun parseAtomic(amount: String, profile: CoinProfile = CoinRegistry.MEWC): Long {
        val normalized = amount.trim()
        require(normalized.isNotEmpty()) { "Amount must not be blank" }
        return try {
            toAtomic(BigDecimal(normalized), profile)
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("Invalid ${profile.ticker} amount: $amount", e)
        }
    }

    fun toAtomic(amount: BigDecimal, profile: CoinProfile = CoinRegistry.MEWC): Long {
        return try {
            amount
                .setScale(profile.decimals, RoundingMode.UNNECESSARY)
                .movePointRight(profile.decimals)
                .longValueExact()
        } catch (e: ArithmeticException) {
            throw IllegalArgumentException(
                "${profile.ticker} supports at most ${profile.decimals} decimal places and must fit in 64 bits",
                e
            )
        }
    }

    fun fromAtomic(amount: Long, profile: CoinProfile = CoinRegistry.MEWC): BigDecimal =
        BigDecimal.valueOf(amount, profile.decimals)

    fun formatAtomic(
        amount: Long,
        profile: CoinProfile = CoinRegistry.MEWC,
        trimTrailingZeros: Boolean = true
    ): String {
        val decimal = fromAtomic(amount, profile)
        return if (trimTrailingZeros) {
            decimal.stripTrailingZeros().toPlainString()
        } else {
            decimal.setScale(profile.decimals).toPlainString()
        }
    }
}
