package com.meowcoin.wallet.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** P2PKH and native-P2WPKH transaction builder for the registered Bitcoin-family profiles. */
object MeowcoinTransaction {
    const val DUST_THRESHOLD = 100_000L
    const val DEFAULT_FEE_RATE = 1_000L
    const val MAX_TX_INPUTS = 500

    data class OutPoint(
        val txHash: String,
        val outputIndex: Int
    )

    data class UTXO(
        val txHash: String,
        val outputIndex: Int,
        val value: Long,
        val scriptPubKey: String
    ) {
        val outPoint: OutPoint
            get() = OutPoint(txHash, outputIndex)
    }

    enum class ScriptType { P2PKH, P2WPKH }

    /** A UTXO explicitly paired with its owner key and locking-script interpretation. */
    data class SpendableUTXO(
        val utxo: UTXO,
        val ownerKey: MeowcoinKeyPair,
        val scriptType: ScriptType = detectScriptType(utxo.scriptPubKey)
    )

    data class TxOutput(
        val address: String,
        val value: Long
    )

    /** Low-level BIP143 material kept internal so consensus vectors can test the signer directly. */
    internal data class Bip143Input(
        val txHash: String,
        val outputIndex: Int,
        val value: Long,
        val scriptPubKey: ByteArray,
        val sequence: Int
    )

    internal data class Bip143Output(
        val value: Long,
        val scriptPubKey: ByteArray
    )

    data class SignedTransaction(
        val txHex: String,
        val txId: String,
        val size: Int,
        val selectedOutpoints: List<OutPoint> = emptyList(),
        val actualFee: Long = 0L,
        val wtxId: String = txId,
        val baseSize: Int = size,
        val weight: Int = size * 4,
        val virtualSize: Int = size
    ) {
        val fee: Long
            get() = actualFee

        val selectedOutPoints: List<OutPoint>
            get() = selectedOutpoints

        val hasWitness: Boolean
            get() = baseSize != size
    }

    /**
     * Compatibility entry point for a single-key Meowcoin wallet.
     * Multi-address wallets must use the [SpendableUTXO] overload.
     */
    fun buildTransaction(
        keyPair: MeowcoinKeyPair,
        utxos: List<UTXO>,
        outputs: List<TxOutput>,
        changeAddress: String,
        feeRate: Long = keyPair.profile.defaultFeeRate
    ): SignedTransaction = buildTransaction(
        spendableUtxos = utxos.map { SpendableUTXO(it, keyPair) },
        outputs = outputs,
        changeAddress = changeAddress,
        profile = keyPair.profile,
        feeRate = feeRate
    )

    fun buildTransaction(
        keyPair: MeowcoinKeyPair,
        utxos: List<UTXO>,
        outputs: List<TxOutput>,
        changeAddress: String,
        profile: CoinProfile,
        feeRate: Long = profile.defaultFeeRate
    ): SignedTransaction = buildTransaction(
        spendableUtxos = utxos.map { SpendableUTXO(it, keyPair) },
        outputs = outputs,
        changeAddress = changeAddress,
        profile = profile,
        feeRate = feeRate
    )

