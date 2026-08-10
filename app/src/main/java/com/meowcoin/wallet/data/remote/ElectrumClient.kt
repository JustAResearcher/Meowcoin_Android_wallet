package com.meowcoin.wallet.data.remote

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.meowcoin.wallet.crypto.CoinProfile
import com.meowcoin.wallet.crypto.CoinRegistry
import com.meowcoin.wallet.crypto.ElectrumEndpoint
import com.meowcoin.wallet.crypto.MeowcoinAddress
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Electrum Stratum protocol client for a Bitcoin-family [CoinProfile].
 *
 * Implements a JSON-RPC client over TCP/SSL that speaks the Electrum protocol,
 * allowing the wallet to query a configured light-wallet backend without downloading
 * the full blockchain. The genesis pin prevents accidental cross-chain connections, but this
 * client still trusts the server because it does not validate a complete header chain.
 *
 * Protocol reference: https://electrumx.readthedocs.io/en/latest/protocol.html
 */
class ElectrumClient(
    private val profile: CoinProfile = CoinRegistry.MEWC,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "ElectrumClient"
        private const val PROTOCOL_VERSION = "1.4"
        private const val CLIENT_NAME = "MultiCoinAndroidWallet"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MEWC_GENESIS_HEADER_HEX =
            "0400000000000000000000000000000000000000000000000000000000000000" +
                "0000000090739a9ddd9c782daf939db397a9d21f74a960fea5c398d533842c59f66c91e8" +
                "1b000c63ffff001e565d0500"

        /**
         * Calculate the conventional display-order double-SHA hash for an 80-byte genesis header.
         * Live subscription headers remain opaque because AuxPoW chains can return extended data.
         */
        internal fun genesisHashFromHeader(headerHex: String): String {
            require(headerHex.length == 160) { "Block header must be exactly 80 bytes" }
            require(headerHex.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) {
                "Block header must be hexadecimal"
            }

            val header = ByteArray(80) { index ->
                headerHex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
            val firstHash = MessageDigest.getInstance("SHA-256").digest(header)
            val secondHash = MessageDigest.getInstance("SHA-256").digest(firstHash)
            return secondHash.reversedArray().joinToString("") { "%02x".format(it) }
        }

        /**
         * Match height zero to a profile. MEWC's official block ID is X16R-derived, so its raw
         * serialized genesis header is pinned instead of comparing a double-SHA header hash.
         */
        internal fun genesisHeaderMatchesProfile(
            profile: CoinProfile,
            headerHex: String
        ): Boolean {
            if (profile.id == CoinRegistry.MEWC.id) {
                return headerHex.equals(MEWC_GENESIS_HEADER_HEX, ignoreCase = true)
            }
            return runCatching {
                genesisHashFromHeader(headerHex) == profile.genesisHash
            }.getOrDefault(false)
        }
    }

    /** Compatibility overload for callers that supplied only a coroutine scope. */
    constructor(legacyScope: CoroutineScope) : this(CoinRegistry.MEWC, legacyScope)

    private val gson = Gson()
    private val requestId = AtomicInteger(0)
    private val clientJob = SupervisorJob(scope.coroutineContext[Job])
    private val clientScope = CoroutineScope(scope.coroutineContext + clientJob)
    @Volatile
    private var reconnectEnabled = false

    // Pending requests waiting for a response
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<JsonElement>>()

    // Subscription callbacks (method → callback)
    private val subscriptions = ConcurrentHashMap<String, MutableList<(JsonElement) -> Unit>>()

    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private var readerJob: Job? = null
    private var reconnectJob: Job? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _serverInfo = MutableStateFlow<ServerInfo?>(null)
    val serverInfo: StateFlow<ServerInfo?> = _serverInfo.asStateFlow()

    private var currentServer: ElectrumEndpoint? = null

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
     * Connect to the first trusted TLS Electrum server in the active coin profile.
     * Plaintext is never used as an automatic fallback.
     */
    suspend fun connect(): Boolean {
        if (!clientJob.isActive) return false
        if (_connectionState.value == ConnectionState.CONNECTED) return true

        reconnectEnabled = true
        _connectionState.value = ConnectionState.CONNECTING

        for (server in profile.electrumServers) {
            val sslPort = server.sslPort
            if (sslPort == null) {
                Log.w(TAG, "Skipping ${server.host}: profile has no TLS port")
                continue
            }

            Log.d(TAG, "Trying ${server.host}:$sslPort (TLS)...")
            if (connectToServer(server, useSSL = true)) return true
        }

        Log.e(TAG, "Could not connect to any Electrum server")
        _connectionState.value = ConnectionState.ERROR
        return false
    }

    /**
     * Connect to a specific custom server.
     */
    suspend fun connectToCustomServer(host: String, port: Int, useSSL: Boolean = true): Boolean {
        require(host.isNotBlank()) { "Host must not be blank" }
        require(port in 1..65535) { "Port must be between 1 and 65535" }
        if (!clientJob.isActive) return false
        reconnectEnabled = true
        val server = ElectrumEndpoint(
            host = host,
            tcpPort = if (useSSL) null else port,
            sslPort = if (useSSL) port else null
        )
        return connectToServer(server, useSSL)
    }

    private suspend fun connectToServer(
        server: ElectrumEndpoint,
        useSSL: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val port = (if (useSSL) server.sslPort else server.tcpPort)
                ?: throw IllegalArgumentException(
                    "${if (useSSL) "TLS" else "TCP"} port is not configured for ${server.host}"
                )
            val socket = if (useSSL) {
                createVerifiedTlsSocket(server.host, port).apply {
                    // Keep subscription reads open while the server is idle.
                    // Individual JSON-RPC calls are bounded in request().
                    soTimeout = 0
                }
            } else {
                Socket().apply {
                    connect(InetSocketAddress(server.host, port), CONNECT_TIMEOUT_MS)
                    soTimeout = 0
                }
            }

            writer = PrintWriter(socket.getOutputStream(), true)
            reader = BufferedReader(InputStreamReader(socket.getInputStream()))

            // Start reading responses in background
            readerJob = clientScope.launch { readLoop() }

            // Negotiate protocol version
            val versionResult = request(
                "server.version",
                listOf(CLIENT_NAME, PROTOCOL_VERSION)
            )

            val versionArray = versionResult.asJsonArray
            val serverVersion = versionArray[0].asString
            val protocolVersion = versionArray[1].asString

            // An authenticated hostname can still serve another chain. Pin the profile to the
            // chain's immutable genesis header before trusting balances or transactions.
            val genesisHeader = request("blockchain.block.header", listOf(0)).asString
            if (!genesisHeaderMatchesProfile(profile, genesisHeader)) {
                throw ElectrumException(
                    "Wrong chain or malformed genesis header from ${server.host} for ${profile.ticker}"
                )
            }

            currentServer = server
            _connectionState.value = ConnectionState.CONNECTED
            _serverInfo.value = ServerInfo(
                host = server.host,
                protocolVersion = protocolVersion,
                serverVersion = serverVersion
            )

            Log.i(TAG, "Connected to ${server.host}:$port ($serverVersion, protocol $protocolVersion)")
            true
        } catch (e: CancellationException) {
            disconnectAfterFailure()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}")
            disconnectAfterFailure()
            false
        }
    }

    private fun createVerifiedTlsSocket(host: String, port: Int): SSLSocket {
        val rawSocket = Socket()
        try {
            rawSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            val sslSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(rawSocket, host, port, true) as SSLSocket
            sslSocket.sslParameters = sslSocket.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
            }
            sslSocket.startHandshake()
            return sslSocket
        } catch (e: Exception) {
            rawSocket.close()
            throw e
        }
    }

    /**
     * Disconnect from the current server.
     */
    fun disconnect() {
        reconnectEnabled = false
        reconnectJob?.cancel()
        reconnectJob = null
        val resources = detachConnection()
        clientScope.launch(Dispatchers.IO) { closeResources(resources) }
    }

    /** Permanently close this client and wait for its reader/reconnect jobs to stop. */
    suspend fun close() {
        reconnectEnabled = false
        reconnectJob?.cancel()
        reconnectJob = null
        val resources = detachConnection()
        withContext(NonCancellable + Dispatchers.IO) { closeResources(resources) }
        withContext(NonCancellable) { clientJob.cancelAndJoin() }
    }

    /** Non-blocking permanent shutdown for lifecycle callbacks that cannot suspend. */
    fun shutdown() {
        reconnectEnabled = false
        reconnectJob?.cancel()
        reconnectJob = null
        val resources = detachConnection()
        clientScope.launch(Dispatchers.IO) {
            closeResources(resources)
            clientJob.cancel()
        }
    }

    private suspend fun disconnectAfterFailure() {
        val resources = detachConnection()
        withContext(NonCancellable + Dispatchers.IO) { closeResources(resources) }
    }

    private data class ConnectionResources(
        val writer: PrintWriter?,
        val reader: BufferedReader?
    )

    private fun detachConnection(): ConnectionResources {
        readerJob?.cancel()
        readerJob = null
        val resources = ConnectionResources(writer, reader)
        writer = null
        reader = null
        pendingRequests.values.forEach {
            it.completeExceptionally(Exception("Disconnected"))
        }
        pendingRequests.clear()
        _connectionState.value = ConnectionState.DISCONNECTED
        currentServer = null
        return resources
    }

    private fun closeResources(resources: ConnectionResources) {
        try {
            resources.writer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Writer close failed during disconnect", e)
        }
        try {
            resources.reader?.close()
        } catch (e: Exception) {
            // Socket teardown is best-effort and must never abort coin activation.
            Log.w(TAG, "Reader close failed during disconnect", e)
        }
    }

    /**
     * Attempt to reconnect after a connection drop.
     */
    suspend fun reconnect(): Boolean {
        if (!clientJob.isActive || !reconnectEnabled) return false
        _connectionState.value = ConnectionState.RECONNECTING
        disconnectAfterFailure()
        delay(2000) // Brief delay before retry
        if (!clientJob.isActive || !reconnectEnabled) return false
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

        withContext(Dispatchers.IO) {
            writer?.println(json)
                ?: throw Exception("Not connected")
        }

        return try {
            withTimeout(READ_TIMEOUT_MS.toLong()) {
                deferred.await()
            }
        } finally {
            pendingRequests.remove(id, deferred)
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
            if (!currentCoroutineContext().isActive || !reconnectEnabled || !clientJob.isActive) {
                return
            }
            Log.e(TAG, "Read loop error: ${e.message}")
            _connectionState.value = ConnectionState.ERROR
            reconnectJob?.cancel()
            reconnectJob = clientScope.launch {
                delay(5000)
                if (reconnectEnabled && clientJob.isActive) reconnect()
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
     * The proof is returned as server data; this client does not validate it against a verified
     * header chain.
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
     * Convert an address for the active coin profile to an Electrum scripthash.
     * Electrum uses reversed SHA-256 of the scriptPubKey, so the script must match the
     * address type — P2PKH, P2SH, or post-APEX SegWit/Taproot bech32.
     */
    fun addressToScriptHash(address: String): String {
        val scriptPubKey = MeowcoinAddress.toScriptPubKey(address, profile)
        val sha256 = MessageDigest.getInstance("SHA-256").digest(scriptPubKey)
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
