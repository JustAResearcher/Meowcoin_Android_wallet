package com.meowcoin.wallet.crypto

/**
 * HD Wallet implementation for Meowcoin using BIP39/BIP32/BIP44.
 *
 * Derivation path: m/44'/1669'/0'/change/index
 * - 44'     = BIP44 purpose
 * - 1669'   = Meowcoin coin type
 * - 0'      = Account 0
 * - 0       = External (receiving) chain
 * - 1       = Internal (change) chain
 */
class HdWallet private constructor(
    private val masterKey: Bip32.ExtendedKey,
    val mnemonic: String
) {
    companion object {
        /**
         * Create a new HD wallet with a fresh mnemonic.
         * @param wordCount 12 or 24 word mnemonic
         */
        fun create(wordCount: Int = 12): HdWallet {
            val mnemonic = Bip39.generateMnemonic(wordCount)
            val seed = Bip39.mnemonicToSeed(mnemonic)
            val masterKey = Bip32.masterKeyFromSeed(seed)
            return HdWallet(masterKey, mnemonic)
        }

        /**
         * Restore an HD wallet from an existing mnemonic.
         * @param mnemonic Space-separated BIP39 mnemonic phrase
         * @param passphrase Optional BIP39 passphrase
         */
        fun fromMnemonic(mnemonic: String, passphrase: String = ""): HdWallet {
            require(Bip39.validateMnemonic(mnemonic)) { "Invalid mnemonic phrase" }
            val seed = Bip39.mnemonicToSeed(mnemonic, passphrase)
            val masterKey = Bip32.masterKeyFromSeed(seed)
            return HdWallet(masterKey, mnemonic)
        }
    }

    /**
     * Derive a receiving address key pair at the given index.
     * Path: m/44'/1669'/0'/0/index
     */
    fun deriveReceivingKey(index: Int): MeowcoinKeyPair {
        val extKey = Bip32.deriveMeowcoinKey(masterKey, account = 0, change = 0, addressIndex = index)
        return extKey.toKeyPair()
    }

    /**
     * Derive a change address key pair at the given index.
     * Path: m/44'/1669'/0'/1/index
     */
    fun deriveChangeKey(index: Int): MeowcoinKeyPair {
        val extKey = Bip32.deriveMeowcoinKey(masterKey, account = 0, change = 1, addressIndex = index)
        return extKey.toKeyPair()
    }

    /**
     * Derive a receiving address at the given index.
     */
    fun deriveReceivingAddress(index: Int): String {
        return deriveReceivingKey(index).toAddress()
    }

    /**
     * Derive a change address at the given index.
     */
    fun deriveChangeAddress(index: Int): String {
        return deriveChangeKey(index).toAddress()
    }

    /**
     * Generate multiple receiving addresses.
     * @param count Number of addresses to generate
     * @param startIndex Starting index (default 0)
     * @return List of (index, address) pairs
     */
    fun deriveReceivingAddresses(count: Int, startIndex: Int = 0): List<Pair<Int, String>> {
        return (startIndex until startIndex + count).map { index ->
            index to deriveReceivingAddress(index)
        }
    }

    /**
     * Get the key pair for a specific address derived from this wallet.
     * Scans both receiving and change chains up to maxScan indices.
     */
    fun findKeyForAddress(address: String, maxScan: Int = 50): MeowcoinKeyPair? {
        // Search receiving chain
        for (i in 0 until maxScan) {
            val key = deriveReceivingKey(i)
            if (key.toAddress() == address) return key
        }
        // Search change chain
        for (i in 0 until maxScan) {
            val key = deriveChangeKey(i)
            if (key.toAddress() == address) return key
        }
        return null
    }
}
