package io.bluewallet.blueberry

import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxHash
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import io.bluewallet.blueberry.labels.AddressLabel
import io.bluewallet.blueberry.labels.WalletLabels
import io.bluewallet.blueberry.labels.decryptWalletLabels
import io.bluewallet.blueberry.labels.labelingAccountKey
import io.bluewallet.blueberry.parse.CoinGraphCoin
import io.bluewallet.blueberry.parse.CoinLinkGraph
import io.bluewallet.blueberry.parse.CoinLinkage
import io.bluewallet.blueberry.parse.scriptHex
import io.bluewallet.blueberry.storage.StoredTx
import io.bluewallet.blueberry.storage.TxPaymentLabelRow
import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.blueberry.wallet.AddressScriptType
import io.bluewallet.blueberry.wallet.addressFromOutputScript
import io.bluewallet.blueberry.wallet.createWallet
import io.bluewallet.blueberry.wallet.deriveWatchWallet
import io.bluewallet.blueberry.wallet.saveWalletSecret
import kotlinx.coroutines.runBlocking
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UtxoDefaultLabelTest {
    private val txid = "ab".repeat(32)
    private val key = "$txid:0"
    private val address = "bc1qsavings"

    private fun coin(
        name: String? = null,
        addressLabel: String? = null,
        txLabel: String? = null,
    ) = WalletUtxoRow(
        key = key,
        txid = txid,
        vout = 0,
        outpointShort = "ab:0",
        valueSats = 10_000,
        scriptPubKey = ByteArray(0),
        address = address,
        amountLabel = "",
        height = 1,
        ageLabel = "1d",
        valueBar = "",
        name = name,
        isChange = false,
        addressLabel = addressLabel,
        txLabel = txLabel,
    )

    private fun peerCoin(
        id: String,
        height: Int,
        change: Boolean = false,
    ): WalletUtxoRow {
        val idTx = id.repeat(32).take(64)
        return coin().copy(
            key = "$idTx:0",
            txid = idTx,
            outpointShort = "$id:0",
            height = height,
            isChange = change,
            name = null,
        )
    }

    @Test
    fun address_label_is_the_default_when_the_coin_has_no_name() {
        assertEquals("savings", coin(addressLabel = " savings ").label)
    }

    @Test
    fun explicit_coin_name_wins_over_the_address_label() {
        assertEquals("rent", coin(name = " rent ", addressLabel = "savings").label)
    }

    @Test
    fun transaction_label_is_the_default_when_the_coin_has_no_name() {
        assertEquals("rent", coin(txLabel = " rent ").label)
        assertEquals("mine", coin(name = "mine", txLabel = "rent", addressLabel = "savings").label)
        assertEquals("rent", coin(name = "  ", txLabel = "rent", addressLabel = "savings").label)
        assertEquals("savings", coin(addressLabel = "savings").label)
    }

    @Test
    fun a_named_coin_names_another_coin_on_the_same_address_without_saving_it() {
        val db = createSqliteDatabase(":memory:")
        val named = peerCoin("aa", height = 1)
        val blank = peerCoin("bb", height = 2)
        db.utxoNames.upsert(named.key, "cafe")
        val snap = withStoredUtxoMeta(db, WalletTxsSnapshot(utxos = listOf(named, blank), utxosReady = true))
        val coins = snap.utxos.associateBy { it.key }
        assertEquals("cafe", coins.getValue(blank.key).label)
        assertNull(coins.getValue(blank.key).name)
        assertEquals(listOf(named.key), db.utxoNames.list().map { it.outpoint })

        db.utxoNames.delete(named.key)
        val cleared = withStoredUtxoMeta(db, snap).utxos.associateBy { it.key }
        assertNull(cleared.getValue(blank.key).label)
        db.close()
    }

    @Test
    fun a_change_name_does_not_spread_and_an_address_label_wins() {
        val db = createSqliteDatabase(":memory:")
        val change = peerCoin("cc", height = 3, change = true)
        val receive = peerCoin("dd", height = 4)
        db.utxoNames.upsert(change.key, "change from: groceries")
        val unspread = withStoredUtxoMeta(db, WalletTxsSnapshot(utxos = listOf(change, receive), utxosReady = true))
        assertNull(unspread.utxos.single { it.key == receive.key }.label)

        db.addressLabels.upsert(address, "savings")
        db.utxoNames.upsert(peerCoin("ee", height = 1).key, "cafe")
        val named = peerCoin("ee", height = 1)
        val blank = peerCoin("ff", height = 2)
        val labeled = withStoredUtxoMeta(db, WalletTxsSnapshot(utxos = listOf(named, blank), utxosReady = true))
        assertEquals("savings", labeled.utxos.single { it.key == blank.key }.label)
        db.close()
    }

    @Test
    fun a_change_name_on_a_receive_chain_does_not_spread() {
        val db = createSqliteDatabase(":memory:")
        val change = peerCoin("cc", height = 3, change = false)
        val receive = peerCoin("dd", height = 4)
        db.utxoNames.upsert(change.key, "change from: groceries")
        val snap = withStoredUtxoMeta(db, WalletTxsSnapshot(utxos = listOf(change, receive), utxosReady = true))
        val coins = snap.utxos.associateBy { it.key }
        assertEquals("change from: groceries", coins.getValue(change.key).name)
        assertNull(coins.getValue(receive.key).label)
        assertNull(coins.getValue(receive.key).peerLabel)
        db.close()
    }

    @Test
    fun a_spent_coins_name_still_labels_a_new_coin_on_that_address() {
        val db = createSqliteDatabase(":memory:")
        val script = byteArrayOf(0x00, 0x14) + ByteArray(20) { 7 }
        val addr = addressFromOutputScript(script)!!
        val spentTxid = "aa".repeat(32)
        val spentKey = "$spentTxid:0"
        val fresh = peerCoin("bb", height = 5).copy(address = addr.uppercase(), scriptPubKey = script)
        db.utxoNames.upsert(spentKey, "cafe")
        val graph =
            CoinLinkGraph(
                coins =
                    mapOf(
                        spentKey to CoinGraphCoin(spentKey, spentTxid, 1_000L, scriptHex(script), false, true),
                    ),
                txs = emptyMap(),
                neighbors = emptyMap(),
            )
        val snap =
            WalletTxsSnapshot(
                txs = listOf(WalletTxRow(spentTxid, "aa", 1, "", 0, "")),
                utxos = listOf(fresh),
                utxosReady = true,
                coinLinkGraph = graph,
            )
        val coin = withStoredUtxoMeta(db, snap).utxos.single()
        assertEquals("cafe", coin.label)
        assertNull(coin.name)
        assertEquals(listOf(spentKey), db.utxoNames.list().map { it.outpoint })
        db.close()
    }

    @Test
    fun two_named_coins_at_the_same_height_use_the_later_transaction_index() {
        val db = createSqliteDatabase(":memory:")
        val addr = "bc1qsamepeer"
        val older = peerCoin("ff", height = 10).copy(address = addr)
        val newer = peerCoin("aa", height = 10).copy(address = addr)
        val blank = peerCoin("cc", height = 10).copy(address = addr)
        db.utxoNames.upsert(older.key, "first")
        db.utxoNames.upsert(newer.key, "second")
        db.transactions.upsert(StoredTx(older.txid, 10, 1, "11".repeat(32), byteArrayOf(0x00), 0))
        db.transactions.upsert(StoredTx(newer.txid, 10, 4, "11".repeat(32), byteArrayOf(0x00), 0))
        val snap =
            WalletTxsSnapshot(
                utxos = listOf(older, newer, blank),
                utxosReady = true,
            )
        val coins = withStoredUtxoMeta(db, snap).utxos.associateBy { it.key }
        assertEquals("second", coins.getValue(blank.key).peerLabel)
        assertEquals("second", coins.getValue(older.key).peerLabel)
        assertEquals("first", coins.getValue(newer.key).peerLabel)
        db.close()
    }

    @Test
    fun blank_coin_name_falls_back_to_the_address_label() {
        assertEquals("savings", coin(name = "  ", addressLabel = "savings").label)
        assertNull(coin(name = " ", addressLabel = " ").label)
        assertNull(coin().label)
    }

    @Test
    fun stored_meta_refresh_picks_up_the_address_label() {
        val db = createSqliteDatabase(":memory:")
        db.addressLabels.upsert(address, "savings")
        val snap = withStoredUtxoMeta(db, WalletTxsSnapshot(utxos = listOf(coin()), utxosReady = true))
        assertEquals("savings", snap.utxos.single().addressLabel)
        assertNull(snap.utxos.single().name, "default must not become an explicit name")
        db.addressLabels.delete(address)
        assertNull(withStoredUtxoMeta(db, snap).utxos.single().label)
        db.close()
    }

    @Test
    fun labelling_an_address_labels_its_coins_until_they_get_their_own_name() {
        val db = createSqliteDatabase(":memory:")
        val wif = "L4ccWrPMmFDZw4kzAKFqJNxgHANjdy6b7YKNXMwB4xac4FLF3Tov"
        saveWalletSecret(db, wif)
        val wallet = createWallet(db)
        val recv = deriveWatchWallet(wif).addresses.first { it.scriptType == AddressScriptType.P2PKH }
        db.transactions.upsert(fundingTx(recv.scriptPubKey))
        val store = createWalletTxsStore()

        setAddressLabel(db, wallet, store, recv.address, "savings")
        val utxo = store.get().utxos.single()
        assertEquals("savings", utxo.label)
        assertNull(db.utxoNames.list().firstOrNull(), "default must not be persisted as a coin name")

        setUtxoName(db, wallet, store, utxo.key, "rent")
        assertEquals("rent", store.onlyCoinLabel())

        setUtxoName(db, wallet, store, utxo.key, "")
        assertEquals("savings", store.onlyCoinLabel())

        setAddressLabel(db, wallet, store, recv.address, "")
        assertNull(store.onlyCoinLabel())
        db.close()
    }

    @Test
    fun transaction_label_labels_its_coin_without_saving_a_coin_name() {
        val db = createSqliteDatabase(":memory:")
        val wif = "L4ccWrPMmFDZw4kzAKFqJNxgHANjdy6b7YKNXMwB4xac4FLF3Tov"
        saveWalletSecret(db, wif)
        val wallet = createWallet(db)
        val recv = deriveWatchWallet(wif).addresses.first { it.scriptType == AddressScriptType.P2PKH }
        val stored = fundingTx(recv.scriptPubKey)
        db.transactions.upsert(stored)
        db.txPaymentLabels.upsert(TxPaymentLabelRow(stored.txid, "rent"))
        db.addressLabels.upsert(recv.address, "savings")

        val coin = snapshotFromDb(db, 1, 1, wallet).utxos.single()
        assertEquals("rent", coin.label)
        assertNull(coin.name, "default must not become an explicit name")
        assertEquals(emptyList(), db.utxoNames.list())

        db.txPaymentLabels.delete(stored.txid)
        val cleared = withStoredUtxoMeta(db, snapshotFromDb(db, 2, 2, wallet))
        assertEquals("savings", cleared.utxos.single().label)
        db.close()
    }

    @Test
    fun coin_control_shows_the_address_label_as_the_coin_label() {
        val control = buildCoinControlSnapshot(listOf(coin(addressLabel = "savings")), CoinLinkage(emptyMap(), emptyMap()), emptySet())
        val c = control.coins.single()
        assertEquals("savings", c.label)
        assertTrue(CoinControlFilter.Labeled.matches(c))
        assertEquals("savings", coinRowSubtitle(c))
    }

    @Test
    fun coin_control_keeps_the_explicit_name_over_the_address_label() {
        val control = buildCoinControlSnapshot(listOf(coin(name = "rent", addressLabel = "savings")), CoinLinkage(emptyMap(), emptyMap()), emptySet())
        assertEquals("rent", control.coins.single().label)
    }

    @Test
    fun coin_sheet_edits_only_the_explicit_name() {
        val row = coinsRows(listOf(coin(addressLabel = "savings"))).single()
        assertNull(row.name)
        assertEquals("savings", row.addressLabel)
    }

    @Test
    fun bip329_export_carries_only_the_address_label_not_a_derived_coin_label() =
        runBlocking {
            val db = createSqliteDatabase(":memory:")
            val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
            saveWalletSecret(db, mnemonic)
            val wallet = createWallet(db)
            val recv = deriveWatchWallet(mnemonic).addresses.first { !it.change }
            db.transactions.upsert(fundingTx(recv.scriptPubKey))
            val store = createWalletTxsStore()
            setAddressLabel(db, wallet, store, recv.address, "savings")
            val coin = store.get().utxos.single()
            assertEquals("savings", coin.label)
            // Opening the coin sheet and saving untouched submits the explicit name only, which is empty.
            setUtxoName(db, wallet, store, coin.key, coinsRows(store.get().utxos).single().name.orEmpty())

            var body: String? = null
            publishWalletLabels(db) { _, b ->
                body = b
                "1"
            }

            val exported = decryptWalletLabels(labelingAccountKey(MnemonicCode.toSeed(mnemonic, "")), Base64.decode(body!!))
            assertEquals(WalletLabels(emptyList(), emptyList(), listOf(AddressLabel(recv.address, "savings"))), exported)
            db.close()
        }
}

private fun WalletTxsStore.onlyCoinLabel(): String? = get().utxos.single().label

private fun fundingTx(script: ByteArray): StoredTx {
    val tx =
        Transaction(
            2L,
            listOf(TxIn(OutPoint(TxHash(ByteArray(32).also { it[0] = 42 }), 0L), 0xffffffffL)),
            listOf(TxOut(Satoshi(9_664L), script)),
            0L,
        )
    return StoredTx(tx.txid.toString(), 800_000, 0, "aa".repeat(32), Transaction.write(tx), 9_664L)
}
