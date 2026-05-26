# Meowcoin Wallet — Release Notes

## v1.0.5 — HD wallet restore actually finds your coins

Restoring an HD wallet from a seed phrase only ever derived one address (`m/44'/1669'/0'/0/0`), so funds sitting on a change address or any receiving index past 0 were invisible — the Settings screen showed `Addresses: 1` and balance `0.00000000` even when the seed was correct.

- **Fix:** `WalletRepository.discoverHdAddresses()` walks both the receiving (`change=0`) and change (`change=1`) chains with the standard BIP44 gap limit of 20, querying Electrum `blockchain.scripthash.get_history` for each derived address. Any address with on-chain activity is persisted with its private key, so balances, history, and signing all work afterwards. The next-derivation pointers (`nextReceivingIndex`, `nextChangeIndex`) advance past the highest discovered index so future "New Address" and automatic change outputs don't collide with used ones.
- **Restore flow:** `importHdWallet` now triggers discovery automatically once the Electrum connection comes up, before subscriptions, so newly-found addresses also receive push notifications.
- **Settings → Address Management → Rescan Addresses:** new button to re-run discovery on demand without deleting and reimporting. Useful if you sent from another wallet that put funds on a change address, or if you used the same seed in a desktop wallet that derived past index 0.
- **Cosmetic:** the "About" line at the bottom of Settings now shows the current version (was stuck on `v1.0.1` since the 1.0.1 release).

### Limitations still present
- Discovery is P2PKH-only. If your funds sit on a SegWit (`mewc1q…`) or Taproot (`mewc1p…`) address derived from the same seed in another wallet, they won't appear. A separate `m/84'/1669'/…` derivation path would be needed.
- Discovery runs synchronously inside the loading spinner — for a clean wallet it's ~40 sequential Electrum round-trips. Fast in practice; could be batched if it ever feels slow.

## v1.0.4 — APEX electrum server migration

The default Electrum server list and the cleartext-traffic whitelist were pointing at hosts that went offline with the APEX upgrade, so the wallet could not connect for many users. Replaced with the current canonical `electrs-mewc` (protocol 1.4) endpoints published by the Meowcoin Foundation:

- `electrs.mewccrypto.com`
- `electrs2.mewccrypto.com`
- `electrs3.meowcoin.org`
- `electrs4.meowcoin.org`
- `electrs5.meowcoin.org`

All five serve SSL on 50002; the client tries SSL first and falls back to TCP/50001 where available. No protocol changes — the wallet already negotiates Electrum 1.4, which matches the new servers.

## v1.0.3 — APEX (Meow_v30.2.0) compatibility

- **Bech32 / Bech32m address support (BIP173 / BIP350)** — the wallet can now send to native SegWit (`mewc1q…`) and Taproot (`mewc1p…`) addresses, which become available across the network from APEX.
- **Address dispatch refactor** — sending to any of P2PKH (`M…`), P2SH (`m…`), P2WPKH, P2WSH, or P2TR now produces the correct `scriptPubKey` and Electrum `scripthash`.
- **Fixed bech32 HRP** — was a dummy `mc`, now `mewc` (mainnet) and `tmewc` (testnet) per Meowcoin chainparams.

Receive addresses remain legacy P2PKH for now (HD path `m/44'/1669'/0'/…`), so existing wallets keep working without migration.

---

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
