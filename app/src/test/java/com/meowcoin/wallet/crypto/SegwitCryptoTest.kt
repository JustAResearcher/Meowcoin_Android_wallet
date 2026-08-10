package com.meowcoin.wallet.crypto

import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SegwitCryptoTest {
    private val mnemonic =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    @Test
    fun bip84MatchesThePublishedBitcoinFirstAddressVector() {
        val master = Bip32.masterKeyFromSeed(Bip39.mnemonicToSeed(mnemonic))
        val key = Bip32.deriveNativeSegwitKey(master, CoinRegistry.BTC).toKeyPair(CoinRegistry.BTC)

        assertEquals("m/84'/0'/0'/0/0", Bip32.bip84Path(CoinRegistry.BTC))
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", key.toP2WPKHAddress())
    }

    @Test
    fun mewcAndLitecoinBip84ReceiveAndChangeBranchesRestoreFromTheSeed() {
        val firstMewc = HdWallet.fromMnemonic(mnemonic, CoinRegistry.MEWC)
        val restoredMewc = HdWallet.fromMnemonic(mnemonic, CoinRegistry.MEWC)
        val litecoin = HdWallet.fromMnemonic(mnemonic, CoinRegistry.LTC)

        val mewcReceive = firstMewc.deriveReceivingAddress(7, Bip32.BIP84_PURPOSE)
        val mewcChange = firstMewc.deriveChangeAddress(3, Bip32.BIP84_PURPOSE)
        val ltcReceive = litecoin.deriveNativeSegwitReceivingAddress(7)

        assertTrue(mewcReceive.startsWith("mewc1q"))
        assertTrue(mewcChange.startsWith("mewc1q"))
        assertTrue(ltcReceive.startsWith("ltc1q"))
        assertEquals(mewcReceive, restoredMewc.deriveNativeSegwitReceivingAddress(7))
        assertEquals(
            firstMewc.deriveNativeSegwitReceivingKey(7).privateKeyHex(),
            restoredMewc.findKeyForAddress(mewcReceive, maxScan = 8)?.privateKeyHex()
        )
        assertNotEquals(firstMewc.deriveReceivingAddress(7), mewcReceive)
        assertEquals("m/84'/1669'/0'/1/3", Bip32.bip84Path(CoinRegistry.MEWC, change = 1, addressIndex = 3))
        assertEquals("m/84'/2'/0'/0/7", Bip32.bip84Path(CoinRegistry.LTC, addressIndex = 7))
    }

    @Test
    fun mewcAndLitecoinBip84BranchesMatchIndependentFixedVectors() {
        val mewc = HdWallet.fromMnemonic(mnemonic, CoinRegistry.MEWC)
        val litecoin = HdWallet.fromMnemonic(mnemonic, CoinRegistry.LTC)

        assertBip84Vector(
            key = mewc.deriveNativeSegwitReceivingKey(0),
            address = mewc.deriveNativeSegwitReceivingAddress(0),
            expectedPrivateKey = "0d2d6394683e3a540781758c4a942f280b00c4cc95e5bfbd7b5ba633dd3bf8a6",
            expectedPublicKey = "03e0dbe3f9b11fc847f418b322e1eada09aded7261171163431c2aa703dc8f87b0",
            expectedAddress = "mewc1q8v5rfqry3amwrhxxv9vkntuwwlpvp0zm9jggkz",
            expectedScript = "00143b283480648f76e1dcc6615969af8e77c2c0bc5b"
        )
        assertBip84Vector(
            key = mewc.deriveNativeSegwitChangeKey(0),
            address = mewc.deriveNativeSegwitChangeAddress(0),
            expectedPrivateKey = "10e601319bb164d0f713406072d6fa5550b80204cf0d775e0e356ecf6cb74017",
            expectedPublicKey = "03b923ca0f71b529003353c07074d054c373ab5706d64418459cad120616748795",
            expectedAddress = "mewc1qxa43ugn0r7ltqwc3e5r5ldwgxuy7yvufla8q3g",
            expectedScript = "0014376b1e226f1fbeb03b11cd074fb5c83709e23389"
        )
        assertBip84Vector(
            key = litecoin.deriveNativeSegwitReceivingKey(0),
            address = litecoin.deriveNativeSegwitReceivingAddress(0),
            expectedPrivateKey = "4ab54480cbbaa53d5d3ba43a3e85d221df085490e88346ad11579e17000db7bb",
            expectedPublicKey = "02e49c9b9b5d0f127235dc26a0c252814c52fb333d651a946773f59d72c2da9904",
            expectedAddress = "ltc1qjmxnz78nmc8nq77wuxh25n2es7rzm5c2rkk4wh",
            expectedScript = "001496cd3178f3de0f307bcee1aeaa4d5987862dd30a"
        )
        assertBip84Vector(
            key = litecoin.deriveNativeSegwitChangeKey(0),
            address = litecoin.deriveNativeSegwitChangeAddress(0),
            expectedPrivateKey = "dbf6dd9d691b00d0a7f6f93605a67ddb42ba4baafcf9b0d98979b66f8a21184e",
            expectedPublicKey = "029857513f0fe1dc125f219ffef22098e14c653c4bcb0a2aaeff47b3a252569f1a",
            expectedAddress = "ltc1qyeljcy9v88jg8sqvnqh0m5q390xruc5r98q9yy",
            expectedScript = "0014267f2c10ac39e483c00c982efdd0112bcc3e6283"
        )
    }

    @Test
    fun walletDoesNotEnableBip84ReceivingForOtherPhaseOneCoins() {
        val dogecoin = HdWallet.fromMnemonic(mnemonic, CoinRegistry.DOGE)

        assertThrows(IllegalArgumentException::class.java) {
            dogecoin.deriveReceivingAddress(0, Bip32.BIP84_PURPOSE)
        }
    }

    @Test
    fun nativeP2wpkhInputUsesBip143AndReportsWitnessMetrics() {
        val owner = fixedKey(1, CoinRegistry.LTC)
        val destination = fixedKey(2, CoinRegistry.LTC).toP2WPKHAddress()
        val input = ownedP2wpkh(1, 1_000_000L, owner)

        val transaction = MeowcoinTransaction.buildSendAllTransaction(
            spendableUtxos = listOf(input),
            destinationAddress = destination,
            profile = CoinRegistry.LTC,
            feeRate = 1L
        )

        val parsed = parseWitnessTransaction(transaction.txHex.hexToBytes())
        val signatureWithHashType = parsed.witness.single()[0]
        val signature = signatureWithHashType.copyOf(signatureWithHashType.size - 1)
        val signatureHash = bip143Hash(
            version = parsed.version,
            inputs = listOf(input.utxo),
            outputEntries = parsed.outputEntries,
            signingIndex = 0
        )

        assertEquals(1, signatureWithHashType.last().toInt())
        assertArrayEquals(owner.compressedPublicKey(), parsed.witness.single()[1])
        assertTrue(owner.verify(signatureHash, signature))
        assertEquals(transaction.txId, txId(parsed.stripped))
        assertEquals(transaction.wtxId, txId(transaction.txHex.hexToBytes()))
        assertNotEquals(transaction.txId, transaction.wtxId)
        assertTrue(transaction.hasWitness)
        assertEquals(parsed.stripped.size, transaction.baseSize)
        assertEquals(transaction.baseSize * 4 + transaction.size - transaction.baseSize, transaction.weight)
        assertEquals((transaction.weight + 3) / 4, transaction.virtualSize)
        assertEquals(110L, transaction.actualFee)
        assertTrue(transaction.actualFee >= transaction.virtualSize)
    }

    @Test
    fun bip143DigestMatchesThePublishedNativeP2wpkhVector() {
        val firstOutpoint =
            "fff7f7881a8099afa6940d42d1e7f6362bec38171ea3edf433541db4e4ad969f"
        val secondOutpoint =
            "ef51e1b804cc89d182d279655c3aa89e815b1b309fe287d9b2b55d57b90ec68a"
        val witnessScript = "00141d0f172a0ecb48aee1be1f2687d2963ae33f71a1".hexToBytes()
        val inputs = listOf(
            MeowcoinTransaction.Bip143Input(
                txHash = firstOutpoint.hexToBytes().reversedArray().toHex(),
                outputIndex = 0,
                value = 625_000_000L,
                scriptPubKey = byteArrayOf(),
                sequence = 0xFFFFFFEE.toInt()
            ),
            MeowcoinTransaction.Bip143Input(
                txHash = secondOutpoint.hexToBytes().reversedArray().toHex(),
                outputIndex = 1,
                value = 600_000_000L,
                scriptPubKey = witnessScript,
                sequence = -1
            )
        )
        val outputs = listOf(
            MeowcoinTransaction.Bip143Output(
                112_340_000L,
                "76a9148280b37df378db99f66f85c95a783a76ac7a6d5988ac".hexToBytes()
            ),
            MeowcoinTransaction.Bip143Output(
                223_450_000L,
                "76a9143bde42dbee7e4dbe6a21b2d50ce2f0167faa815988ac".hexToBytes()
            )
        )

        val digest = MeowcoinTransaction.bip143SignatureHash(
            transactionVersion = 1,
            inputs = inputs,
            outputs = outputs,
            inputIndex = 1,
            lockTime = 17
        )

        assertEquals(
            "c37af31116d1b27caf68aae9e3ac82f1477929014d5b917657d0eb49478cb670",
            digest.toHex()
        )
        val owner = MeowcoinKeyPair.fromPrivateKey(
            "619c335025c7f4012e556c2a58b2506e30b8511b53ade95ea316fd8c3286feb9",
            CoinRegistry.BTC
        )
        assertEquals(
            "025476c2e83188368da1ff3e292e7acafcdb3566bb0ad253f62fc70f07aeee6357",
            owner.compressedPublicKey().toHex()
        )
        val signatureWithHashType =
            "304402203609e17b84f6a7d30c80bfa610b5b4542f32a8a0d5447a12fb1366d7f01cc44a" +
                "0220573a954c4518331561406f90300e8f3358f51928d43c212a8caed02de67eebee01"
        val signatureBytes = signatureWithHashType.hexToBytes()
        assertEquals(1, signatureBytes.last().toInt())
        assertTrue(owner.verify(digest, signatureBytes.copyOf(signatureBytes.size - 1)))
    }

    @Test
    fun mixedLegacyAndP2wpkhInputsSerializeAnEmptyLegacyWitness() {
        val legacyOwner = fixedKey(10, CoinRegistry.MEWC)
        val segwitOwner = fixedKey(11, CoinRegistry.MEWC)
        val legacy = ownedP2pkh(10, 1_000_000L, legacyOwner)
        val segwit = ownedP2wpkh(11, 1_000_000L, segwitOwner)

        val transaction = MeowcoinTransaction.buildSendAllTransaction(
            spendableUtxos = listOf(legacy, segwit),
            destinationAddress = fixedKey(12, CoinRegistry.MEWC).toP2WPKHAddress(),
            profile = CoinRegistry.MEWC,
            feeRate = 1L
        )

        val parsed = parseWitnessTransaction(transaction.txHex.hexToBytes())
        assertTrue(parsed.scriptSignatures[0].isNotEmpty())
        assertTrue(parsed.scriptSignatures[1].isEmpty())
        assertTrue(parsed.witness[0].isEmpty())
        assertEquals(2, parsed.witness[1].size)
        assertEquals(259L, transaction.actualFee)
        assertEquals(transaction.txId, txId(parsed.stripped))
    }

    @Test
    fun singleKeyConsolidationCompatibilityApiSignsNativeP2wpkhInputs() {
        val owner = fixedKey(14, CoinRegistry.LTC)
        val firstSpendable = ownedP2wpkh(14, 700_000L, owner)
        val secondSpendable = ownedP2wpkh(15, 800_000L, owner)
        val first = firstSpendable.utxo
        val second = secondSpendable.utxo
        val destination = owner.toP2WPKHAddress()

        val estimatedFee = MeowcoinTransaction.estimateFee(
            spendableUtxos = listOf(firstSpendable, secondSpendable),
            destinationAddress = destination,
            profile = CoinRegistry.LTC,
            feeRate = 1L
        )

        val transaction = MeowcoinTransaction.buildConsolidationTransaction(
            keyPair = owner,
            utxos = listOf(first, second),
            destinationAddress = destination,
            profile = CoinRegistry.LTC,
            feeRate = 1L
        )

        val parsed = parseWitnessTransaction(transaction.txHex.hexToBytes())
        assertEquals(2, parsed.witness.size)
        assertTrue(parsed.scriptSignatures.all(ByteArray::isEmpty))
        assertTrue(parsed.witness.all { it.size == 2 })
        assertEquals(listOf(first.outPoint, second.outPoint), transaction.selectedOutpoints)
        assertEquals(178L, estimatedFee)
        assertEquals(178L, transaction.actualFee)
        assertEquals(transaction.actualFee, estimatedFee)
        assertTrue(transaction.actualFee >= transaction.virtualSize)
    }

    @Test
    fun concreteFeeEstimatorAccountsForMixedInputWeight() {
        val legacyOwner = fixedKey(17, CoinRegistry.MEWC)
        val segwitOwner = fixedKey(18, CoinRegistry.MEWC)
        val destination = fixedKey(19, CoinRegistry.MEWC).toP2WPKHAddress()

        val estimatedFee = MeowcoinTransaction.estimateFee(
            spendableUtxos = listOf(
                ownedP2pkh(17, 1_000_000L, legacyOwner),
                ownedP2wpkh(18, 1_000_000L, segwitOwner)
            ),
            destinationAddress = destination,
            feeRate = 1L
        )

        assertEquals(259L, estimatedFee)
    }

    @Test
    fun legacyTransactionKeepsTxidAndWtxidIdentical() {
        val owner = fixedKey(15, CoinRegistry.MEWC)
        val input = ownedP2pkh(15, 1_000_000L, owner)

        val transaction = MeowcoinTransaction.buildSendAllTransaction(
            spendableUtxos = listOf(input),
            destinationAddress = owner.toAddress(),
            feeRate = 1L
        )

        assertEquals(transaction.txId, transaction.wtxId)
        assertEquals(transaction.size, transaction.baseSize)
        assertEquals(transaction.size * 4, transaction.weight)
        assertEquals(transaction.size, transaction.virtualSize)
        assertTrue(!transaction.hasWitness)
    }

    @Test
    fun nativeSegwitRejectsUncompressedKeys() {
        val uncompressed = MeowcoinKeyPair.fromPrivateKey(
            "16".padStart(64, '0'),
            CoinRegistry.MEWC,
            compressed = false
        )

        assertThrows(IllegalArgumentException::class.java) {
            uncompressed.toP2WPKHAddress()
        }
    }

    @Test
    fun declaredScriptTypeMustMatchTheUtxoScript() {
        val owner = fixedKey(20, CoinRegistry.MEWC)
        val input = ownedP2wpkh(20, 1_000_000L, owner)

        assertThrows(IllegalArgumentException::class.java) {
            MeowcoinTransaction.buildSendAllTransaction(
                spendableUtxos = listOf(input.copy(scriptType = MeowcoinTransaction.ScriptType.P2PKH)),
                destinationAddress = owner.toP2WPKHAddress(),
                feeRate = 1L
            )
        }
    }

    private fun fixedKey(value: Int, profile: CoinProfile): MeowcoinKeyPair =
        MeowcoinKeyPair.fromPrivateKey(value.toString(16).padStart(64, '0'), profile)

    private fun assertBip84Vector(
        key: MeowcoinKeyPair,
        address: String,
        expectedPrivateKey: String,
        expectedPublicKey: String,
        expectedAddress: String,
        expectedScript: String
    ) {
        assertEquals(expectedPrivateKey, key.privateKeyHex())
        assertEquals(expectedPublicKey, key.compressedPublicKey().toHex())
        assertEquals(expectedAddress, address)
        assertEquals(expectedScript, MeowcoinAddress.toScriptPubKey(address, key.profile).toHex())
    }

    private fun ownedP2pkh(
        index: Int,
        value: Long,
        owner: MeowcoinKeyPair
    ): MeowcoinTransaction.SpendableUTXO = owned(
        index,
        value,
        owner,
        owner.toAddress()
    )

    private fun ownedP2wpkh(
        index: Int,
        value: Long,
        owner: MeowcoinKeyPair
    ): MeowcoinTransaction.SpendableUTXO = owned(
        index,
        value,
        owner,
        owner.toP2WPKHAddress()
    )

    private fun owned(
        index: Int,
        value: Long,
        owner: MeowcoinKeyPair,
        address: String
    ): MeowcoinTransaction.SpendableUTXO = MeowcoinTransaction.SpendableUTXO(
        utxo = MeowcoinTransaction.UTXO(
            txHash = index.toString(16).padStart(64, '0'),
            outputIndex = 0,
            value = value,
            scriptPubKey = MeowcoinAddress.toScriptPubKey(address, owner.profile).toHex()
        ),
        ownerKey = owner
    )

    private data class ParsedWitnessTransaction(
        val version: ByteArray,
        val scriptSignatures: List<ByteArray>,
        val outputEntries: ByteArray,
        val witness: List<List<ByteArray>>,
        val stripped: ByteArray
    )

    private fun parseWitnessTransaction(serialized: ByteArray): ParsedWitnessTransaction {
        val reader = ByteArrayReader(serialized)
        val version = reader.readBytes(4)
        assertArrayEquals(byteArrayOf(0x00, 0x01), reader.readBytes(2))
        val inputCount = reader.readVarInt().toInt()
        val outpoints = mutableListOf<ByteArray>()
        val scripts = mutableListOf<ByteArray>()
        val sequences = mutableListOf<ByteArray>()
        repeat(inputCount) {
            outpoints += reader.readBytes(36)
            scripts += reader.readBytes(reader.readVarInt().toInt())
            sequences += reader.readBytes(4)
        }
        val outputCount = reader.readVarInt()
        val outputsStart = reader.position
        repeat(outputCount.toInt()) {
            reader.readBytes(8)
            reader.readBytes(reader.readVarInt().toInt())
        }
        val outputEntries = serialized.copyOfRange(outputsStart, reader.position)
        val witness = (0 until inputCount).map {
            (0 until reader.readVarInt().toInt()).map {
                reader.readBytes(reader.readVarInt().toInt())
            }
        }
        val lockTime = reader.readBytes(4)
        assertEquals(serialized.size, reader.position)

        val stripped = ByteArrayBuilder().apply {
            writeBytes(version)
            writeVarInt(inputCount.toLong())
            repeat(inputCount) { index ->
                writeBytes(outpoints[index])
                writeVarInt(scripts[index].size.toLong())
                writeBytes(scripts[index])
                writeBytes(sequences[index])
            }
            writeVarInt(outputCount)
            writeBytes(outputEntries)
            writeBytes(lockTime)
        }.toByteArray()
        return ParsedWitnessTransaction(version, scripts, outputEntries, witness, stripped)
    }

    private fun bip143Hash(
        version: ByteArray,
        inputs: List<MeowcoinTransaction.UTXO>,
        outputEntries: ByteArray,
        signingIndex: Int
    ): ByteArray {
        val hashPrevouts = doubleSha256(ByteArrayBuilder().apply {
            inputs.forEach { input ->
                writeBytes(input.txHash.hexToBytes().reversedArray())
                writeInt32LE(input.outputIndex)
            }
        }.toByteArray())
        val hashSequence = doubleSha256(ByteArray(inputs.size * 4) { 0xFF.toByte() })
        val hashOutputs = doubleSha256(outputEntries)
        val signingInput = inputs[signingIndex]
        val witnessProgram = signingInput.scriptPubKey.hexToBytes()
        val scriptCode = byteArrayOf(0x76, 0xA9.toByte(), 0x14) +
            witnessProgram.copyOfRange(2, 22) + byteArrayOf(0x88.toByte(), 0xAC.toByte())

        return doubleSha256(ByteArrayBuilder().apply {
            writeBytes(version)
            writeBytes(hashPrevouts)
            writeBytes(hashSequence)
            writeBytes(signingInput.txHash.hexToBytes().reversedArray())
            writeInt32LE(signingInput.outputIndex)
            writeVarInt(scriptCode.size.toLong())
            writeBytes(scriptCode)
            writeInt64LE(signingInput.value)
            writeInt32LE(-1)
            writeBytes(hashOutputs)
            writeInt32LE(0)
            writeInt32LE(1)
        }.toByteArray())
    }

    private fun txId(serialized: ByteArray): String =
        doubleSha256(serialized).reversedArray().toHex()

    private fun doubleSha256(data: ByteArray): ByteArray {
        val sha256 = MessageDigest.getInstance("SHA-256")
        return sha256.digest(sha256.digest(data))
    }
}