    /** Build a payment, selecting inputs greedily and signing each with its own owner key. */
    fun buildTransaction(
        spendableUtxos: List<SpendableUTXO>,
        outputs: List<TxOutput>,
        changeAddress: String,
        profile: CoinProfile = CoinRegistry.MEWC,
        feeRate: Long = profile.defaultFeeRate
    ): SignedTransaction {
        validateSpendables(spendableUtxos, profile)
        validateOutputs(outputs, profile)
        requireFeeRate(feeRate)

        val totalOutput = sumOutputValues(outputs)
        val selected = selectUtxos(
            spendableUtxos = spendableUtxos,
            targetAmount = totalOutput,
            outputs = outputs,
            changeAddress = changeAddress,
            profile = profile,
            feeRate = feeRate
        )
        val totalInput = sumInputValues(selected)
        val estimatedFee = estimateFeeForOutputs(
            inputs = selected,
            outputs = outputs + TxOutput(changeAddress, 1L),
            profile = profile,
            feeRate = feeRate
        )
        val change = Math.subtractExact(Math.subtractExact(totalInput, totalOutput), estimatedFee)
        require(change >= 0) {
            "Insufficient funds. Need ${Math.addExact(totalOutput, estimatedFee)}, have $totalInput"
        }

        val finalOutputs = outputs.toMutableList()
        if (change >= profile.dustThreshold) {
            require(MeowcoinAddress.isValid(changeAddress, profile)) {
                "Invalid ${profile.ticker} change address"
            }
            finalOutputs += TxOutput(changeAddress, change)
        }

        return signAndDescribe(profile, selected, finalOutputs, totalInput)
    }

    /**
     * Spend every supplied UTXO to one destination, subtracting the fee from the output and
     * deliberately creating no change output. This is the safe implementation for a MAX send.
     */
    fun buildSendAllTransaction(
        keyPair: MeowcoinKeyPair,
        utxos: List<UTXO>,
        destinationAddress: String,
        feeRate: Long = keyPair.profile.defaultFeeRate
    ): SignedTransaction = buildSendAllTransaction(
        spendableUtxos = utxos.map { SpendableUTXO(it, keyPair) },
        destinationAddress = destinationAddress,
        profile = keyPair.profile,
        feeRate = feeRate
    )

    fun buildSendAllTransaction(
        spendableUtxos: List<SpendableUTXO>,
        destinationAddress: String,
        profile: CoinProfile = CoinRegistry.MEWC,
        feeRate: Long = profile.defaultFeeRate
    ): SignedTransaction {
        validateSpendables(spendableUtxos, profile)
        require(spendableUtxos.size <= MAX_TX_INPUTS) {
            "A transaction can use at most $MAX_TX_INPUTS inputs"
        }
        require(MeowcoinAddress.isValid(destinationAddress, profile)) {
            "Invalid ${profile.ticker} destination address"
        }
        requireFeeRate(feeRate)

        val totalInput = sumInputValues(spendableUtxos)
        val fee = estimateFeeForOutputs(
            inputs = spendableUtxos,
            outputs = listOf(TxOutput(destinationAddress, 1L)),
            profile = profile,
            feeRate = feeRate
        )
        val outputValue = Math.subtractExact(totalInput, fee)
        require(outputValue >= profile.dustThreshold) {
            "These UTXOs are worth too little to send after the network fee"
        }

        return signAndDescribe(
            profile = profile,
            inputs = spendableUtxos,
            outputs = listOf(TxOutput(destinationAddress, outputValue)),
            totalInput = totalInput
        )
    }

    /** Compatibility entry point for consolidating UTXOs owned by one key. */
    fun buildConsolidationTransaction(
        keyPair: MeowcoinKeyPair,
        utxos: List<UTXO>,
        destinationAddress: String,
        feeRate: Long = keyPair.profile.defaultFeeRate
    ): SignedTransaction = buildConsolidationTransaction(
        spendableUtxos = utxos.map { SpendableUTXO(it, keyPair) },
        destinationAddress = destinationAddress,
        profile = keyPair.profile,
        feeRate = feeRate
    )

    fun buildConsolidationTransaction(
        keyPair: MeowcoinKeyPair,
        utxos: List<UTXO>,
        destinationAddress: String,
        profile: CoinProfile,
        feeRate: Long = profile.defaultFeeRate
    ): SignedTransaction = buildConsolidationTransaction(
        spendableUtxos = utxos.map { SpendableUTXO(it, keyPair) },
        destinationAddress = destinationAddress,
        profile = profile,
        feeRate = feeRate
    )

