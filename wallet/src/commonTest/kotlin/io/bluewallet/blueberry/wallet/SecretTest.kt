package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.MnemonicCode
import io.bluewallet.blueberry.storage.createSqliteDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretTest {
    @Test
    fun trims_mnemonic_and_accepts_account_zpub() {
        assertEquals(ParsedWalletSecret(WalletSecretKind.MNEMONIC, ABANDON), parseWalletSecret("  $ABANDON  "))
        assertEquals(ParsedWalletSecret(WalletSecretKind.ZPUB, BLUE_ZPUB), parseWalletSecret(BLUE_ZPUB))
        assertEquals(
            ParsedWalletSecret(WalletSecretKind.MNEMONIC, ABANDON),
            parseWalletSecret(ABANDON.replace(" ", "  ").uppercase()),
        )
    }

    @Test
    fun rejects_invalid_mnemonic_empty_xpub_vpub_master_zpub_french() {
        assertFailsWith<IllegalArgumentException> { parseWalletSecret("not a real mnemonic phrase at all") }
        assertFailsWith<IllegalArgumentException> { parseWalletSecret("") }.also {
            assertTrue(it.message!!.contains("empty"))
        }
        assertFailsWith<IllegalArgumentException> { parseWalletSecret("   ") }.also {
            assertTrue(it.message!!.contains("empty"))
        }
        val seed = MnemonicCode.toSeed(ABANDON, "")
        val account = DeterministicWallet.generate(seed).derivePrivateKey(BIP84_ACCOUNT_PATH)
        val xpub = account.extendedPublicKey.encode(DeterministicWallet.xpub)
        assertFailsWith<IllegalArgumentException> { parseWalletSecret(xpub) }.also {
            assertTrue(it.message!!.contains("zpub"))
        }
        assertFailsWith<IllegalArgumentException> { parseWalletSecret("zprv" + "1".repeat(107)) }.also {
            assertTrue(it.message!!.contains("mainnet account zpub"))
        }
        val vpub = account.extendedPublicKey.encode(DeterministicWallet.vpub)
        assertTrue(vpub.startsWith("vpub"))
        assertFailsWith<IllegalArgumentException> { parseWalletSecret(vpub) }.also {
            assertTrue(it.message!!.contains("mainnet account zpub"))
        }
        val master = DeterministicWallet.generate(seed).extendedPublicKey.encode(DeterministicWallet.zpub)
        assertFailsWith<IllegalArgumentException> { parseWalletSecret(master) }.also {
            assertTrue(it.message!!.contains("account-level"))
        }
        assertFailsWith<IllegalArgumentException> {
            parseWalletSecret(
                "abaisser abaisser abaisser abaisser abaisser abaisser abaisser abaisser abaisser abaisser abaisser abeille",
            )
        }.also { assertTrue(it.message!!.contains("mnemonic")) }
    }

    @Test
    fun kv_round_trip_and_inspect() {
        val db = createSqliteDatabase(":memory:")
        assertFalse(hasWalletSecret(db))
        assertFailsWith<IllegalArgumentException> { loadWalletSecret(db) }
        saveWalletSecret(db, ABANDON)
        assertTrue(hasWalletSecret(db))
        assertEquals(ABANDON, loadWalletSecret(db))
        assertEquals(ABANDON, db.keyValue.get(WALLET_SECRET_KEY))
        assertEquals(WalletSecretInspection.Ok(ABANDON), inspectWalletSecret(db))
        db.keyValue.set(WALLET_SECRET_KEY, "not a real mnemonic phrase at all")
        val bad = inspectWalletSecret(db)
        assertTrue(bad is WalletSecretInspection.Invalid)
        assertTrue((bad as WalletSecretInspection.Invalid).detail.isNotEmpty())
        assertEquals("not a real mnemonic phrase at all", db.keyValue.get(WALLET_SECRET_KEY))
        db.close()
    }

    @Test
    fun accepts_wif_and_rejects_uncompressed_and_testnet() {
        assertEquals(ParsedWalletSecret(WalletSecretKind.WIF, WIF_BECH32), parseWalletSecret("  $WIF_BECH32  "))
        assertEquals(WalletSecretKind.WIF, parseWalletSecret(WIF_P2SH).kind)
        assertEquals(WalletSecretKind.WIF, parseWalletSecret(WIF_TAPROOT).kind)
        assertFailsWith<IllegalArgumentException> {
            parseWalletSecret("5JqSfbkoVDrzM5i7PH7939G5fwWVDWmnFTSMbVctAmet3tYMq2S")
        }.also { assertTrue(it.message!!.contains("compressed") || it.message!!.contains("WIF")) }
        assertFailsWith<IllegalArgumentException> { parseWalletSecret("KnotAValidWifKeyxxxxxxxxxxx") }
        assertFailsWith<IllegalArgumentException> {
            parseWalletSecret("cMahea7zqjxrtgAbB7LSGbcQUr1uX1ojuat9jZodMN87JcbXMTcA")
        }.also { assertTrue(it.message!!.contains("mainnet") || it.message!!.contains("testnet")) }
    }

    @Test
    fun accepts_mainnet_addresses_and_rejects_p2wsh() {
        assertEquals(ParsedWalletSecret(WalletSecretKind.ADDRESS, ADDR_BECH32), parseWalletSecret("  $ADDR_BECH32  "))
        assertEquals(WalletSecretKind.ADDRESS, parseWalletSecret(ADDR_LEGACY).kind)
        assertEquals(WalletSecretKind.ADDRESS, parseWalletSecret(ADDR_P2SH).kind)
        assertEquals(WalletSecretKind.ADDRESS, parseWalletSecret(ADDR_TAPROOT).kind)
        assertEquals(WalletSecretKind.ADDRESS, parseWalletSecret(BIP341_TAPROOT).kind)
        assertFailsWith<IllegalArgumentException> {
            parseWalletSecret("tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx")
        }.also { assertTrue(it.message!!.contains("invalid mainnet address")) }
        assertFailsWith<IllegalArgumentException> {
            parseWalletSecret("${ADDR_BECH32.substring(0, 8)} ${ADDR_BECH32.substring(8)}")
        }.also { assertTrue(it.message!!.contains("invalid mainnet address")) }
        val p2wsh = p2wshOpTrueAddress()
        assertFailsWith<IllegalArgumentException> { parseWalletSecret(p2wsh) }.also {
            assertTrue(it.message!!.contains("P2WSH") && it.message!!.contains("unsupported"))
        }
        assertEquals(WalletSecretKind.WIF, parseWalletSecret(WIF_BECH32).kind)
        val empty = createSqliteDatabase(":memory:")
        assertEquals(WalletSecretInspection.Missing, inspectWalletSecret(empty))
        empty.close()
    }
}

internal fun p2wshOpTrueAddress(): String {
    val script = byteArrayOf(0x51)
    val hash =
        fr.acinq.bitcoin.Crypto
            .sha256(script)
    return fr.acinq.bitcoin.Bech32
        .encodeWitnessAddress("bc", 0, hash)
}
