# 🐱 Meowcoin Wallet for Android

A lightweight, open-source Android wallet for **Meowcoin (MEWC)** — a community-driven cryptocurrency forked from Ravencoin.

This wallet connects directly to Electrum servers using the **Stratum protocol**, so it works as an **SPV light client**. You don't need to download the full blockchain — just install and go.

---

## Features

- **Send & Receive MEWC** — Scan QR codes or paste addresses
- **Light Client (SPV)** — Connects to Electrum servers, no full node required
- **Secure Key Storage** — Private keys encrypted with AES-256-GCM via Android Keystore
- **Real-time Updates** — Subscribes to address and block notifications
- **Custom Server Support** — Point the wallet at your own Electrum node
- **Import / Export** — Import existing wallets via WIF private key
- **Transaction History** — View all incoming and outgoing transactions
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
│       │   │   │   ├── SendScreen.kt      # Send MEWC
│       │   │   │   ├── ReceiveScreen.kt   # Receive MEWC (QR code + address)
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
┌──────────────┐        Stratum (JSON-RPC over TCP/SSL)        ┌─────────────────┐
│              │  ◄──────────────────────────────────────────►  │   Electrum      │
│  Meowcoin    │    • blockchain.scripthash.get_balance         │   Server        │
│  Wallet App  │    • blockchain.scripthash.listunspent         │                 │
│              │    • blockchain.transaction.broadcast           │  (indexes the   │
│  (this app)  │    • blockchain.scripthash.subscribe            │   full chain)   │
│              │    • blockchain.headers.subscribe               │                 │
└──────────────┘                                                └─────────────────┘
```

The wallet **never downloads the full blockchain**. Instead, it asks Electrum servers for only the data it needs:

1. **Balance** — Queries UTXOs for your address
2. **History** — Fetches transaction list for your address
3. **Send** — Builds and signs transactions locally, then broadcasts via Electrum
4. **Real-time** — Subscribes to address and block updates for instant notifications

### Default Electrum Servers

| Server | TCP Port | SSL Port |
|--------|----------|----------|
| `electrum.mewccrypto.com` | 50001 | 50002 |
| `meowelectrum.xyz` | 50001 | 50002 |
| `meowelectrum2.testtopper.biz` | 50001 | 50002 |

You can configure a custom server in **Settings** if you run your own node.

---

## Key Technical Details

| Parameter | Value |
|-----------|-------|
| **Curve** | secp256k1 (same as Bitcoin) |
| **Address Version** | 50 (0x32) → addresses start with **M** |
| **WIF Version** | 112 (0x70) |
| **P2SH Version** | 122 (0x7A) → addresses start with **m** |
| **BIP44 Coin Type** | 1669 |
| **Block Time** | 60 seconds |
| **P2P Port** | 8788 |
| **Magic Bytes** | `M E W C` (0x4D454743) |

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

- [ ] BIP39 mnemonic seed phrase generation
- [ ] HD wallet (BIP44 key derivation)
- [ ] Multi-wallet support
- [ ] Meowcoin asset support (tokens on the MEWC chain)
- [ ] Fiat price display
- [ ] Dark/light theme toggle
- [ ] Widget for home screen balance
- [ ] Localization (translations)
- [ ] Unit tests for crypto layer
- [ ] CI/CD with GitHub Actions

---

## Security Notes

- **Private keys never leave the device.** They're encrypted at rest using AES-256-GCM backed by Android Keystore hardware.
- **Transactions are signed locally.** The Electrum server only sees the final signed transaction, never your keys.
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
