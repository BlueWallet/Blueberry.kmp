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
