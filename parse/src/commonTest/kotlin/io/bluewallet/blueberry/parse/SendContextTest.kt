package io.bluewallet.blueberry.parse

import fr.acinq.bitcoin.Bech32
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxHash
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.psbt.Psbt
import io.bluewallet.blueberry.storage.StoredTx
import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.blueberry.wallet.AddressScriptType
import io.bluewallet.blueberry.wallet.CreateWalletOptions
import io.bluewallet.blueberry.wallet.HD_SCRIPT_TYPES
import io.bluewallet.blueberry.wallet.HdWatchGaps
import io.bluewallet.blueberry.wallet.MAX_WATCH_COUNT
import io.bluewallet.blueberry.wallet.MnemonicChangePick
import io.bluewallet.blueberry.wallet.PsbtSendResult
import io.bluewallet.blueberry.wallet.SendAmount
import io.bluewallet.blueberry.wallet.SendInputUtxo
import io.bluewallet.blueberry.wallet.SignedSendResult
import io.bluewallet.blueberry.wallet.WatchGaps
import io.bluewallet.blueberry.wallet.createWallet
import io.bluewallet.blueberry.wallet.deriveWatchWallet
import io.bluewallet.blueberry.wallet.hd
import io.bluewallet.blueberry.wallet.hexToBytes
import io.bluewallet.blueberry.wallet.loadHdWatchGaps
import io.bluewallet.blueberry.wallet.resolvedScriptType
import io.bluewallet.blueberry.wallet.saveHdWatchGaps
import io.bluewallet.blueberry.wallet.saveWalletSecret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val WIF_LEGACY = "L4ccWrPMmFDZw4kzAKFqJNxgHANjdy6b7YKNXMwB4xac4FLF3Tov"
private const val DEST_LEGACY = "1GX36PGBUrF8XahZEGQqHqnJGW2vCZteoB"
private const val ADDR_BECH32 = "bc1q3rl0mkyk0zrtxfmqn9wpcd3gnaz00yv9yp0hxe"
private const val BIP86_INT_0 = "bc1p3qkhfews2uk44qtvauqyr2ttdsw7svhkl9nkm9s9c3x4ax5h60wqwruhk7"

private fun fundingTx(
    scriptPubKey: ByteArray,
    valueSats: Long,
    salt: Int = 42,
): Pair<String, ByteArray> {
    val prevHash = ByteArray(32)
    prevHash[0] = salt.toByte()
    val tx =
        Transaction(
            2L,
            listOf(TxIn(OutPoint(TxHash(prevHash), 0L), 0xffffffffL)),
            listOf(TxOut(Satoshi(valueSats), scriptPubKey)),
            0L,
        )
    return tx.txid.toString() to Transaction.write(tx)
}

class SendContextTest {
    @Test
    fun mnemonic_change_growth_is_capped_at_max_watch_count() {
        assertEquals(MAX_WATCH_COUNT, grownInternalCount(MAX_WATCH_COUNT, MAX_WATCH_COUNT - 1))
    }

    @Test
    fun missing_mnemonic_change_falls_back_to_last_watched_internal_of_type() {
        val watch = deriveWatchWallet(ABANDON_MNEMONIC, WatchGaps(1, 2))
        val pick = MnemonicChangePick(AddressScriptType.P2TR, 3, null)

        val address = findMnemonicChangeAddress(watch, pick)

        assertEquals(AddressScriptType.P2TR, address.resolvedScriptType())
        assertEquals(true, address.change)
        assertEquals(1, address.index)
    }

