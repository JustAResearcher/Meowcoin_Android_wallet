package com.meowcoin.wallet.crypto

data class ElectrumEndpoint(
    val host: String,
    val tcpPort: Int? = null,
    val sslPort: Int? = null
) {
    init {
        require(host.isNotBlank()) { "Electrum host must not be blank" }
        require(tcpPort != null || sslPort != null) { "Electrum endpoint needs a TCP or TLS port" }
        require(tcpPort == null || tcpPort in 1..65535) { "Invalid Electrum TCP port" }
        require(sslPort == null || sslPort in 1..65535) { "Invalid Electrum TLS port" }
    }
}

/**
 * Mainnet parameters needed by the wallet's Bitcoin-family crypto layer.
 *
 * A profile deliberately contains no mutable wallet state. It is safe to share across wallets,
 * while keys, addresses, databases, and backends remain explicitly scoped by [id].
 */
data class CoinProfile(
    val id: String,
    val name: String,
    val ticker: String,
    val uriScheme: String,
    val bip44CoinType: Int,
    val pubKeyAddressVersion: Int,
    val scriptAddressVersions: Set<Int>,
    val wifVersion: Int,
    val acceptedWifVersions: Set<Int> = setOf(wifVersion),
    val extendedPublicKeyVersion: Int = 0x0488B21E,
    val extendedPrivateKeyVersion: Int = 0x0488ADE4,
    val bech32Hrp: String? = null,
    val transactionVersion: Int,
    val dustThreshold: Long,
    val defaultFeeRate: Long,
    val decimals: Int = 8,
    val genesisHash: String,
    val tickerAliases: Set<String> = emptySet(),
    val electrumServers: List<ElectrumEndpoint> = emptyList(),
    val enabled: Boolean = true,
    val disabledReason: String? = null
) {
    init {
        require(id.matches(Regex("[a-z0-9]+"))) { "Coin id must be lowercase alphanumeric" }
        require(ticker.isNotBlank()) { "Coin ticker must not be blank" }
        require(uriScheme.matches(Regex("[a-z][a-z0-9+.-]*"))) { "Invalid payment URI scheme" }
        require(bip44CoinType >= 0) { "BIP44 coin type must be non-negative" }
        require(pubKeyAddressVersion in 0..255) { "P2PKH version must fit in one byte" }
        require(scriptAddressVersions.isNotEmpty()) { "At least one P2SH version is required" }
        require(scriptAddressVersions.all { it in 0..255 }) { "P2SH versions must fit in one byte" }
        require(wifVersion in 0..255) { "WIF version must fit in one byte" }
        require(acceptedWifVersions.isNotEmpty() && acceptedWifVersions.all { it in 0..255 }) {
            "Accepted WIF versions must fit in one byte"
        }
        require(wifVersion in acceptedWifVersions) { "Primary WIF version must be accepted" }
        require(transactionVersion > 0) { "Transaction version must be positive" }
        require(dustThreshold >= 0) { "Dust threshold must be non-negative" }
        require(defaultFeeRate > 0) { "Fee rate must be positive" }
        require(decimals in 0..18) { "Coin decimals must be between 0 and 18" }
        require(genesisHash.matches(Regex("[0-9a-f]{64}"))) { "Genesis hash must be lowercase hex" }
        require(enabled || !disabledReason.isNullOrBlank()) { "Disabled coins need a reason" }
    }

    val primaryScriptAddressVersion: Int
        get() = scriptAddressVersions.first()

    val coinType: Int
        get() = bip44CoinType
}

/** Phase 1 mainnet registry. Profiles without independent, verified backends remain gated. */
object CoinRegistry {
    val MEWC = CoinProfile(
        id = "mewc",
        name = "Meowcoin",
        ticker = "MEWC",
        uriScheme = "meowcoin",
        bip44CoinType = 1669,
        pubKeyAddressVersion = 50,
        scriptAddressVersions = linkedSetOf(122),
        wifVersion = 112,
        bech32Hrp = "mewc",
        transactionVersion = 2,
        dustThreshold = 100_000L,
        defaultFeeRate = 1_000L,
        genesisHash = "000000edd819220359469c54f2614b5602ebc775ea67a64602f354bdaa320f70",
        electrumServers = listOf(
            ElectrumEndpoint("electrs.mewccrypto.com", 50001, 50002),
            ElectrumEndpoint("electrs2.mewccrypto.com", 50001, 50002),
            ElectrumEndpoint("electrs3.meowcoin.org", 50001, 50002),
            ElectrumEndpoint("electrs4.meowcoin.org", 50001, 50002),
            ElectrumEndpoint("electrs5.meowcoin.org", 50001, 50002)
        )
    )

    val BTC = CoinProfile(
        id = "btc",
        name = "Bitcoin",
        ticker = "BTC",
        uriScheme = "bitcoin",
        bip44CoinType = 0,
        pubKeyAddressVersion = 0,
        scriptAddressVersions = linkedSetOf(5),
        wifVersion = 128,
        bech32Hrp = "bc",
        transactionVersion = 2,
        dustThreshold = 546L,
        defaultFeeRate = 1L,
        genesisHash = "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f",
        electrumServers = listOf(
            ElectrumEndpoint("blockstream.info", sslPort = 700),
            ElectrumEndpoint("electrum.jhoenicke.de", sslPort = 50002)
        )
    )

