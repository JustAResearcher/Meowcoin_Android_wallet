package com.meowcoin.wallet.crypto

/**
 * HD wallet implementation using BIP39/BIP32 with BIP44 legacy and BIP84 native-SegWit
 * branches under an explicit [profile].
 *
 * Derivation paths: m/44'/coin_type'/0'/change/index and
 * m/84'/coin_type'/0'/change/index.
 * - 44'/84' = legacy P2PKH / native P2WPKH purpose
 * - coin_type' = the profile's registered SLIP-44 coin type
 * - 0'      = Account 0
 * - 0       = External (receiving) chain
 * - 1       = Internal (change) chain
 */
class HdWallet private constructor(
    private val masterKey: Bip32.ExtendedKey,
    val mnemonic: String,
    val profile: CoinProfile
) {
    companion object {
        /**
         * Create a new HD wallet with a fresh mnemonic.
         * @param wordCount 12 or 24 word mnemonic
         */
        fun create(wordCount: Int = 12): HdWallet {
            return create(CoinRegistry.MEWC, wordCount)
        }

        fun create(profile: CoinProfile, wordCount: Int = 12): HdWallet {
            val mnemonic = Bip39.generateMnemonic(wordCount)
            val seed = Bip39.mnemonicToSeed(mnemonic)
            val masterKey = Bip32.masterKeyFromSeed(seed)
            return HdWallet(masterKey, mnemonic, profile)
        }

        /**
         * Restore an HD wallet from an existing mnemonic.
         * @param mnemonic Space-separated BIP39 mnemonic phrase
         * @param passphrase Optional BIP39 passphrase
         */
        fun fromMnemonic(mnemonic: String, passphrase: String = ""): HdWallet {
            return fromMnemonic(mnemonic, CoinRegistry.MEWC, passphrase)
        }

        fun fromMnemonic(
            mnemonic: String,
            profile: CoinProfile,
            passphrase: String = "",
            derivationVersion: Int = Bip39.CANONICAL_DERIVATION_VERSION
        ): HdWallet {
            require(Bip39.validateMnemonic(mnemonic)) { "Invalid mnemonic phrase" }
            val seed = Bip39.mnemonicToSeedForVersion(
                mnemonic,
                passphrase,
                derivationVersion
            )
            val masterKey = Bip32.masterKeyFromSeed(seed)
            return HdWallet(masterKey, mnemonic, profile)
        }
    }

    /**
     * Derive a receiving address key pair at the given index.
     * Path: m/44'/coin_type'/0'/0/index
     */
    fun deriveReceivingKey(index: Int): MeowcoinKeyPair =
        deriveReceivingKey(index, Bip32.BIP44_PURPOSE)

    fun deriveReceivingKey(index: Int, derivationPurpose: Int): MeowcoinKeyPair {
        requirePurposeSupported(derivationPurpose)
        val extKey = Bip32.deriveCoinKey(
            masterKey,
            profile,
            account = 0,
            change = 0,
            addressIndex = index,
            purpose = derivationPurpose
        )
        return extKey.toKeyPair(profile)
    }

    /**
     * Derive a change address key pair at the given index.
     * Path: m/44'/coin_type'/0'/1/index
     */
    fun deriveChangeKey(index: Int): MeowcoinKeyPair =
        deriveChangeKey(index, Bip32.BIP44_PURPOSE)

    fun deriveChangeKey(index: Int, derivationPurpose: Int): MeowcoinKeyPair {
        requirePurposeSupported(derivationPurpose)
        val extKey = Bip32.deriveCoinKey(
            masterKey,
            profile,
            account = 0,
            change = 1,
            addressIndex = index,
            purpose = derivationPurpose
        )
        return extKey.toKeyPair(profile)
    }

    /**
     * Derive a receiving address at the given index.
     */
    fun deriveReceivingAddress(index: Int): String =
        deriveReceivingAddress(index, Bip32.BIP44_PURPOSE)

    fun deriveReceivingAddress(index: Int, derivationPurpose: Int): String {
        val key = deriveReceivingKey(index, derivationPurpose)
        return key.addressForPurpose(derivationPurpose)
    }

    /**
     * Derive a change address at the given index.
     */
    fun deriveChangeAddress(index: Int): String =
        deriveChangeAddress(index, Bip32.BIP44_PURPOSE)

    fun deriveChangeAddress(index: Int, derivationPurpose: Int): String {
        val key = deriveChangeKey(index, derivationPurpose)
        return key.addressForPurpose(derivationPurpose)
    }

    fun deriveNativeSegwitReceivingKey(index: Int): MeowcoinKeyPair =
        deriveReceivingKey(index, Bip32.BIP84_PURPOSE)

    fun deriveNativeSegwitChangeKey(index: Int): MeowcoinKeyPair =
        deriveChangeKey(index, Bip32.BIP84_PURPOSE)

    fun deriveNativeSegwitReceivingAddress(index: Int): String =
        deriveReceivingAddress(index, Bip32.BIP84_PURPOSE)

    fun deriveNativeSegwitChangeAddress(index: Int): String =
        deriveChangeAddress(index, Bip32.BIP84_PURPOSE)

    /**
     * Generate multiple receiving addresses.
     * @param count Number of addresses to generate
     * @param startIndex Starting index (default 0)
     * @return List of (index, address) pairs
     */
    fun deriveReceivingAddresses(
        count: Int,
        startIndex: Int = 0
    ): List<Pair<Int, String>> = deriveReceivingAddresses(
        count,
        startIndex,
        Bip32.BIP44_PURPOSE
    )

    fun deriveReceivingAddresses(
        count: Int,
        startIndex: Int,
        derivationPurpose: Int
    ): List<Pair<Int, String>> {
        require(count >= 0) { "Address count must be non-negative" }
        return (startIndex until startIndex + count).map { index ->
            index to deriveReceivingAddress(index, derivationPurpose)
        }
    }

    /**
     * Get the key pair for a specific address derived from this wallet.
     * Scans both receiving and change chains up to maxScan indices.
     */
    fun findKeyForAddress(address: String, maxScan: Int = 50): MeowcoinKeyPair? {
        require(maxScan >= 0) { "Scan limit must be non-negative" }
        val parsed = MeowcoinAddress.parse(address, profile) ?: return null
        val purpose = when (parsed.type) {
            MeowcoinAddress.Type.P2PKH -> Bip32.BIP44_PURPOSE
            MeowcoinAddress.Type.P2WPKH -> Bip32.BIP84_PURPOSE
            else -> return null
        }
        if (purpose == Bip32.BIP84_PURPOSE && !supportsNativeSegwit()) return null

        for (change in 0..1) {
            for (i in 0 until maxScan) {
                val key = if (change == 0) {
                    deriveReceivingKey(i, purpose)
                } else {
                    deriveChangeKey(i, purpose)
                }
                if (key.addressForPurpose(purpose) == address) return key
            }
        }
        return null
    }

    private fun MeowcoinKeyPair.addressForPurpose(purpose: Int): String = when (purpose) {
        Bip32.BIP44_PURPOSE -> toAddress()
        Bip32.BIP84_PURPOSE -> toP2WPKHAddress()
        else -> throw IllegalArgumentException("Unsupported derivation purpose: $purpose")
    }

    private fun requirePurposeSupported(purpose: Int) {
        require(purpose == Bip32.BIP44_PURPOSE || purpose == Bip32.BIP84_PURPOSE) {
            "Only BIP44 and BIP84 derivation are supported"
        }
        if (purpose == Bip32.BIP84_PURPOSE) {
            require(supportsNativeSegwit()) {
                "Native SegWit receiving is currently enabled only for MEWC and LTC"
            }
        }
    }

    private fun supportsNativeSegwit(): Boolean =
        profile.id == CoinRegistry.MEWC.id || profile.id == CoinRegistry.LTC.id
}