    fun buildConsolidationTransaction(
        spendableUtxos: List<SpendableUTXO>,
        destinationAddress: String,
        profile: CoinProfile = CoinRegistry.MEWC,
        feeRate: Long = profile.defaultFeeRate
    ): SignedTransaction {
        require(spendableUtxos.size >= 2) { "At least two UTXOs are required to consolidate" }
        require(spendableUtxos.size <= MAX_TX_INPUTS) {
            "A consolidation can use at most $MAX_TX_INPUTS inputs"
        }
        validateSpendables(spendableUtxos, profile)
        require(MeowcoinAddress.isValid(destinationAddress, profile)) {
            "Invalid ${profile.ticker} consolidation destination"
        }
        requireFeeRate(feeRate)

        val totalInput = sumInputValues(spendableUtxos)
        val fee = estimateFeeForOutputs(
            inputs = spendableUtxos,
            outputs = listOf(TxOutput(destinationAddress, 1L)),
            profile = profile,
            feeRate = feeRate
        )
        val outputValue = Math.subtractExact(totalInput, fee)
        require(outputValue >= profile.dustThreshold) {
            "These UTXOs are worth too little to consolidate after the network fee"
        }

        return signAndDescribe(
            profile = profile,
            inputs = spendableUtxos,
            outputs = listOf(TxOutput(destinationAddress, outputValue)),
            totalInput = totalInput
        )
    }

    /** Legacy P2PKH size estimate retained for existing callers. */
    fun estimateFee(inputCount: Int, outputCount: Int, feeRate: Long = DEFAULT_FEE_RATE): Long {
        require(inputCount >= 0 && outputCount >= 0) { "Counts must be non-negative" }
        requireFeeRate(feeRate)
        return Math.multiplyExact(estimateLegacyP2pkhSize(inputCount, outputCount).toLong(), feeRate)
    }

    fun estimateFee(
        inputCount: Int,
        outputCount: Int,
        profile: CoinProfile,
        feeRate: Long = profile.defaultFeeRate
    ): Long = estimateFee(inputCount, outputCount, feeRate)

    /**
     * Estimate a one-destination transaction from the concrete wallet-owned inputs.
     * Unlike the count-only compatibility API, this charges P2WPKH inputs by virtual size.
     */
    fun estimateFee(
        spendableUtxos: List<SpendableUTXO>,
        destinationAddress: String,
        profile: CoinProfile = CoinRegistry.MEWC,
        feeRate: Long = profile.defaultFeeRate
    ): Long {
        validateSpendables(spendableUtxos, profile)
        require(MeowcoinAddress.isValid(destinationAddress, profile)) {
            "Invalid ${profile.ticker} destination address"
        }
        requireFeeRate(feeRate)
        return estimateFeeForOutputs(
            inputs = spendableUtxos,
            outputs = listOf(TxOutput(destinationAddress, 1L)),
            profile = profile,
            feeRate = feeRate
        )
    }

    /** Classify a wallet-owned script. Other output types remain valid send targets only. */
    fun detectScriptType(scriptPubKeyHex: String): ScriptType {
        val script = try {
            scriptPubKeyHex.hexToBytes()
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("UTXO scriptPubKey must be hex", e)
        }
        return when {
            isP2pkhScript(script) -> ScriptType.P2PKH
            isP2wpkhScript(script) -> ScriptType.P2WPKH
            else -> throw IllegalArgumentException("Only P2PKH and native P2WPKH UTXOs can be spent")
        }
    }

