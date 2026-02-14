package com.meowcoin.wallet.data.remote

import retrofit2.Response
import retrofit2.http.*

/**
 * Meowcoin Explorer/Electrum API service.
 * Compatible with the standard insight-api / blockbook API that Meowcoin explorers use.
 */
interface MeowcoinApiService {

    /**
     * Get address info including balance.
     */
    @GET("api/addr/{address}")
    suspend fun getAddressInfo(
        @Path("address") address: String
    ): Response<AddressInfoResponse>

    /**
     * Get UTXOs for an address.
     */
    @GET("api/addr/{address}/utxo")
    suspend fun getUtxos(
        @Path("address") address: String
    ): Response<List<UtxoResponse>>

    /**
     * Get transaction details.
     */
    @GET("api/tx/{txid}")
    suspend fun getTransaction(
        @Path("txid") txId: String
    ): Response<TransactionResponse>

    /**
     * Get transaction history for an address.
     */
    @GET("api/txs")
    suspend fun getTransactionHistory(
        @Query("address") address: String,
        @Query("pageNum") page: Int = 0
    ): Response<TransactionHistoryResponse>

    /**
     * Broadcast a signed transaction.
     */
    @POST("api/tx/send")
    @FormUrlEncoded
    suspend fun broadcastTransaction(
        @Field("rawtx") rawTx: String
    ): Response<BroadcastResponse>

    /**
     * Get current block count (chain height).
     */
    @GET("api/sync")
    suspend fun getSyncStatus(): Response<SyncStatusResponse>
}

// Response data classes

data class AddressInfoResponse(
    val addrStr: String = "",
    val balance: Double = 0.0,
    val balanceSat: Long = 0,
    val unconfirmedBalance: Double = 0.0,
    val unconfirmedBalanceSat: Long = 0,
    val totalReceived: Double = 0.0,
    val totalReceivedSat: Long = 0,
    val totalSent: Double = 0.0,
    val totalSentSat: Long = 0,
    val txApperances: Int = 0,
    val transactions: List<String> = emptyList()
)

data class UtxoResponse(
    val address: String = "",
    val txid: String = "",
    val vout: Int = 0,
    val scriptPubKey: String = "",
    val amount: Double = 0.0,
    val satoshis: Long = 0,
    val height: Int = 0,
    val confirmations: Int = 0
)

data class TransactionResponse(
    val txid: String = "",
    val version: Int = 0,
    val vin: List<TxInput> = emptyList(),
    val vout: List<TxOutputResponse> = emptyList(),
    val blockhash: String = "",
    val blockheight: Int = 0,
    val confirmations: Int = 0,
    val time: Long = 0,
    val fees: Double = 0.0,
    val valueIn: Double = 0.0,
    val valueOut: Double = 0.0
)

data class TxInput(
    val txid: String = "",
    val vout: Int = 0,
    val addr: String = "",
    val value: Double = 0.0,
    val valueSat: Long = 0
)

data class TxOutputResponse(
    val value: String = "0",
    val n: Int = 0,
    val scriptPubKey: ScriptPubKeyResponse = ScriptPubKeyResponse()
)

data class ScriptPubKeyResponse(
    val hex: String = "",
    val asm: String = "",
    val addresses: List<String> = emptyList(),
    val type: String = ""
)

data class TransactionHistoryResponse(
    val pagesTotal: Int = 0,
    val txs: List<TransactionResponse> = emptyList()
)

data class BroadcastResponse(
    val txid: String = ""
)

data class SyncStatusResponse(
    val status: String = "",
    val blockChainHeight: Int = 0,
    val syncPercentage: Double = 0.0,
    val height: Int = 0
)
