package com.meowcoin.wallet.data.remote

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.meowcoin.wallet.crypto.MeowcoinNetwork
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocketFactory

/**
 * Electrum Stratum protocol client for Meowcoin.
 *
 * Implements a JSON-RPC client over TCP/SSL that speaks the Electrum protocol,
 * allowing the wallet to operate as a light client (SPV) without downloading
 * the full blockchain.
 *
 * Protocol reference: https://electrumx.readthedocs.io/en/latest/protocol.html
 */
class ElectrumClient(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "ElectrumClient"
        private const val PROTOCOL_VERSION = "1.4"
        private const val CLIENT_NAME = "MeowcoinWallet"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
    }

    private val gson = Gson()
    private val requestId = AtomicInteger(0)

    // Pending requests waiting for a response
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<JsonElement>>()

    // Subscription callbacks (method → callback)
    private val subscriptions = ConcurrentHashMap<String, MutableList<(JsonElement) -> Unit>>()

    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private var readerJob: Job? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _serverInfo = MutableStateFlow<ServerInfo?>(null)
    val serverInfo: StateFlow<ServerInfo?> = _serverInfo.asStateFlow()

    private var currentServer: MeowcoinNetwork.ElectrumServer? = null

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING,
        ERROR
    }

    data class ServerInfo(
        val host: String,
        val protocolVersion: String,
        val serverVersion: String,
        val blockHeight: Int = 0
    )

    // ═══════════════════════════════════════════
    //  Connection Management
    // ═══════════════════════════════════════════

    /**
     * Connect to the best available Electrum server.
     * Tries all known servers and connects to the first one that responds.
     */
    suspend fun connect(): Boolean {
        if (_connectionState.value == ConnectionState.CONNECTED) return true

        _connectionState.value = ConnectionState.CONNECTING

        for (server in MeowcoinNetwork.ELECTRUM_SERVERS) {
            try {
                Log.d(TAG, "Trying ${server.host}:${server.sslPort} (SSL)...")
                if (connectToServer(server, useSSL = true)) return true
            } catch (e: Exception) {
                Log.w(TAG, "SSL failed for ${server.host}: ${e.message}")
            }

            try {
                Log.d(TAG, "Trying ${server.host}:${server.tcpPort} (TCP)...")
                if (connectToServer(server, useSSL = false)) return true
            } catch (e: Exception) {
                Log.w(TAG, "TCP failed for ${server.host}: ${e.message}")
            }
        }

        Log.e(TAG, "Could not connect to any Electrum server")
        _connectionState.value = ConnectionState.ERROR
        return false
    }

    /**
     * Connect to a specific custom server.
     */
    suspend fun connectToCustomServer(host: String, port: Int, useSSL: Boolean = true): Boolean {
        val server = MeowcoinNetwork.ElectrumServer(
            host = host,
            tcpPort = if (useSSL) port else port,
            sslPort = if (useSSL) port else port
        )
        return connectToServer(server, useSSL)
    }

    private suspend fun connectToServer(
        server: MeowcoinNetwork.ElectrumServer,
        useSSL: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val port = if (useSSL) server.sslPort else server.tcpPort
            val socket = if (useSSL) {
                SSLSocketFactory.getDefault().createSocket().apply {
                    connect(InetSocketAddress(server.host, port), CONNECT_TIMEOUT_MS)
                    soTimeout = READ_TIMEOUT_MS
                }
            } else {
                java.net.Socket().apply {
                    connect(InetSocketAddress(server.host, port), CONNECT_TIMEOUT_MS)
                    soTimeout = READ_TIMEOUT_MS
                }
            }

            writer = PrintWriter(socket.getOutputStream(), true)
            reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            // Start reading responses in background
            readerJob = scope.launch { readLoop() }

            // Negotiate protocol version
            val versionResult = request(
                "server.version",
                listOf(CLIENT_NAME, PROTOCOL_VERSION)
            )

            val versionArray = versionResult.asJsonArray
            val serverVersion = versionArray[0].asString
            val protocolVersion = versionArray[1].asString

            currentServer = server
            _connectionState.value = ConnectionState.CONNECTED
            _serverInfo.value = ServerInfo(
                host = server.host,
                protocolVersion = protocolVersion,
                serverVersion = serverVersion
            )

            Log.i(TAG, "Connected to ${server.host}:$port ($serverVersion, protocol $protocolVersion)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}")
            disconnect()
            false
        }
    }

    /**
     * Disconnect from the current server.
     */
    fun disconnect() {
        readerJob?.cancel()
        readerJob = null
        writer?.close()
        writer = null
        reader?.close()
        reader = null
        pendingRequests.values.forEach {
            it.completeExceptionally(Exception("Disconnected"))
        }
        pendingRequests.clear()
        _connectionState.value = ConnectionState.DISCONNECTED
        currentServer = null
    }

    /**
     * Attempt to reconnect after a connection drop.
     */
    suspend fun reconnect(): Boolean {
        _connectionState.value = ConnectionState.RECONNECTING
        disconnect()
        delay(2000) // Brief delay before retry
        return connect()
    }

    // ═══════════════════════════════════════════
    //  JSON-RPC Communication
    // ═══════════════════════════════════════════

    /**
     * Send a JSON-RPC request and wait for the response.
     */
    suspend fun request(method: String, params: List<Any> = emptyList()): JsonElement {
        val id = requestId.incrementAndGet()
        val deferred = CompletableDeferred<JsonElement>()
        pendingRequests[id] = deferred

        val rpcRequest = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", method)
            add("params", gson.toJsonTree(params))
        }

        val json = gson.toJson(rpcRequest)
        Log.d(TAG, "→ $json")

        writer?.println(json)
            ?: throw Exception("Not connected")

        return withTimeout(READ_TIMEOUT_MS.toLong()) {
            deferred.await()
        }
    }

    /**
     * Read loop for incoming server messages (responses and notifications).
     */
    private suspend fun readLoop() {
        try {
            while (currentCoroutineContext().isActive) {
                val line = withContext(Dispatchers.IO) {
                    reader?.readLine()
                } ?: break

                Log.d(TAG, "← $line")

                try {
                    val json = JsonParser.parseString(line).asJsonObject

                    if (json.has("id") && !json.get("id").isJsonNull) {
                        // Response to a request
                        val id = json.get("id").asInt
                        val deferred = pendingRequests.remove(id)

                        if (json.has("error") && !json.get("error").isJsonNull) {
                            val error = json.getAsJsonObject("error")
                            val message = error.get("message")?.asString ?: "Unknown error"
                            deferred?.completeExceptionally(ElectrumException(message))
                        } else {
                            deferred?.complete(json.get("result"))
                        }
                    } else if (json.has("method")) {
                        // Subscription notification
                        val method = json.get("method").asString
                        val params = json.getAsJsonArray("params")
                        subscriptions[method]?.forEach { callback ->
                            callback(params)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing message: ${e.message}")
                }
            }
        } catch (e: CancellationException) {
            // Normal cancellation
        } catch (e: Exception) {
            Log.e(TAG, "Read loop error: ${e.message}")
            _connectionState.value = ConnectionState.ERROR
            // Auto-reconnect
            scope.launch {
                delay(5000)
                reconnect()
            }
        }
    }

    // ═══════════════════════════════════════════
    //  Blockchain Methods
    // ═══════════════════════════════════════════

    /**
     * Get the balance for a scripthash.
     * Returns (confirmed, unconfirmed) in satoshis.
     */
    suspend fun getBalance(scriptHash: String): Pair<Long, Long> {
        val result = request("blockchain.scripthash.get_balance", listOf(scriptHash))
        val obj = result.asJsonObject
        return Pair(
            obj.get("confirmed").asLong,
            obj.get("unconfirmed").asLong
        )
    }

    /**
     * Get transaction history for a scripthash.
     */
    suspend fun getHistory(scriptHash: String): List<HistoryItem> {
        val result = request("blockchain.scripthash.get_history", listOf(scriptHash))
        return result.asJsonArray.map { item ->
            val obj = item.asJsonObject
            HistoryItem(
                txHash = obj.get("tx_hash").asString,
                height = obj.get("height").asInt,
                fee = obj.get("fee")?.asLong
            )
        }
    }

    /**
     * Get unspent transaction outputs for a scripthash.
     */
    suspend fun listUnspent(scriptHash: String): List<UnspentOutput> {
        val result = request("blockchain.scripthash.listunspent", listOf(scriptHash))
        return result.asJsonArray.map { item ->
            val obj = item.asJsonObject
            UnspentOutput(
                txHash = obj.get("tx_hash").asString,
                txPos = obj.get("tx_pos").asInt,
                value = obj.get("value").asLong,
                height = obj.get("height").asInt
            )
        }
    }

    /**
     * Subscribe to notifications about an address (via scripthash).
     * Returns the current status hash.
     */
    suspend fun subscribeToAddress(
        scriptHash: String,
        onUpdate: (JsonElement) -> Unit
    ): String? {
        subscriptions.getOrPut("blockchain.scripthash.subscribe") { mutableListOf() }
            .add(onUpdate)

        val result = request("blockchain.scripthash.subscribe", listOf(scriptHash))
        return if (result.isJsonNull) null else result.asString
    }

    /**
     * Subscribe to new block headers.
     */
    suspend fun subscribeToHeaders(onNewBlock: (BlockHeader) -> Unit): BlockHeader {
        subscriptions.getOrPut("blockchain.headers.subscribe") { mutableListOf() }
            .add { params ->
                val headerArray = if (params.isJsonArray) params.asJsonArray else JsonArray().apply { add(params) }
                if (headerArray.size() > 0) {
                    val obj = headerArray[0].asJsonObject
                    onNewBlock(
                        BlockHeader(
                            height = obj.get("height").asInt,
                            hex = obj.get("hex")?.asString ?: ""
                        )
                    )
                }
            }

        val result = request("blockchain.headers.subscribe")
        val obj = result.asJsonObject
        val header = BlockHeader(
            height = obj.get("height").asInt,
            hex = obj.get("hex")?.asString ?: ""
        )

        _serverInfo.update { it?.copy(blockHeight = header.height) }
        return header
    }

    /**
     * Get a raw transaction by its ID.
     */
    suspend fun getTransaction(txId: String, verbose: Boolean = true): JsonElement {
        return request("blockchain.transaction.get", listOf(txId, verbose))
    }

    /**
     * Broadcast a raw transaction hex.
     * Returns the transaction ID.
     */
    suspend fun broadcastTransaction(rawTxHex: String): String {
        val result = request("blockchain.transaction.broadcast", listOf(rawTxHex))
        return result.asString
    }

    /**
     * Estimate the fee for a transaction (in sat/kB).
     * @param blocks Number of blocks for confirmation target
     */
    suspend fun estimateFee(blocks: Int = 2): Double {
        val result = request("blockchain.estimatefee", listOf(blocks))
        return result.asDouble
    }

    /**
     * Get the current block header at a specific height.
     */
    suspend fun getBlockHeader(height: Int): String {
        val result = request("blockchain.block.header", listOf(height))
        return result.asString
    }

    /**
     * Get the Merkle proof for a transaction in a block.
     * Used for SPV verification.
     */
    suspend fun getMerkleProof(txId: String, height: Int): MerkleProof {
        val result = request("blockchain.transaction.get_merkle", listOf(txId, height))
        val obj = result.asJsonObject
        return MerkleProof(
            merkle = obj.getAsJsonArray("merkle").map { it.asString },
            blockHeight = obj.get("block_height").asInt,
            pos = obj.get("pos").asInt
        )
    }

    /**
     * Ping the server to keep the connection alive.
     */
    suspend fun ping(): Boolean {
        return try {
            request("server.ping")
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get the donation address for the server.
     */
    suspend fun getServerDonationAddress(): String? {
        return try {
            val result = request("server.donation_address")
            if (result.isJsonNull) null else result.asString
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get list of peers known to this server.
     */
    suspend fun getPeers(): List<JsonElement> {
        return try {
            val result = request("server.peers.subscribe")
            result.asJsonArray.toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ═══════════════════════════════════════════
    //  Utility: Address → ScriptHash
    // ═══════════════════════════════════════════

    /**
     * Convert a Meowcoin address to an Electrum scripthash.
     * Electrum uses reversed SHA-256 of the scriptPubKey.
     */
    fun addressToScriptHash(address: String): String {
        val hash160 = com.meowcoin.wallet.crypto.MeowcoinAddress.toHash160(address)

        // Build P2PKH scriptPubKey: OP_DUP OP_HASH160 <20 bytes> OP_EQUALVERIFY OP_CHECKSIG
        val scriptPubKey = byteArrayOf(
            0x76.toByte(),       // OP_DUP
            0xA9.toByte(),       // OP_HASH160
            0x14.toByte()        // Push 20 bytes
        ) + hash160 + byteArrayOf(
            0x88.toByte(),       // OP_EQUALVERIFY
            0xAC.toByte()        // OP_CHECKSIG
        )

        // SHA-256 hash of the scriptPubKey, then reverse bytes
        val sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(scriptPubKey)
        return sha256.reversedArray().joinToString("") { "%02x".format(it) }
    }

    // ═══════════════════════════════════════════
    //  Data Classes
    // ═══════════════════════════════════════════

    data class HistoryItem(
        val txHash: String,
        val height: Int,        // 0 = unconfirmed, -1 = unconfirmed with unconfirmed parent
        val fee: Long? = null
    )

    data class UnspentOutput(
        val txHash: String,
        val txPos: Int,
        val value: Long,       // In satoshis
        val height: Int
    )

    data class BlockHeader(
        val height: Int,
        val hex: String
    )

    data class MerkleProof(
        val merkle: List<String>,
        val blockHeight: Int,
        val pos: Int
    )

    class ElectrumException(message: String) : Exception(message)
}
