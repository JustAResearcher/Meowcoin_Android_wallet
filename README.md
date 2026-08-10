# 🐱 Multi-Coin Wallet for Android

A lightweight, open-source Android wallet for transparent Bitcoin-family payments. Meowcoin remains the home network and retains its asset and Ash Cats features.

Operational Phase 1 networks are **Meowcoin, Bitcoin, Litecoin, Dogecoin, and Pepecoin**. DigiByte, Viacoin, and Junkcoin profiles are included but disabled until each has two independent production TLS Electrum backends. Litecoin MWEB is not supported.

---

## Features

- **Send & Receive** — MEWC, BTC, LTC, DOGE, and PEP with coin-specific QR/payment URIs
- **HD Multi-Coin Wallet** — One BIP39 recovery phrase with registered BIP44 paths, plus BIP84 native SegWit receiving for MEWC and LTC
- **Electrum Light Client** — TLS-only automatic endpoints with hostname and genesis-chain checks
- **Secure Key Storage** — Private keys encrypted with AES-256-GCM via Android Keystore
- **Real-time Updates** — Subscribes to address and block notifications
- **Custom Server Support** — Point the wallet at your own Electrum node
- **Import / Export** — Import an existing MEWC WIF or restore all enabled coins from a recovery phrase
- **Transaction History** — View all incoming and outgoing transactions
- **Ash Cats Forge** — Open the mobile Forge with your public address prefilled
- **Material Design 3** — Clean, modern UI built with Jetpack Compose

---

## Screenshots

> *Coming soon — contribute screenshots by opening a PR!*

---

## Getting Started

### Prerequisites

| Tool | Version |
|------|---------|
| **Android Studio** | Ladybug (2024.2+) or newer |
| **JDK** | 17 |
| **Android SDK** | API 35 (compile), API 26+ (min) |
| **Gradle** | 9.x (included via wrapper) |

### Build & Run

1. **Clone the repo**
   ```bash
   git clone https://github.com/JustAResearcher/Meowcoin_Android_wallet.git
   cd Meowcoin_Android_wallet
   ```

2. **Open in Android Studio**
   - File → Open → select the cloned folder
   - Wait for Gradle sync to finish (this downloads all dependencies)

3. **Run on a device or emulator**
   - Click the green ▶️ Run button, or press `Shift + F10`
   - The app requires Android 8.0 (API 26) or newer

4. **Build a release APK** (optional)
   ```bash
   ./gradlew assembleRelease
   ```
   The APK will be at `app/build/outputs/apk/release/`.

The Ash Cats target defaults to `https://www.mewccrypto.com/ash-cats/`. To use
another HTTPS deployment for a build, pass
`-PashCatsUrl=https://example.com/ash-cats/` to Gradle.

> **Tip:** If you see "SDK location not found", create a `local.properties` file in the project root with:
> ```
> sdk.dir=/path/to/your/Android/Sdk
> ```

---

## Project Structure