    private fun selectUtxos(
        spendableUtxos: List<SpendableUTXO>,
        targetAmount: Long,
        outputs: List<TxOutput>,
        changeAddress: String,
        profile: CoinProfile,
        feeRate: Long
    ): List<SpendableUTXO> {
        require(MeowcoinAddress.isValid(changeAddress, profile)) {
            "Invalid ${profile.ticker} change address"
        }

        val sorted = spendableUtxos.sortedByDescending { it.utxo.value }
        val selected = mutableListOf<SpendableUTXO>()
        var total = 0L

        for (spendable in sorted) {
            if (selected.size == MAX_TX_INPUTS) break
            selected += spendable
            total = Math.addExact(total, spendable.utxo.value)
            val estimatedFee = estimateFeeForOutputs(
                inputs = selected,
                outputs = outputs + TxOutput(changeAddress, 1L),
                profile = profile,
                feeRate = feeRate
            )
            if (total >= Math.addExact(targetAmount, estimatedFee)) return selected
        }

        if (selected.size == MAX_TX_INPUTS && sorted.size > MAX_TX_INPUTS) {
            throw IllegalStateException(
                "Too many small coins to send this amount in one transaction " +
                    "(maximum $MAX_TX_INPUTS inputs). Consolidate first or send less."
            )
        }

        val finalFee = estimateFeeForOutputs(
            inputs = selected,
            outputs = outputs + TxOutput(changeAddress, 1L),
            profile = profile,
            feeRate = feeRate
        )
        throw IllegalArgumentException(
            "Insufficient funds. Need ${Math.addExact(targetAmount, finalFee)}, have $total"
        )
    }

    private fun validateSpendables(
        spendableUtxos: List<SpendableUTXO>,
        profile: CoinProfile
    ) {
        require(profile.enabled) { profile.disabledReason ?: "${profile.name} is disabled" }
        require(spendableUtxos.isNotEmpty()) { "No UTXOs available" }

        val seen = mutableSetOf<String>()
        for (spendable in spendableUtxos) {
            val utxo = spendable.utxo
            val ownerKey = spendable.ownerKey
            require(utxo.txHash.matches(Regex("[0-9a-fA-F]{64}"))) {
                "Transaction hash must be 32-byte hex"
            }
            require(utxo.outputIndex >= 0) { "Output index must be non-negative" }
            require(utxo.value > 0) { "UTXO value must be positive" }
            val canonicalOutpoint = "${utxo.txHash.lowercase()}:${utxo.outputIndex}"
            require(seen.add(canonicalOutpoint)) {
                "Duplicate UTXO: ${utxo.txHash}:${utxo.outputIndex}"
            }
            require(ownerKey.profile.id == profile.id) {
                "Owner key belongs to ${ownerKey.profile.ticker}, not ${profile.ticker}"
            }

            val actualScript = try {
                utxo.scriptPubKey.hexToBytes()
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("UTXO scriptPubKey must be hex", e)
            }
            require(detectScriptType(utxo.scriptPubKey) == spendable.scriptType) {
                "Declared UTXO script type does not match scriptPubKey"
            }
            val expectedAddress = when (spendable.scriptType) {
                ScriptType.P2PKH -> ownerKey.toAddress()
                ScriptType.P2WPKH -> {
                    require(profile.id == CoinRegistry.MEWC.id || profile.id == CoinRegistry.LTC.id) {
                        "Native SegWit spending is currently enabled only for MEWC and LTC"
                    }
                    ownerKey.toP2WPKHAddress()
                }
            }
            val expectedScript = MeowcoinAddress.toScriptPubKey(expectedAddress, profile)
            require(actualScript.contentEquals(expectedScript)) {
                "Owner key does not match UTXO ${utxo.txHash}:${utxo.outputIndex}"
            }
        }
    }

    private fun validateOutputs(outputs: List<TxOutput>, profile: CoinProfile) {
        require(outputs.isNotEmpty()) { "No outputs specified" }
        outputs.forEach { output ->
            require(output.value >= profile.dustThreshold) {
                "${profile.ticker} output is below the dust threshold"
            }
            require(MeowcoinAddress.isValid(output.address, profile)) {
                "Invalid ${profile.ticker} destination address"
            }
        }
        sumOutputValues(outputs)
    }

    private fun requireFeeRate(feeRate: Long) {
        require(feeRate > 0) { "Fee rate must be positive" }
    }