    val LTC = CoinProfile(
        id = "ltc",
        name = "Litecoin",
        ticker = "LTC",
        uriScheme = "litecoin",
        bip44CoinType = 2,
        pubKeyAddressVersion = 48,
        scriptAddressVersions = linkedSetOf(50, 5),
        wifVersion = 176,
        bech32Hrp = "ltc",
        transactionVersion = 2,
        dustThreshold = 5_460L,
        defaultFeeRate = 1L,
        genesisHash = "12a765e31ffd4059bada1e25190f6e98c99d9714d334efa41a195a7e7e04bfe2",
        electrumServers = listOf(
            ElectrumEndpoint("ltc.rentonisk.com", sslPort = 50002),
            ElectrumEndpoint("electrum-ltc.petrkr.net", sslPort = 60002)
        )
    )

    val DOGE = CoinProfile(
        id = "doge",
        name = "Dogecoin",
        ticker = "DOGE",
        uriScheme = "dogecoin",
        bip44CoinType = 3,
        pubKeyAddressVersion = 30,
        scriptAddressVersions = linkedSetOf(22),
        wifVersion = 158,
        extendedPublicKeyVersion = 0x02FACAFD,
        extendedPrivateKeyVersion = 0x02FAC398,
        transactionVersion = 1,
        dustThreshold = 1_000_000L,
        defaultFeeRate = 1_000L,
        genesisHash = "1a91e3dace36e2be3bf030a65679fe821aa1d6ef92e7c9902eb318182c355691",
        electrumServers = listOf(
            ElectrumEndpoint("dogecoin.stackwallet.com", sslPort = 50022),
            ElectrumEndpoint("electrum1.cipig.net", sslPort = 20060)
        )
    )

    val DGB = CoinProfile(
        id = "dgb",
        name = "DigiByte",
        ticker = "DGB",
        uriScheme = "digibyte",
        bip44CoinType = 20,
        pubKeyAddressVersion = 30,
        scriptAddressVersions = linkedSetOf(63, 5),
        wifVersion = 128,
        acceptedWifVersions = linkedSetOf(128, 158),
        bech32Hrp = "dgb",
        transactionVersion = 2,
        dustThreshold = 5_460L,
        defaultFeeRate = 100L,
        genesisHash = "7497ea1b465eb39f1c8f507bc877078fe016d6fcb6dfad3a64c98dcc6e1e8496",
        enabled = false,
        disabledReason = "Only two live TLS endpoints passed, and both share one operator"
    )

    val VIA = CoinProfile(
        id = "via",
        name = "Viacoin",
        ticker = "VIA",
        uriScheme = "viacoin",
        bip44CoinType = 14,
        pubKeyAddressVersion = 71,
        scriptAddressVersions = linkedSetOf(33),
        wifVersion = 199,
        bech32Hrp = "via",
        transactionVersion = 2,
        dustThreshold = 54_600L,
        defaultFeeRate = 100L,
        genesisHash = "4e9b54001f9976049830128ec0331515eaabe35a70970d79971da1539a400ba1",
        enabled = false,
        disabledReason = "No live TLS Electrum backend passed acceptance tests"
    )

    val PEP = CoinProfile(
        id = "pep",
        name = "Pepecoin",
        ticker = "PEP",
        tickerAliases = setOf("PEPE"),
        uriScheme = "pepecoin",
        bip44CoinType = 3434,
        pubKeyAddressVersion = 56,
        scriptAddressVersions = linkedSetOf(22),
        wifVersion = 158,
        extendedPublicKeyVersion = 0x02FACAFD,
        extendedPrivateKeyVersion = 0x02FAC398,
        transactionVersion = 1,
        dustThreshold = 1_000_000L,
        defaultFeeRate = 1_000L,
        genesisHash = "37981c0c48b8d48965376c8a42ece9a0838daadb93ff975cb091f57f8c2a5faa",
        electrumServers = listOf(
            ElectrumEndpoint("electrum.pepecoinservice.org", sslPort = 50002),
            ElectrumEndpoint("electrum.pepe.tips", sslPort = 50002)
        )
    )

    val JKC = CoinProfile(
        id = "jkc",
        name = "Junkcoin",
        ticker = "JKC",
        uriScheme = "junkcoin",
        bip44CoinType = 2013,
        pubKeyAddressVersion = 16,
        scriptAddressVersions = linkedSetOf(5),
        wifVersion = 144,
        transactionVersion = 1,
        dustThreshold = 100_000L,
        defaultFeeRate = 1_000L,
        genesisHash = "a2effa738145e377e08a61d76179c21703e13e48910b30a2a87f0dfe794b64c6",
        enabled = false,
        disabledReason = "No production Electrum backend has passed wallet acceptance tests"
    )

    val all: List<CoinProfile> = listOf(MEWC, BTC, LTC, DOGE, DGB, VIA, PEP, JKC)
    val enabled: List<CoinProfile> = all.filter(CoinProfile::enabled)

    private val profilesById = all.associateBy { it.id }
    private val profilesByScheme = all.associateBy { it.uriScheme }
    private val profilesByTicker = buildMap {
        all.forEach { profile ->
            put(profile.ticker.uppercase(), profile)
            profile.tickerAliases.forEach { put(it.uppercase(), profile) }
        }
    }

    fun findById(id: String): CoinProfile? = profilesById[id.trim().lowercase()]

    fun byId(id: String): CoinProfile? = findById(id)

    fun requireById(id: String): CoinProfile =
        findById(id) ?: throw IllegalArgumentException("Unsupported coin id: $id")

    fun findByTicker(ticker: String): CoinProfile? = profilesByTicker[ticker.trim().uppercase()]

    fun findByUriScheme(scheme: String): CoinProfile? = profilesByScheme[scheme.trim().lowercase()]

    fun requireEnabled(id: String): CoinProfile {
        val profile = requireById(id)
        require(profile.enabled) { profile.disabledReason ?: "${profile.name} is disabled" }
        return profile
    }
}
