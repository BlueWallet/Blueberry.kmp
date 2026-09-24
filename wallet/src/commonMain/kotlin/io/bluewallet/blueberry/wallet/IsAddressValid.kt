package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.Base58
import fr.acinq.bitcoin.Base58Check
import fr.acinq.bitcoin.Bech32
import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.Script

fun isAddressValid(address: String): Boolean {
    val value = address.trim()
    if (value.isEmpty()) return false
    return try {
        if (!value.lowercase().startsWith("bc1")) {
            Bitcoin
                .addressToPublicKeyScript(Block.LivenetGenesisBlock.hash, value)
                .isRight
        } else {
            val decoded = Bech32.decodeWitnessAddress(value)
            val version = decoded.second.toInt() and 0xff
            val program = decoded.third
            when (version) {
                0 -> Bitcoin.addressToPublicKeyScript(Block.LivenetGenesisBlock.hash, value).isRight
                1 -> {
                    if (program.size != 32) return false
                    val compressed = byteArrayOf(2) + program
                    Crypto.isPubKeyValid(compressed)
                }
                else -> false
            }
        }
    } catch (_: Exception) {
        false
    }
}

fun watchAddressScriptType(address: String): AddressScriptType {
    val value = address.trim()
    if (!isAddressValid(value)) throw IllegalArgumentException("invalid mainnet address")
    if (value.lowercase().startsWith("bc1")) {
        val decoded = Bech32.decodeWitnessAddress(value)
        val version = decoded.second.toInt() and 0xff
        val program = decoded.third
        if (version == 0) {
            if (program.size == 20) return AddressScriptType.P2WPKH
            if (program.size == 32) throw IllegalArgumentException("P2WSH watch addresses are unsupported")
            throw IllegalArgumentException("unsupported witness v0 address")
        }
        if (version == 1 && program.size == 32) return AddressScriptType.P2TR
        throw IllegalArgumentException("unsupported witness address")
    }
    val (prefix, _) = Base58Check.decode(value)
    return when (prefix) {
        Base58.Prefix.PubkeyAddress -> AddressScriptType.P2PKH
        Base58.Prefix.ScriptAddress -> AddressScriptType.P2SH_P2WPKH
        else -> throw IllegalArgumentException("unsupported mainnet address version")
    }
}

fun outputScriptFromAddress(address: String): ByteArray {
    val value = address.trim()
    val lower = value.lowercase()
    if (lower.startsWith("bc1")) {
        val decoded = Bech32.decodeWitnessAddress(value)
        val version = decoded.second.toInt() and 0xff
        val program = decoded.third
        if (version == 1 && program.size == 32) {
            return byteArrayOf(0x51, 0x20) + program
        }
    }
    val script =
        Bitcoin
            .addressToPublicKeyScript(Block.LivenetGenesisBlock.hash, value)
            .right
            ?: throw IllegalArgumentException("invalid mainnet address")
    return Script.write(script)
}

fun inputAddresses(
    txBytes: ByteArray?,
    parentTx: (String) -> ByteArray?,
): List<String> {
    val tx = readTx(txBytes)?.takeUnless { it.isCoinbase() } ?: return emptyList()
    return tx.txIn.mapNotNull { input ->
        val parentId =
            prevoutTxidDisplay(
                input.outPoint.hash.value
                    .toByteArray(),
            )
        val parent = readTx(parentTx(parentId))
        val output = parent?.txOut?.getOrNull(input.outPoint.index.toInt())
        output?.let { addressFromOutputScript(it.publicKeyScript.toByteArray()) }
    }
}

fun destinationAddresses(
    txBytes: ByteArray?,
    changeScripts: List<ByteArray>,
): List<String> {
    val tx = readTx(txBytes) ?: return emptyList()
    return tx.txOut.mapNotNull { output ->
        val script = output.publicKeyScript.toByteArray()
        val change = changeScripts.any { it.contentEquals(script) }
        if (change) null else addressFromOutputScript(script)
    }
}

fun addressFromOutputScript(script: ByteArray): String? =
    witnessAddress(script, size = 22, version = 0, header = 0x00)
        ?: witnessAddress(script, size = 34, version = 1, header = 0x51)
        ?: p2pkhAddress(script)
        ?: p2shAddress(script)

private fun readTx(bytes: ByteArray?): fr.acinq.bitcoin.Transaction? =
    bytes?.let {
        runCatching {
            fr.acinq.bitcoin.Transaction
                .read(it)
        }.getOrNull()
    }

private fun witnessAddress(
    script: ByteArray,
    size: Int,
    version: Int,
    header: Int,
): String? {
    val programStart = 2
    val match = script.size == size && script[0] == header.toByte() && script[1] == (size - programStart).toByte()
    val program = script.copyOfRange(programStart, size)
    return if (match) Bech32.encodeWitnessAddress("bc", version.toByte(), program) else null
}

private fun p2pkhAddress(script: ByteArray): String? {
    val match = script.size == 25 && script[0] == 0x76.toByte() && script[1] == 0xa9.toByte()
    val tail = match && script[2] == 0x14.toByte() && script[23] == 0x88.toByte() && script[24] == 0xac.toByte()
    return if (tail) Base58Check.encode(Base58.Prefix.PubkeyAddress, script.copyOfRange(3, 23)) else null
}

private fun p2shAddress(script: ByteArray): String? {
    val match = script.size == 23 && script[0] == 0xa9.toByte() && script[1] == 0x14.toByte()
    return if (match && script[22] == 0x87.toByte()) {
        Base58Check.encode(Base58.Prefix.ScriptAddress, script.copyOfRange(2, 22))
    } else {
        null
    }
}
