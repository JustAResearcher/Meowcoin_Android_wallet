package com.meowcoin.wallet.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches MEWC fiat price data from public APIs.
 * Uses CoinGecko or xeggex as fallback sources.
 */
object PriceService {

    private const val TAG = "PriceService"

    // CoinGecko API (free, no key)
    private const val COINGECKO_URL =
        "https://api.coingecko.com/api/v3/simple/price?ids=meowcoin&vs_currencies=usd,eur,gbp,btc"

    // Xeggex API fallback
    private const val XEGGEX_URL =
        "https://api.xeggex.com/api/v2/ticker/MEWC%2FUSDT"

    data class PriceData(
        val usdPrice: Double = 0.0,
        val eurPrice: Double = 0.0,
        val gbpPrice: Double = 0.0,
        val btcPrice: Double = 0.0,
        val lastUpdated: Long = 0L,
        val source: String = ""
    )

    private val _priceData = MutableStateFlow(PriceData())
    val priceData: StateFlow<PriceData> = _priceData.asStateFlow()

    /**
     * Fetch the latest MEWC price.
     * Tries CoinGecko first, then Xeggex as fallback.
     */
    suspend fun fetchPrice(): PriceData = withContext(Dispatchers.IO) {
        try {
            val data = fetchFromCoinGecko()
            if (data != null) {
                _priceData.value = data
                return@withContext data
            }
        } catch (e: Exception) {
            Log.w(TAG, "CoinGecko failed: ${e.message}")
        }

        try {
            val data = fetchFromXeggex()
            if (data != null) {
                _priceData.value = data
                return@withContext data
            }
        } catch (e: Exception) {
            Log.w(TAG, "Xeggex failed: ${e.message}")
        }

        Log.e(TAG, "All price sources failed")
        _priceData.value
    }

    private fun fetchFromCoinGecko(): PriceData? {
        val json = httpGet(COINGECKO_URL) ?: return null
        val root = JSONObject(json)
        val meowcoin = root.optJSONObject("meowcoin") ?: return null

        return PriceData(
            usdPrice = meowcoin.optDouble("usd", 0.0),
            eurPrice = meowcoin.optDouble("eur", 0.0),
            gbpPrice = meowcoin.optDouble("gbp", 0.0),
            btcPrice = meowcoin.optDouble("btc", 0.0),
            lastUpdated = System.currentTimeMillis(),
            source = "CoinGecko"
        )
    }

    private fun fetchFromXeggex(): PriceData? {
        val json = httpGet(XEGGEX_URL) ?: return null
        val root = JSONObject(json)
        val lastPrice = root.optDouble("lastPrice", 0.0)
        if (lastPrice <= 0.0) return null

        return PriceData(
            usdPrice = lastPrice,
            eurPrice = lastPrice * 0.92, // approximate EUR
            gbpPrice = lastPrice * 0.79, // approximate GBP
            btcPrice = 0.0,
            lastUpdated = System.currentTimeMillis(),
            source = "Xeggex"
        )
    }

    private fun httpGet(urlStr: String): String? {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "MeowcoinWallet/1.0")

        return try {
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().readText()
            } else {
                Log.w(TAG, "HTTP ${conn.responseCode} from $urlStr")
                null
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Format a satoshi amount to fiat display string.
     */
    fun formatFiat(satoshis: Long, currency: String = "USD"): String {
        val mewc = satoshis / 100_000_000.0
        val price = _priceData.value
        val fiatAmount = when (currency.uppercase()) {
            "USD" -> mewc * price.usdPrice
            "EUR" -> mewc * price.eurPrice
            "GBP" -> mewc * price.gbpPrice
            else -> mewc * price.usdPrice
        }
        val symbol = when (currency.uppercase()) {
            "USD" -> "$"
            "EUR" -> "€"
            "GBP" -> "£"
            else -> "$"
        }
        return "$symbol${"%.2f".format(fiatAmount)}"
    }

    /**
     * Get the current MEWC price in the given fiat currency.
     */
    fun getPrice(currency: String = "USD"): Double {
        return when (currency.uppercase()) {
            "USD" -> _priceData.value.usdPrice
            "EUR" -> _priceData.value.eurPrice
            "GBP" -> _priceData.value.gbpPrice
            else -> _priceData.value.usdPrice
        }
    }
}
