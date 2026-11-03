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
import io.bluewallet.blueberry.parse.CoinLinkage
import io.bluewallet.blueberry.storage.StoredTx
import io.bluewallet.blueberry.storage.createSqliteDatabase
import io.bluewallet.blueberry.wallet.AddressScriptType
import io.bluewallet.blueberry.wallet.createWallet
import io.bluewallet.blueberry.wallet.deriveWatchWallet
import io.bluewallet.blueberry.wallet.saveWalletSecret
import kotlinx.coroutines.runBlocking
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UtxoDefaultLabelTest {
    private val txid = "ab".repeat(32)
    private val key = "$txid:0"
    private val address = "bc1qsavings"

    private fun coin(
        name: String? = null,
        addressLabel: String? = null,
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
    )

    @Test
    fun address_label_is_the_default_when_the_coin_has_no_name() {
        assertEquals("savings", coin(addressLabel = " savings ").label)
    }

    @Test
    fun explicit_coin_name_wins_over_the_address_label() {
        assertEquals("rent", coin(name = " rent ", addressLabel = "savings").label)
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
    fun coin_control_shows_the_address_label_as_the_coin_label() {
        val control = buildCoinControlSnapshot(listOf(coin(addressLabel = "savings")), CoinLinkage(emptyMap(), emptyMap()), emptySet())
        val c = control.coins.single()
        assertEquals("savings", c.label)
        assertEquals(CoinPrivacyState.Labeled, c.privacyState)
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
