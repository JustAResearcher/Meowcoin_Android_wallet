package com.meowcoin.wallet

import android.app.Application
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class MeowcoinApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Register BouncyCastle security provider for secp256k1
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }
}