    private fun signAndDescribe(
        profile: CoinProfile,
        inputs: List<SpendableUTXO>,
        outputs: List<TxOutput>,
        totalInput: Long
    ): SignedTransaction {
        val signedTransaction = signTransaction(profile, inputs, outputs)
        val actualFee = Math.subtractExact(totalInput, sumOutputValues(outputs))
        require(actualFee >= 0) { "Transaction outputs exceed selected inputs" }

        val txId = transactionId(signedTransaction.stripped)
        val wtxId = transactionId(signedTransaction.full)
        val baseSize = signedTransaction.stripped.size
        val totalSize = signedTransaction.full.size
        val weight = Math.addExact(
            Math.multiplyExact(baseSize, 4),
            totalSize - baseSize
        )

        return SignedTransaction(
            txHex = signedTransaction.full.toHex(),
            txId = txId,
            size = totalSize,
            selectedOutpoints = inputs.map { it.utxo.outPoint },
            actualFee = actualFee,
            wtxId = wtxId,
            baseSize = baseSize,
            weight = weight,
            virtualSize = (weight + 3) / 4
        )
    }

    private fun estimateFeeForOutputs(
        inputs: List<SpendableUTXO>,
        outputs: List<TxOutput>,
        profile: CoinProfile,
        feeRate: Long
    ): Long {
        val outputScriptSizes = outputs.map { output ->
            MeowcoinAddress.toScriptPubKey(output.address, profile).size
        }
        val virtualSize = estimateVirtualSize(inputs, outputScriptSizes)
        return Math.multiplyExact(virtualSize.toLong(), feeRate)
    }

    private fun estimateLegacyP2pkhSize(inputs: Int, outputs: Int): Int =
        10 + Math.addExact(Math.multiplyExact(inputs, 148), Math.multiplyExact(outputs, 34))

    private fun estimateVirtualSize(
        inputs: List<SpendableUTXO>,
        outputScriptSizes: List<Int>
    ): Int {
        var baseSize = 4 + varIntSize(inputs.size.toLong()) + 4
        for (input in inputs) {
            val inputBaseSize = when (input.scriptType) {
                ScriptType.P2PKH -> 116 + input.ownerKey.encodedPublicKey().size
                ScriptType.P2WPKH -> 41
            }
            baseSize = Math.addExact(baseSize, inputBaseSize)
        }
        baseSize = Math.addExact(baseSize, varIntSize(outputScriptSizes.size.toLong()))
        for (scriptSize in outputScriptSizes) {
            baseSize = Math.addExact(baseSize, 8 + varIntSize(scriptSize.toLong()) + scriptSize)
        }

        if (inputs.none { it.scriptType == ScriptType.P2WPKH }) return baseSize

        var witnessSize = 2 // marker and flag
        for (input in inputs) {
            witnessSize = Math.addExact(
                witnessSize,
                when (input.scriptType) {
                    ScriptType.P2PKH -> 1 // empty witness stack
                    ScriptType.P2WPKH -> {
                        val publicKeySize = input.ownerKey.compressedPublicKey().size
                        1 + varIntSize(MAX_SIGNATURE_WITH_HASH_TYPE.toLong()) +
                            MAX_SIGNATURE_WITH_HASH_TYPE +
                            varIntSize(publicKeySize.toLong()) + publicKeySize
                    }
                }
            )
        }
        val weight = Math.addExact(Math.multiplyExact(baseSize, 4), witnessSize)
        return (weight + 3) / 4
    }

    private fun varIntSize(value: Long): Int = when {
        value < 0xFD -> 1
        value <= 0xFFFF -> 3
        value <= 0xFFFFFFFFL -> 5
        else -> 9
    }

    private fun sumInputValues(inputs: List<SpendableUTXO>): Long =
        inputs.fold(0L) { total, input -> Math.addExact(total, input.utxo.value) }