```
MeowcoinWallet/
├── app/
│   ├── build.gradle.kts          # App dependencies & build config
│   ├── proguard-rules.pro        # ProGuard rules for release builds
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/meowcoin/wallet/
│       │   ├── MainActivity.kt            # App entry point
│       │   ├── MeowcoinApp.kt             # Application class (registers BouncyCastle)
│       │   │
│       │   ├── crypto/                    # 🔐 Cryptography layer
│       │   │   ├── Base58.kt              # Base58Check encoding (addresses)
│       │   │   ├── KeyPair.kt             # secp256k1 ECDSA keys & signing
│       │   │   ├── MeowcoinNetwork.kt     # Network constants (ports, seeds, magic bytes)
│       │   │   ├── SecureKeyStore.kt      # Encrypted key storage (Android Keystore)
│       │   │   └── Transaction.kt         # UTXO transaction building & signing
│       │   │
│       │   ├── data/                      # 💾 Data layer
│       │   │   ├── local/
│       │   │   │   ├── Entities.kt        # Room database entities
│       │   │   │   ├── Daos.kt            # Room DAOs (queries)
│       │   │   │   └── WalletDatabase.kt  # Room database singleton
│       │   │   ├── remote/
│       │   │   │   └── ElectrumClient.kt  # Electrum Stratum protocol client
│       │   │   └── repository/
│       │   │       └── WalletRepository.kt# Main repository (coordinates everything)
│       │   │
│       │   ├── ui/                        # 🎨 UI layer (Jetpack Compose)
│       │   │   ├── components/
│       │   │   │   └── WalletComponents.kt# Reusable UI components
│       │   │   ├── navigation/
│       │   │   │   └── Navigation.kt      # Screen navigation (NavHost)
│       │   │   ├── screens/
│       │   │   │   ├── HomeScreen.kt      # Main wallet view (balance, transactions)
│       │   │   │   ├── SendScreen.kt      # Multi-coin send + fee confirmation
│       │   │   │   ├── ReceiveScreen.kt   # Coin-specific QR code + address
│       │   │   │   ├── WelcomeScreen.kt   # First-run setup
│       │   │   │   └── SettingsScreen.kt  # Settings & custom server config
│       │   │   └── theme/
│       │   │       ├── Color.kt           # Meowcoin orange color palette
│       │   │       └── Theme.kt           # Material 3 theme
│       │   │
│       │   └── viewmodel/
│       │       └── WalletViewModel.kt     # UI state management
│       │
│       └── res/                           # Android resources
│           ├── drawable/                  # Launcher icon vectors
│           ├── values/                    # Colors, strings, themes
│           └── xml/                       # Network security config
│
├── build.gradle.kts              # Root build file (plugin versions)
├── settings.gradle.kts           # Project settings & repositories
├── gradle.properties             # Gradle JVM args & Android settings
└── gradle/wrapper/               # Gradle wrapper config
```

---

## How It Works

### Light Client Architecture

```
┌──────────────┐          Stratum (JSON-RPC over TLS)          ┌─────────────────┐
│              │  ◄──────────────────────────────────────────►  │   Electrum      │
│  Multi-Coin  │    • blockchain.scripthash.get_balance         │   Server        │
│  Wallet App  │    • blockchain.scripthash.listunspent         │                 │
│              │    • blockchain.transaction.broadcast           │  (indexes the   │
│  (this app)  │    • blockchain.scripthash.subscribe            │   full chain)   │
│              │    • blockchain.headers.subscribe               │                 │
└──────────────┘                                                └─────────────────┘
```

The wallet **never downloads the full blockchain**. Instead, it asks the active coin's Electrum servers for only the data it needs:

1. **Balance** — Queries UTXOs for your address
2. **History** — Fetches transaction list for your address
3. **Send** — Builds and signs transactions locally, then broadcasts via Electrum
4. **Real-time** — Subscribes to address and block updates for instant notifications

### Enabled Networks and Default TLS Servers

| Coin | Primary | Secondary |
|------|---------|-----------|
| MEWC | `electrs.mewccrypto.com:50002` | `electrs2.mewccrypto.com:50002` |
| BTC | `blockstream.info:700` | `electrum.jhoenicke.de:50002` |
| LTC | `ltc.rentonisk.com:50002` | `electrum-ltc.petrkr.net:60002` |
| DOGE | `dogecoin.stackwallet.com:50022` | `electrum1.cipig.net:20060` |
| PEP | `electrum.pepecoinservice.org:50002` | `electrum.pepe.tips:50002` |

Automatic connections never fall back to plaintext. A custom plaintext server remains an explicit advanced-user choice in **Settings**.

This is a server-trusting light client, not a fully validating SPV node: TLS authenticates the host and the client rejects a server on the wrong genesis chain, but it does not validate the complete header chain or prove that a server has not omitted history. Configure your own server in **Settings** when stronger backend control is required.

---

## Key Technical Details

| Coin | Coin type | P2PKH | P2SH | WIF | Default HD receive type |
|------|-----------|-------|------|-----|-------------------------|
| MEWC | 1669 | 50 | 122 | 112 | BIP84 P2WPKH (`mewc1q…`) |
| BTC | 0 | 0 | 5 | 128 | BIP44 P2PKH |
| LTC | 2 | 48 | 50 (accept 5) | 176 | BIP84 P2WPKH (`ltc1q…`), no MWEB |
| DOGE | 3 | 30 | 22 | 158 | BIP44 P2PKH |
| PEP | 3434 | 56 | 22 | 158 | BIP44 P2PKH |

