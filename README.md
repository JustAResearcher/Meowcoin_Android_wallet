# Meowcoin Android Wallet

A secure, lightweight Android wallet for **Meowcoin (MEWC)** — powered by Electrum servers.

## Features

- **Create / Import Wallets** — Generate a new wallet or import via WIF private key
- **Send & Receive MEWC** — Simple transaction flow with QR code support
- **Light Client** — Connects to Electrum servers (no full node required)
- **Secure Key Storage** — Private keys stored in Android Keystore
- **Dark / Light Theme** — Automatic system theme with Meowcoin brand colors
- **Transaction History** — View incoming and outgoing transactions
- **Network Status** — Real-time Electrum connection indicator

## Screenshots

*Coming soon*

## Build

Requires Android Studio with Kotlin & Jetpack Compose support.

```bash
git clone https://github.com/JustAResearcher/Meowcoin_Android_wallet.git
cd Meowcoin_Android_wallet
# Open in Android Studio and build
```

**Requirements:**
- Android SDK 35 (compile) / min SDK 26
- Kotlin 2.0+
- Jetpack Compose (Material 3)

## Changelog

### v1.0.1 (2025-02-15)
- Updated branding to official 2025 Meowcoin assets from [mewccrypto.com/branding](https://www.mewccrypto.com/branding)
- Replaced launcher icons with MeowcoinR2 logo (all mipmap densities)
- Added in-app logo drawables (`meowcoin_logo`, `meowcoin_logo_full`)
- Updated primary brand color to official `#BE840A`
- Replaced emoji placeholders with real logo images on Welcome and Home screens
- Cleaned up build artifacts from repository

### v1.0.0
- Initial release
- Wallet creation and WIF import
- Send/receive MEWC via Electrum
- QR code address display
- Dark/light theme support
- Transaction history

## Network

- **Chain:** Meowcoin Mainnet
- **Address prefix:** `M` (pubkey 50, script 122)
- **Default Electrum servers:** Configured in app

## License

See [COPYING](COPYING) for details.

## Links

- **Website:** [mewccrypto.com](https://www.mewccrypto.com)
- **Branding:** [mewccrypto.com/branding](https://www.mewccrypto.com/branding)
- **Mining Proxy:** [Meowcoin-Stratum-Proxy](https://github.com/JustAResearcher/Meowcoin-Stratum-Proxy)
- **Node:** [Meowcoin-Lab](https://github.com/JustAResearcher/Meowcoin-Lab)