    private fun sumOutputValues(outputs: List<TxOutput>): Long =
        outputs.fold(0L) { total, output -> Math.addExact(total, output.value) }

    private data class InputSignature(
        val signatureWithHashType: ByteArray,
        val publicKey: ByteArray
    )

    private data class SignedSerialization(
        val stripped: ByteArray,
        val full: ByteArray
    )

    private fun signTransaction(
        profile: CoinProfile,
        inputs: List<SpendableUTXO>,
        outputs: List<TxOutput>
    ): SignedSerialization {
        val signatures = inputs.mapIndexed { index, spendable ->
            val signatureHash = when (spendable.scriptType) {
                ScriptType.P2PKH -> createLegacySignatureHash(profile, inputs, outputs, index)
                ScriptType.P2WPKH -> createBip143SignatureHash(profile, inputs, outputs, index)
            }
            InputSignature(
                signatureWithHashType = spendable.ownerKey.sign(signatureHash) +
                    byteArrayOf(SIGHASH_ALL.toByte()),
                publicKey = if (spendable.scriptType == ScriptType.P2WPKH) {
                    spendable.ownerKey.compressedPublicKey()
                } else {
                    spendable.ownerKey.encodedPublicKey()
                }
            )
        }

        val hasWitness = inputs.any { it.scriptType == ScriptType.P2WPKH }
        val stripped = serializeSignedTransaction(profile, inputs, outputs, signatures, false)
        val full = if (hasWitness) {
            serializeSignedTransaction(profile, inputs, outputs, signatures, true)
        } else {
            stripped
        }
        return SignedSerialization(stripped, full)
    }

    private fun serializeSignedTransaction(
        profile: CoinProfile,
        inputs: List<SpendableUTXO>,
        outputs: List<TxOutput>,
        signatures: List<InputSignature>,
        includeWitness: Boolean
    ): ByteArray = ByteArrayBuilder().apply {
        writeInt32LE(profile.transactionVersion)
        if (includeWitness) writeBytes(byteArrayOf(0x00, 0x01))
        writeVarInt(inputs.size.toLong())
        inputs.forEachIndexed { index, spendable ->
            writeOutpoint(spendable.utxo)
            val scriptSignature = if (spendable.scriptType == ScriptType.P2PKH) {
                ByteArrayBuilder().apply {
                    writePushData(signatures[index].signatureWithHashType)
                    writePushData(signatures[index].publicKey)
                }.toByteArray()
            } else {
                byteArrayOf()
            }
            writeVarInt(scriptSignature.size.toLong())
            writeBytes(scriptSignature)
            writeInt32LE(SEQUENCE)
        }
        writeOutputs(profile, outputs)
        if (includeWitness) {
            inputs.forEachIndexed { index, spendable ->
                if (spendable.scriptType == ScriptType.P2WPKH) {
                    writeVarInt(2)
                    writeVarBytes(signatures[index].signatureWithHashType)
                    writeVarBytes(signatures[index].publicKey)
                } else {
                    writeVarInt(0)
                }
            }
        }
        writeInt32LE(LOCK_TIME)
    }.toByteArray()

    private fun createLegacySignatureHash(
        profile: CoinProfile,
        inputs: List<SpendableUTXO>,
        outputs: List<TxOutput>,
        inputIndex: Int
    ): ByteArray = doubleSha256(ByteArrayBuilder().apply {
        writeInt32LE(profile.transactionVersion)
        writeVarInt(inputs.size.toLong())
        inputs.forEachIndexed { index, spendable ->
            writeOutpoint(spendable.utxo)
            val scriptCode = if (index == inputIndex) {
                spendable.utxo.scriptPubKey.hexToBytes()
            } else {
                byteArrayOf()
            }
            writeVarBytes(scriptCode)
            writeInt32LE(SEQUENCE)
        }
        writeOutputs(profile, outputs)
        writeInt32LE(LOCK_TIME)
        writeInt32LE(SIGHASH_ALL)
    }.toByteArray())

