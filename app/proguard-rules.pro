# Add project specific ProGuard rules here.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keep class com.meowcoin.wallet.crypto.** { *; }
-keep class com.meowcoin.wallet.data.local.** { *; }
