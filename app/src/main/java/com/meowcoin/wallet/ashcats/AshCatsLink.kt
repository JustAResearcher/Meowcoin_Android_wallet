package com.meowcoin.wallet.ashcats

import java.net.URI
import java.net.URLEncoder

private const val ANDROID_WALLET_SOURCE = "android-wallet"

internal fun buildAshCatsUrl(baseUrl: String, ownerAddress: String): String {
    val uri = URI(baseUrl)
    require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
        "Ash Cats URL must use HTTPS"
    }

    val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
    val baseWithoutFragment = baseUrl.substringBefore('#').trimEnd('?', '&')
    val separator = if ('?' in baseWithoutFragment) '&' else '?'
    val encodedOwner = URLEncoder.encode(ownerAddress.trim(), "UTF-8")

    return buildString {
        append(baseWithoutFragment)
        append(separator)
        append("source=")
        append(ANDROID_WALLET_SOURCE)
        if (encodedOwner.isNotEmpty()) {
            append("&owner=")
            append(encodedOwner)
        }
        append(fragment)
    }
}
