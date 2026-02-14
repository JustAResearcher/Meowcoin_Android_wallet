package com.meowcoin.wallet.`data`.local

import kotlin.reflect.KClass

internal fun KClass<WalletDatabase>.instantiateImpl(): WalletDatabase =
    com.meowcoin.wallet.`data`.local.WalletDatabase_Impl()