MEWC and LTC preserve and scan their legacy `m/44'/coin_type'/0'/0|1/index` branches, while new receive and change addresses use `m/84'/coin_type'/0'/0|1/index`. Wallet-owned P2PKH and native P2WPKH inputs can be spent together; native inputs use BIP143 signing and witness-aware fee calculation. Litecoin support remains transparent-only and deliberately rejects MWEB transaction serialization.

Bare Base58 strings do not identify their originating chain. If a raw address maps to different script types in registered profiles—notably Meowcoin P2PKH versus Litecoin P2SH at version 50—the send flow blocks it and requires a matching `meowcoin:` or `litecoin:` payment URI. The final confirmation repeats the network and interpreted address type. New `mewc1q…` and `ltc1q…` receive addresses carry distinct Bech32 network prefixes and avoid that collision.

Restoring the BIP39 phrase scans both receive and change branches for BIP44 and, on MEWC/LTC, BIP84 with a gap limit of 20. Keep an accurate offline copy of the phrase; encrypted on-device storage and Electrum servers are not backups.

---

## Dependencies

| Library | Purpose |
|---------|---------|
| **Jetpack Compose** | Modern declarative UI |
| **BouncyCastle** | secp256k1 ECDSA cryptography |
| **Room** | Local SQLite database for wallets, txs, UTXOs |
| **Gson** | JSON parsing for Electrum Stratum protocol |
| **AndroidX Security** | Encrypted key storage (AES-256-GCM) |
| **ZXing** | QR code generation |
| **CameraX + ML Kit** | QR code scanning |
| **Kotlin Coroutines** | Async operations & Flow |

---

## Contributing

Contributions are welcome! Here's how to help:

1. **Fork** the repo
2. **Create a branch** for your feature: `git checkout -b feature/my-feature`
3. **Make your changes** and test them
4. **Commit** with a clear message: `git commit -m "Add: my new feature"`
5. **Push** and open a **Pull Request**

### Ideas for Contributions

- [x] BIP39 mnemonic seed phrase generation
- [x] HD wallet (coin-specific BIP44 key derivation)
- [x] Native SegWit BIP84 receive/change and BIP143 spending for MEWC/LTC
- [x] Coin-scoped wallet storage
- [x] Meowcoin asset support (tokens on the MEWC chain)
- [x] Fiat price display for MEWC
- [ ] Dark/light theme toggle
- [ ] Widget for home screen balance
- [ ] Localization (translations)
- [x] Unit tests for amount, profile, URI, address, HD, transaction, and network helpers
- [ ] CI/CD with GitHub Actions

---

## Security Notes

- **Private keys never leave the device.** They're encrypted at rest using AES-256-GCM backed by Android Keystore hardware.
- **Transactions are signed locally.** The Electrum server only sees the final signed transaction, never your keys.
- **Previous outputs are verified before signing.** Values and scripts returned by Electrum must match the raw transaction and txid.
- **Fees require confirmation.** The exact signed amount and bounded fee are shown before broadcast.
- **Coin data is isolated.** Addresses, private keys, transactions, and UTXOs are scoped by a stable coin ID.
- **Secrets are screen-capture protected.** The app re-locks biometric wallets after backgrounding and uses Android's secure-window flag.
- **Electrum servers are trusted for completeness.** Genesis pinning prevents an accidental wrong-chain connection but is not full consensus verification.
- **Cloud backup is disabled** (`android:allowBackup="false"`) to prevent key leakage.
- **This is experimental software.** Use at your own risk. Start with small amounts.

---

## Meowcoin Resources

- **Website:** [meowcoin.cc](https://mewccrypto.com)
- **GitHub:** [Meowcoin-Foundation](https://github.com/Meowcoin-Foundation)
- **Explorer:** [explorer.mewccrypto.com](https://explorer.mewccrypto.com)
- **Discord:** [Meowcoin Community](https://discord.gg/meowcoin)

---

## License

This project is open source. See [LICENSE](LICENSE) for details.

---

*Built with 🐱 for the Meowcoin community.*