    /** BIP143 SIGHASH_ALL digest for a native P2WPKH input. */
    private fun createBip143SignatureHash(
        profile: CoinProfile,
        inputs: List<SpendableUTXO>,
        outputs: List<TxOutput>,
        inputIndex: Int
    ): ByteArray = bip143SignatureHash(
        transactionVersion = profile.transactionVersion,
        inputs = inputs.map { spendable ->
            Bip143Input(
                txHash = spendable.utxo.txHash,
                outputIndex = spendable.utxo.outputIndex,
                value = spendable.utxo.value,
                scriptPubKey = spendable.utxo.scriptPubKey.hexToBytes(),
                sequence = SEQUENCE
            )
        },
        outputs = outputs.map { output ->
            Bip143Output(
                value = output.value,
                scriptPubKey = MeowcoinAddress.toScriptPubKey(output.address, profile)
            )
        },
        inputIndex = inputIndex,
        lockTime = LOCK_TIME
    )

    /** SIGHASH_ALL digest defined by BIP143 for a native P2WPKH input. */
    internal fun bip143SignatureHash(
        transactionVersion: Int,
        inputs: List<Bip143Input>,
        outputs: List<Bip143Output>,
        inputIndex: Int,
        lockTime: Int,
        sighashType: Int = SIGHASH_ALL
    ): ByteArray {
        require(sighashType == SIGHASH_ALL) { "Only BIP143 SIGHASH_ALL is supported" }
        require(inputIndex in inputs.indices) { "BIP143 input index is out of range" }
        val signingInput = inputs[inputIndex]
        val witnessProgram = signingInput.scriptPubKey
        require(isP2wpkhScript(witnessProgram)) { "BIP143 input must be native P2WPKH" }
        val publicKeyHash = witnessProgram.copyOfRange(2, 22)
        val scriptCode = byteArrayOf(0x76, 0xA9.toByte(), 0x14) + publicKeyHash +
            byteArrayOf(0x88.toByte(), 0xAC.toByte())

        val hashPrevouts = doubleSha256(ByteArrayBuilder().apply {
            inputs.forEach { input ->
                writeBytes(input.txHash.hexToBytes().reversedArray())
                writeInt32LE(input.outputIndex)
            }
        }.toByteArray())
        val hashSequence = doubleSha256(ByteArrayBuilder().apply {
            inputs.forEach { input -> writeInt32LE(input.sequence) }
        }.toByteArray())
        val hashOutputs = doubleSha256(ByteArrayBuilder().apply {
            outputs.forEach { output ->
                writeInt64LE(output.value)
                writeVarBytes(output.scriptPubKey)
            }
        }.toByteArray())

        return doubleSha256(ByteArrayBuilder().apply {
            writeInt32LE(transactionVersion)
            writeBytes(hashPrevouts)
            writeBytes(hashSequence)
            writeBytes(signingInput.txHash.hexToBytes().reversedArray())
            writeInt32LE(signingInput.outputIndex)
            writeVarBytes(scriptCode)
            writeInt64LE(signingInput.value)
            writeInt32LE(signingInput.sequence)
            writeBytes(hashOutputs)
            writeInt32LE(lockTime)
            writeInt32LE(sighashType)
        }.toByteArray())
    }

    private fun ByteArrayBuilder.writeOutpoint(input: UTXO) {
        writeBytes(input.txHash.hexToBytes().reversedArray())
        writeInt32LE(input.outputIndex)
    }

    private fun ByteArrayBuilder.writeOutputs(profile: CoinProfile, outputs: List<TxOutput>) {
        writeVarInt(outputs.size.toLong())
        outputs.forEach { writeOutput(profile, it) }
    }

