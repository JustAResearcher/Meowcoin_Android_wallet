package com.meowcoin.wallet.data.remote

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.meowcoin.wallet.crypto.CoinRegistry
import java.io.PrintWriter
import java.net.ServerSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElectrumClientLifecycleTest {

    @Test
    fun closeFromMainStopsConnectedClientWithoutReconnect() = runBlocking {
        val server = ServerSocket(0)
        val serverJob = launch(Dispatchers.IO) {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                val writer = PrintWriter(socket.getOutputStream(), true)
                repeat(2) {
                    val request = JsonParser.parseString(reader.readLine()).asJsonObject
                    val result = when (request.get("method").asString) {
                        "server.version" -> Gson().toJsonTree(listOf("test-electrum", "1.4"))
                        "blockchain.block.header" -> Gson().toJsonTree(MEWC_GENESIS_HEADER)
                        else -> error("Unexpected method")
                    }
                    writer.println(
                        Gson().toJson(
                            JsonObject().apply {
                                addProperty("jsonrpc", "2.0")
                                add("id", request.get("id"))
                                add("result", result)
                            }
                        )
                    )
                }
                while (reader.readLine() != null) Unit
            }
        }
        val client = ElectrumClient(CoinRegistry.MEWC)

        try {
            assertTrue(client.connectToCustomServer("127.0.0.1", server.localPort, useSSL = false))
            withContext(Dispatchers.Main) { client.close() }

            // An intentional close used to be handled as a read failure and schedule a
            // reconnect five seconds later.
            delay(5_500)
            assertEquals(
                ElectrumClient.ConnectionState.DISCONNECTED,
                client.connectionState.value
            )
        } finally {
            client.close()
            server.close()
            serverJob.cancelAndJoin()
        }
    }

    companion object {
        private const val MEWC_GENESIS_HEADER =
            "0400000000000000000000000000000000000000000000000000000000000000" +
                "0000000090739a9ddd9c782daf939db397a9d21f74a960fea5c398d533842c59f66c91e8" +
                "1b000c63ffff001e565d0500"
    }
}
