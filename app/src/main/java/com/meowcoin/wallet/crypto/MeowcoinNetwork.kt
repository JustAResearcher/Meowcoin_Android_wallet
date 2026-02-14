package com.meowcoin.wallet.crypto

/**
 * Meowcoin network constants and parameters.
 * Source: https://github.com/Meowcoin-Foundation/Meowcoin/blob/main/src/chainparams.cpp
 */
object MeowcoinNetwork {

    // ── Network Identity ──
    const val COIN_NAME = "Meowcoin"
    const val COIN_TICKER = "MEWC"
    const val BIP44_COIN_TYPE = 1669

    // ── Message Start (Magic Bytes) ──
    val MAINNET_MAGIC = byteArrayOf(0x4D, 0x45, 0x57, 0x43) // "MEWC"
    val TESTNET_MAGIC = byteArrayOf(0x6E, 0x66, 0x78, 0x64) // "nfxd"

    // ── Ports ──
    const val MAINNET_P2P_PORT = 8788
    const val MAINNET_RPC_PORT = 9766
    const val TESTNET_P2P_PORT = 4569
    const val TESTNET_RPC_PORT = 18766

    // ── Address Versions (Base58) ──
    const val PUBKEY_ADDRESS_VERSION = 50     // 0x32 → 'M'
    const val SCRIPT_ADDRESS_VERSION = 122    // 0x7A → 'm' (P2SH)
    const val SECRET_KEY_VERSION = 112        // 0x70 (WIF)

    // ── Extended Key Versions ──
    val EXT_PUBLIC_KEY = byteArrayOf(0x04, 0x88.toByte(), 0xB2.toByte(), 0x1E)
    val EXT_SECRET_KEY = byteArrayOf(0x04, 0x88.toByte(), 0xAD.toByte(), 0xE4.toByte())

    // ── Segwit ──
    const val BECH32_HRP = "mc"

    // ── DNS Seeds ──
    val DNS_SEEDS = listOf(
        "seed-mainnet-mewc.meowcoin.cc",
        "dnsseed.nodeslist.xyz"
    )

    // ── Hard-coded Seed Nodes (mainnet) ──
    val SEED_NODES = listOf(
        "168.119.35.111" to MAINNET_P2P_PORT,
        "173.212.231.94" to MAINNET_P2P_PORT,
        "99.243.50.189" to MAINNET_P2P_PORT,
        "136.32.255.111" to MAINNET_P2P_PORT,
        "213.91.128.133" to MAINNET_P2P_PORT
    )

    // ── Electrum (Stratum) Servers ──
    val ELECTRUM_SERVERS = listOf(
        ElectrumServer("electrum.mewccrypto.com", 50001, 50002),
        ElectrumServer("meowelectrum.xyz", 50001, 50002),
        ElectrumServer("meowelectrum2.testtopper.biz", 50001, 50002)
    )

    // ── Block Parameters ──
    const val BLOCK_TIME_SECONDS = 60              // 1 minute target
    const val INITIAL_BLOCK_REWARD = 5000_00000000L // 5000 MEWC in satoshis
    const val HALVING_INTERVAL = 2_100_000          // blocks
    const val COIN_DECIMALS = 8
    const val COIN_MULTIPLIER = 100_000_000L        // 1 MEWC = 10^8 satoshis

    // ── Special Addresses ──
    const val COMMUNITY_FUND_ADDRESS = "MPyNGZSSZ4rbjkVJRLn3v64pMcktpEYJnU"
    const val BURN_ADDRESS = "MCBurnXXXXXXXXXXXXXXXXXXXXXXUkdzqy"

    // ── Genesis Block ──
    const val GENESIS_HASH = "000000edd819220359469c54f2614b5602ebc775ea67a64602f354bdaa320f70"
    const val GENESIS_TIMESTAMP = 1661730843L
    const val GENESIS_MESSAGE = "The WSJ 08/28/2022 Investors Ramp Up Bets Against Stock Market"

    data class ElectrumServer(
        val host: String,
        val tcpPort: Int,    // Unencrypted TCP
        val sslPort: Int     // SSL/TLS
    )
}