    private fun ByteArrayBuilder.writeOutput(profile: CoinProfile, output: TxOutput) {
        writeInt64LE(output.value)
        writeVarBytes(MeowcoinAddress.toScriptPubKey(output.address, profile))
    }

    private fun ByteArrayBuilder.writeVarBytes(bytes: ByteArray) {
        writeVarInt(bytes.size.toLong())
        writeBytes(bytes)
    }

    private fun isP2pkhScript(script: ByteArray): Boolean =
        script.size == 25 &&
            script[0] == 0x76.toByte() && script[1] == 0xA9.toByte() &&
            script[2] == 0x14.toByte() && script[23] == 0x88.toByte() &&
            script[24] == 0xAC.toByte()

    private fun isP2wpkhScript(script: ByteArray): Boolean =
        script.size == 22 && script[0] == 0x00.toByte() && script[1] == 0x14.toByte()

    private fun transactionId(serialized: ByteArray): String =
        doubleSha256(serialized).reversedArray().toHex()

    private fun doubleSha256(data: ByteArray): ByteArray {
        val sha256 = MessageDigest.getInstance("SHA-256")
        return sha256.digest(sha256.digest(data))
    }

    private const val MAX_SIGNATURE_WITH_HASH_TYPE = 73
    private const val SIGHASH_ALL = 1
    private const val SEQUENCE = -1
    private const val LOCK_TIME = 0
}

class ByteArrayBuilder {
    private val buffer = mutableListOf<Byte>()

    fun writeBytes(bytes: ByteArray) {
        buffer.addAll(bytes.toList())
    }

    fun writeInt32LE(value: Int) {
        writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
    }

    fun writeInt64LE(value: Long) {
        writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array())
    }

    fun writeVarInt(value: Long) {
        require(value >= 0) { "VarInt cannot be negative" }
        when {
            value < 0xFD -> writeBytes(byteArrayOf(value.toByte()))
            value <= 0xFFFF -> {
                writeBytes(byteArrayOf(0xFD.toByte()))
                writeBytes(
                    ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
                        .putShort(value.toShort()).array()
                )
            }
            value <= 0xFFFFFFFFL -> {
                writeBytes(byteArrayOf(0xFE.toByte()))
                writeInt32LE(value.toInt())
            }
            else -> {
                writeBytes(byteArrayOf(0xFF.toByte()))
                writeInt64LE(value)
            }
        }
    }

    fun writePushData(data: ByteArray) {
        when {
            data.size < 0x4C -> writeBytes(byteArrayOf(data.size.toByte()))
            data.size <= 0xFF -> writeBytes(byteArrayOf(0x4C.toByte(), data.size.toByte()))
            else -> {
                writeBytes(byteArrayOf(0x4D.toByte()))
                writeBytes(
                    ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
                        .putShort(data.size.toShort()).array()
                )
            }
        }
        writeBytes(data)
    }

    fun toByteArray(): ByteArray = buffer.toByteArray()
}

class ByteArrayReader(private val data: ByteArray) {
    var position: Int = 0
        private set

    fun readBytes(count: Int): ByteArray {
        require(count >= 0 && position <= data.size - count) { "Unexpected end of transaction data" }
        val result = data.copyOfRange(position, position + count)
        position += count
        return result
    }

    fun readVarInt(): Long {
        val first = readBytes(1)[0].toInt() and 0xFF
        return when (first) {
            in 0 until 0xFD -> first.toLong()
            0xFD -> ByteBuffer.wrap(readBytes(2)).order(ByteOrder.LITTLE_ENDIAN)
                .short.toLong() and 0xFFFF
            0xFE -> ByteBuffer.wrap(readBytes(4)).order(ByteOrder.LITTLE_ENDIAN)
                .int.toLong() and 0xFFFFFFFFL
            else -> ByteBuffer.wrap(readBytes(8)).order(ByteOrder.LITTLE_ENDIAN).long
        }
    }

    fun readRemaining(): ByteArray = readBytes(data.size - position)
}
