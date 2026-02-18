# Meowcoin Wallet v1.0.1 — Release Notes

**Release Date:** 2025  
**Package:** `com.meowcoin.wallet`  
**Min Android:** 8.0 (API 26)  
**Target Android:** 15 (API 35)

---

## 🐱 Major Update — HD Wallet, Multi-Address, Assets, Fiat & Biometrics!

Version 2.0.0 is a major upgrade to the Meowcoin Wallet, introducing HD wallet support with BIP39 seed phrases, multiple address management, asset viewing, fiat currency conversion, and biometric authentication.

---

## New in v2.0.0

### HD Wallet with BIP39 Mnemonic Seed Phrases
- **12-word seed phrase** — Generate a new wallet secured by a standard BIP39 mnemonic
- **Seed phrase backup** — Visual backup screen with numbered word grid and confirmation
- **Restore from mnemonic** — Import an existing HD wallet with any valid BIP39 seed phrase
- **BIP44 derivation** — Standard path `m/44'/1669'/0'/0/n` for Meowcoin (coin type 1669)
- **BIP32 key derivation** — Full hierarchical deterministic key tree from master seed
- **Legacy WIF import** — Still supported as an alternative for single-key wallets

### Multiple Address Support
- **Derive new addresses** — Generate additional receiving addresses from the HD tree
- **Address list** — View and manage all derived addresses in Settings
- **Aggregated balance** — Total balance across all addresses displayed on Home screen
- **Multi-address UTXO selection** — Transactions gather UTXOs from all addresses
- **Change addresses** — Automatic change address derivation (BIP44 internal chain)

### Asset Support
- **Assets tab** — New tab on Home screen to browse Meowcoin assets held by the wallet
- **Asset details** — Shows asset name, amount (with correct decimal formatting), reissuable badge, and IPFS hash
- **Multi-address asset aggregation** — Assets collected across all wallet addresses

### Fiat Currency Conversion
- **Live MEWC price** — Fetches USD price from CoinGecko API (Xeggex fallback)
- **Fiat balance display** — Shows approximate fiat value on the Home screen balance card
- **Multiple currencies** — Supports USD, EUR, GBP, and BTC price data

### Biometric Authentication
- **Fingerprint / Face Unlock** — Optional biometric lock for wallet access
- **Toggle in Settings** — Enable or disable biometric authentication
- **AndroidX Biometric API** — Uses system-level biometric hardware (BIOMETRIC_STRONG + BIOMETRIC_WEAK)

---

## Existing Features (from v1.0.0)

### Wallet Management
- **Create a new wallet** — Generates a secure private key on-device
- **Import existing wallet** — Restore from WIF (Wallet Import Format) private key
- **Backup private key** — Export WIF for safekeeping
- **Delete wallet** — Securely wipe all wallet data from the device

### Send & Receive
- **Send MEWC** — Enter address and amount, with real-time fee estimation
- **Receive MEWC** — Display your address as a QR code for easy sharing
- **Address validation** — Validates Meowcoin P2PKH addresses before sending
- **Copy & share address** — One-tap copy or share via system sheet

### Light Client (Electrum)
- **No full node required** — Connects to Meowcoin Electrum servers via Stratum protocol
- **Real-time balance sync** — Confirmed and unconfirmed balances
- **Transaction history** — View incoming and outgoing transactions with confirmation status
- **UTXO management** — Automatic coin selection for optimal transaction building
- **Multiple server support** — Failover across Meowcoin Electrum servers:
  - `electrum.mewccrypto.com`
  - `meowelectrum.xyz`
  - `meowelectrum2.testtopper.biz`

### Security
- **Self-custody** — Private keys never leave your device
- **Encrypted key storage** — Keys stored in Android Keystore / EncryptedSharedPreferences
- **On-device signing** — Transactions are signed locally
- **No accounts or registration** — Fully permissionless

### User Interface
- **Material Design 3** with Jetpack Compose
- **Meowcoin orange theme** 🧡
- **Tab navigation** — Home, Send, Receive, Settings
- **Pull-to-refresh** syncing
- **Toast notifications** for copy actions and send confirmations

---

## Technical Details

| Detail | Value |
|---|---|
| Language | Kotlin 2.2.10 |
| UI Framework | Jetpack Compose + Material 3 |
| Crypto Library | Bouncy Castle (secp256k1 ECDSA, HMAC-SHA512, PBKDF2) |
| HD Derivation | BIP39 + BIP32 + BIP44 |
| Network | Electrum Stratum JSON-RPC over TCP/SSL |
| Price API | CoinGecko (primary), Xeggex (fallback) |
| Local Storage | Room Database v2 |
| Key Storage | Android Keystore + EncryptedSharedPreferences |
| Biometrics | AndroidX Biometric 1.2.0-alpha05 |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 35 (Android 15) |

---

## Network Parameters

| Parameter | Value |
|---|---|
| Coin | Meowcoin (MEWC) |
| Algorithm | MeowPoW |
| Address Prefix | `M` (version byte 50) |
| BIP44 Coin Type | 1669 |
| Block Time | ~60 seconds |
| P2P Port | 8788 |
| Electrum TCP Port | 50001 |
| Electrum SSL Port | 50002 |

---

## Known Limitations

- Asset creation and transfer transactions not yet supported (view-only)
- Fiat price may be unavailable if CoinGecko and Xeggex APIs are both unreachable
- Unsigned release — use Play App Signing for distribution

---

## What's Next (Planned)

- Asset creation and transfer transactions (OP_MEWC_ASSET)
- Multi-language support
- iOS version
- WalletConnect integration

---

## Installation

Download the APK from [GitHub Releases](https://github.com/JustAResearcher/Meowcoin_Android_wallet/releases) or upload the AAB to Google Play.

---

**Your MEWC, your keys.** 🐱