    @Test
    fun mnemonic_change_uses_random_type_internal_and_grows_past_window() {
        val db = createSqliteDatabase(":memory:")
        saveWalletSecret(db, ABANDON_MNEMONIC)
        val wallet = createWallet(db, CreateWalletOptions(secret = ABANDON_MNEMONIC, addressGap = 1))
        val watch = wallet.snapshot()
        val recv = watch.hd(AddressScriptType.P2WPKH, 0)
        val (fundTxid, fundBytes) = fundingTx(recv.scriptPubKey, 100_000L)
        db.transactions.upsert(
            StoredTx(fundTxid, 800_000, 0, "aa".repeat(32), fundBytes, 100_000L),
        )
        val forced =
            object : kotlin.random.Random() {
                override fun nextBits(bitCount: Int) = 0

                override fun nextInt(until: Int) = HD_SCRIPT_TYPES.indexOf(AddressScriptType.P2TR)
            }
        saveHdWatchGaps(db, HdWatchGaps.uniform(WatchGaps(1, 0)))
        wallet.refresh()
        val result =
            buildActiveSendTx(
                db,
                wallet,
                SendBuildParams(
                    utxos =
                        listOf(
                            SendInputUtxo(
                                fundTxid,
                                0,
                                100_000L,
                                recv.scriptPubKey,
                                nonWitnessUtxo = fundBytes,
                            ),
                        ),
                    toAddress = DEST_LEGACY,
                    amountSats = SendAmount.Exact(50_000L),
                    feeRateSatPerVb = 1.0,
                ),
                random = forced,
            )
        val signed = result as SignedSendResult
        val tx = Transaction.read(hexToBytes(signed.txHex))
        val changeScript = byteArrayOf(0x51, 0x20) + Bech32.decodeWitnessAddress(BIP86_INT_0).third
        assertTrue(tx.txOut.any { it.publicKeyScript.toByteArray().contentEquals(changeScript) })
        assertEquals(1, loadHdWatchGaps(db)[AddressScriptType.P2TR].internal)
        db.close()
    }

    @Test
    fun attaches_prev_tx_from_db_for_legacy_p2pkh_inputs() {
        val db = createSqliteDatabase(":memory:")
        saveWalletSecret(db, WIF_LEGACY)
        val wallet = createWallet(db)
        val watch = deriveWatchWallet(WIF_LEGACY)
        val recv = watch.addresses.first { it.scriptType == AddressScriptType.P2PKH }
        val (fundTxid, fundBytes) = fundingTx(recv.scriptPubKey, 100_000L)
        db.transactions.upsert(
            StoredTx(fundTxid, 800_000, 0, "aa".repeat(32), fundBytes, 100_000L),
        )
        val result =
            buildActiveSendTx(
                db,
                wallet,
                SendBuildParams(
                    utxos =
                        listOf(
                            SendInputUtxo(fundTxid, 0, 100_000L, recv.scriptPubKey),
                        ),
                    toAddress = DEST_LEGACY,
                    amountSats = SendAmount.Exact(50_000L),
                    feeRateSatPerVb = 1.0,
                ),
            )
        val signed = result as SignedSendResult
        val tx = Transaction.read(hexToBytes(signed.txHex))
        assertEquals(1, tx.txIn.size)
        db.close()
    }

    @Test
    fun change_goes_to_the_watched_address_for_address_wallets() {
        val db = createSqliteDatabase(":memory:")
        saveWalletSecret(db, ADDR_BECH32)
        val wallet = createWallet(db)
        val script = wallet.snapshot().scripts[0]
        val result =
            buildActiveSendTx(
                db,
                wallet,
                SendBuildParams(
                    utxos = listOf(SendInputUtxo("11".repeat(32), 0, 100_000L, script)),
                    toAddress = DEST_LEGACY,
                    amountSats = SendAmount.Exact(50_000L),
                    feeRateSatPerVb = 1.0,
                ),
            )
        val psbt = result as PsbtSendResult
        assertTrue(psbt.changeSats > 0L)
        val decoded = Psbt.read(hexToBytes(psbt.psbtHex)).right ?: error("psbt")
        val scripts =
            decoded.global.tx.txOut
                .map { it.publicKeyScript.toByteArray() }
        assertTrue(scripts.any { it.contentEquals(script) })
        db.close()
    }

    @Test
    fun pickUtxosByKeys_returns_selected_or_errors() {
        data class Row(
            val key: String,
            val valueSats: Long,
        )
        val rows = listOf(Row("a", 1), Row("b", 2))
        val selected = pickUtxosByKeys(rows, listOf("b", "a")) { it.key }
        assertEquals(PickUtxos.Ok(listOf(rows[0], rows[1])), selected)
        assertEquals(
            PickUtxos.Error("some selected UTXOs are no longer available"),
            pickUtxosByKeys(rows, listOf("a", "gone")) { it.key },
        )
        assertEquals(PickUtxos.Error("no UTXOs selected"), pickUtxosByKeys(rows, emptyList()) { it.key })
    }
}
