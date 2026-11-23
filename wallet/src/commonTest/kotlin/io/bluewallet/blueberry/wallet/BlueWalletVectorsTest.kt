package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.DeterministicWallet.hardened
import fr.acinq.bitcoin.KeyPath
import fr.acinq.bitcoin.XonlyPublicKey
import fr.acinq.bitcoin.psbt.Psbt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Public test vectors ported from BlueWallet (github.com/BlueWallet/BlueWallet, tests/ at 71c72092).
 * Each block names its BlueWallet source; expected values are BlueWallet's own assertions.
 */
class BlueWalletVectorsTest {
    /** Receive and change addresses 0 until [count] of a watch-only secret, for its one script type. */
    private fun addresses(
        secret: String,
        type: AddressScriptType,
        count: Int = 2,
    ): Pair<List<String>, List<String>> {
        val wallet = deriveWatchWallet(secret, WatchGaps(count, count))

        fun chain(change: Boolean) = (0 until count).map { wallet.hd(type, it, change).address }
        return chain(false) to chain(true)
    }

    private fun assertFirst(
        secret: String,
        type: AddressScriptType,
        receive: List<String>,
        change: List<String> = emptyList(),
    ) {
        val (r, c) = addresses(secret, type, maxOf(receive.size, change.size))
        assertEquals(receive, r.take(receive.size), secret)
        assertEquals(change, c.take(change.size), secret)
    }

    private fun rejects(
        message: String,
        input: String,
    ) {
        val error = assertFailsWith<IllegalArgumentException> { parseWalletSecret(input) }
        assertEquals(message, error.message, input)
    }

    // --- Account keys: receive/change addresses ---

    @Test
    fun bip44_xpubs() {
        // hd-segwit-p2sh-wallet.test.ts "can generate addressess based on xpub"; watch-only-wallet.test.js
        assertFirst(
            "xpub6CQdfC3v9gU86eaSn7AhUFcBVxiGhdtYxdC5Cw2vLmFkfth2KXCMmYcPpvZviA89X6DXDs4PJDk5QVL2G2xaVjv7SM4roWHr1gR4xB3Z7Ps",
            AddressScriptType.P2PKH,
            listOf("12eQ9m4sgAwTSQoNXkRABKhCXCsjm2jdVG", "1QDCFcpnrZ4yrAQxmbvSgeUC9iZZ8ehcR5"),
            listOf("1KZjqYHm7a1DjhjcdcjfQvYfF2h6PqatjX", "13CW9WWBsWpDUvLtbFqYziWBWTYUoQb4nU"),
        )
        // hd-legacy-wallet.test.js "works" (HD_MNEMONIC_OLD account xpub)
        assertFirst(
            "xpub6ByZUAv558PPheJgcPYHpxPLwz8M7TtueYMAik84NADeQcvbzS8W3WxxJ3C9NzfYkMoChiMAumWbeEvMWhTVpH75NqGv5c9wF3wKDbfQShb",
            AddressScriptType.P2PKH,
            listOf("186FBQmCV5W1xY7ywaWtTZPAQNciVN8Por"),
            listOf("1J9zoJz5LsAJ361SQHYnLTWg46Tc2AXUCj"),
        )
        // hd-legacy-wallet.test.js "can use mnemonic with passphrase"
        assertFirst(
            "xpub6D85QDBajeLe2JXJrZeGyQCaw47PWKi3J9DPuHakjTkVBWCxVQQkmMVMSSfnw39tj9FntbozpRtb1AJ8ubjeVSBhyK4M5mzdvsXZzKPwodT",
            AddressScriptType.P2PKH,
            listOf("1F88g2naBMhDB7pYFttPWGQgryba3hPevM"),
            listOf("1H4QD1rg2zQJ4UjuAVJr5eW1fEM8WMqyxh"),
        )
        // hd-legacy-wallet.test.js "can create with custom derivation path" (m/44'/0'/1')
        assertFirst(
            "xpub6BosfCnifzxcJJ1wYuntGJfF2zPJkDeG9ELNHcKNjezuea4tumswN9sH1psMdSVqCMoJC21Bv8usSeqSP4Sp1tLzW7aY59fGn9GCYzx5UTo",
            AddressScriptType.P2PKH,
            listOf("15qucUWKf95Fo58FdCBhUTSAtsm22HHE2Q"),
            listOf("1DgjtFUiXvqxGic9A9fiDPrHNyKC4cGtTH"),
        )
    }

    @Test
    fun bip49_ypubs() {
        // hd-segwit-p2sh-wallet.test.ts "Segwit HD (BIP49) can generate addressess only via ypub"
        assertFirst(
            "ypub6WhHmKBmHNjcrUVNCa3sXduH9yxutMipDcwiKW31vWjcMbfhQHjXdyx4rqXbEtVgzdbhFJ5mZJWmfWwnP4Vjzx97admTUYKQt6b9D7jjSCp",
            AddressScriptType.P2SH_P2WPKH,
            listOf("3GcKN7q7gZuZ8eHygAhHrvPa5zZbG5Q1rK", "35p5LwCAE7mH2css7onyQ1VuS1jgWtQ4U3"),
            listOf("32yn5CdevZQLk3ckuZuA8fEKBco8mEkLei"),
        )
        // hd-segwit-p2sh-wallet.test.ts "can use mnemonic with passphrase"
        assertFirst(
            "ypub6Xa3WiyXHriYt1fxZGWS8B1iduw92yxHCMWKSwJkW6w92FUCTJxwWQQHLXjRHBSsMLY6SvRu8ErqFEC3JmrkTHEm7KSUfbzhUhj7Yopo2JR",
            AddressScriptType.P2SH_P2WPKH,
            listOf("3BtnNenqpGTXwwjb5a1KgzzoKV4TjCuySm"),
            listOf("3EJctafkUBvcSHYhunQRa2iYUHjrMGLXBV"),
        )
        // hd-segwit-p2sh-wallet.test.ts "can create with custom derivation path" (m/49'/0'/1')
        assertFirst(
            "ypub6Ww3ibxVfGzLtJR4F9SRBicspAfvmvw54yern9Q6qZWFC9T6FYA34K57La5Sgs8pXuyvpDfEHX5KNZRiZRukUWaVPyL4NxA69sEAqdoV8ve",
            AddressScriptType.P2SH_P2WPKH,
            listOf("35eszW2wmZ4hn7hfG5LGqxw5xCPjZcEJPM"),
            listOf("35gZZo6xPJEPgcz1cj1mTQHRMiPP97NGRY"),
        )
        // watch-only-wallet.test.js PSBT for a ypub: utxo and change addresses
        assertFirst(
            "ypub6XRzrn3HB1tjhhvrHbk1vnXCecZEdXohGzCk3GXwwbDoJ3VBzZ34jNGWbC6WrS7idXrYjjXEzcPDX5VqnHEnuNf5VAXgLfSaytMkJ2rwVqy",
            AddressScriptType.P2SH_P2WPKH,
            listOf("37EX3KrmopubWPLB8Y8NR36wXs7icu2kjQ"),
            listOf("333R1N8zst8bK7xMtqBndmwcd288qxEBmr"),
        )
    }

    @Test
    fun ypub_change_at_high_indexes() {
        // watch-only-wallet.test.js "can create PSBT base64 without signature for HW wallet" (change 45 and 46)
        val ypub = "ypub6Y9u3QCRC1HkZv3stNxcQVwmw7vC7KX5Ldz38En5P88RQbesP2oy16hNyQocVCfYRQPxdHcd3pmu9AFhLv7NdChWmw5iNLryZ2U6EEHdnfo"
        val wallet = deriveWatchWallet(ypub, WatchGaps(1, 47))
        assertEquals("3GCvDBAktgQQtsbN6x5DYiQCMmgZ9Yk8BK", wallet.hd(AddressScriptType.P2SH_P2WPKH, 45, change = true).address)
        assertEquals("3DrZBgntD8kBBbuKLJtPVAeGT75BMC7NxU", wallet.hd(AddressScriptType.P2SH_P2WPKH, 46, change = true).address)
        assertEquals(
            "03e060c9b5bb85476caa53e3b8cd3d40c9dc2c36a8a5e8ed87e48bfc9bbe1760ad",
            parseWatchKey(ypub).publicKeyAt(change = true, index = 46).toHex(),
        )
    }

    @Test
    fun bip84_zpubs() {
        // hd-segwit-bech32-wallet.test.js "can generate addresses only via zpub"
        assertFirst(
            "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs",
            AddressScriptType.P2WPKH,
            listOf("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", "bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g"),
            listOf("bc1q8c6fshw2dlwun7ekn9qwf37cu2rn755upcp6el"),
        )
        // hd-segwit-bech32-wallet.test.js "can use mnemonic with passphrase"
        assertFirst(
            "zpub6qNvUL1qNQwaReccveprgd4urE2EUvShpcFe7WB9tzf9L4NJNcWhPzJSk4fzNXqBNZdRr6135hBKaqqp5RVvyxZ6eMbZXL6u5iK4zrfkCaQ",
            AddressScriptType.P2WPKH,
            listOf("bc1qgaj3satczjem43pz46ct6r3758twhnny4y720z"),
            listOf("bc1qthe7wh5eplzxczslvthyrer36ph3kxpnfnxgjg"),
        )
        // hd-segwit-bech32-wallet.test.js "can create with custom derivation path" (m/84'/0'/1')
        assertFirst(
            "zpub6rFR7y4Q2AijF6Gk1bofHLs1d66hKFamhXWdWBup1Em25wfabZqkDqvaieV63fDQFaYmaatCG7jVNUpUiM2hAMo6SAVHcrUpSnHDpNzucB7",
            AddressScriptType.P2WPKH,
            listOf("bc1qku0qh0mc00y8tk0n65x2tqw4trlspak0fnjmfz"),
            listOf("bc1qt0x83f5vmnapgl2gjj9r3d67rcghvjaqrvgpck"),
        )
        // watch-only-wallet.test.js Sparrow UR import (fingerprint 3c7f1a52)
        assertFirst(
            "zpub6rkkMBH6dE8bUPM9MC3WTMYQ3pDYR1kHnNDrqEGY3FotR4EUifR1S4xd7ynwczREFCbfWyk5S4mhzPL8YuGsCSgey1AwH7fk4w9AULpyDYL",
            AddressScriptType.P2WPKH,
            listOf("bc1qr0y5c96xtfeulnzxnjl086f2njcmf8qmhenvpp"),
        )
        // watch-only-wallet.test.js PSBT for a zpub: utxo and change addresses
        assertFirst(
            "zpub6rjLjQVqVnj7crz9E4QWj4WgczmEseJq22u2B6k2HZr6NE2PQx3ZYg8BnbjN9kCfHymSeMd2EpwpM5iiz5Nrb3TzvddxW2RMcE3VXdVaXHk",
            AddressScriptType.P2WPKH,
            listOf("bc1qhu8jqyzfazgatpctqn44xr7pdd3mdx6qy2r6xa"),
            listOf("bc1quuafy8htjjj263cvpj7md84magzmc8svmh8lrm"),
        )
        // watch-only-wallet.test.js coldcard/electrum skeleton wallets: utxo 0/0 and change 1/0
        assertFirst(
            ELECTRUM_SKELETON_ZPUB,
            AddressScriptType.P2WPKH,
            listOf("bc1qzqjwye4musmz56cg44ttnchj49zueh9yr0qsxt"),
            listOf("bc1qtutssamysdkgd87df0afjct0mztx56qpze7wqe"),
        )
    }

    @Test
    fun ypub_and_xpub_of_the_same_account_are_one_key() {
        // wallet-descriptor.test.ts "normalizeToXpub"
        val ypub = parseWatchKey("ypub6WhHmKBmHNjcrUVNCa3sXduH9yxutMipDcwiKW31vWjcMbfhQHjXdyx4rqXbEtVgzdbhFJ5mZJWmfWwnP4Vjzx97admTUYKQt6b9D7jjSCp")
        val xpub = parseWatchKey("xpub6Bs2TeWr8hC91BJFNDGFKYomz1pTwjjKJWRVY798YWMjJVrU9dZy1vHvqda1EyqmazUtVpVD6eADnELDfN5jCiTWiJ52tdVvcNXVpa2Z6fx")
        assertEquals(xpub.account, ypub.account)
    }

    @Test
    fun hardware_wallet_exports_first_address() {
        // fixtures/coldcardQ.json (xfp b68af6e4) bip44/bip49/bip84 "first", from its xpub, _pub ypub and _pub zpub
        assertFirst(COLDCARD_Q_BIP44_XPUB, AddressScriptType.P2PKH, listOf("1DFavpj8xohigXrXwk1PpdF3rwTeHk494L"))
        assertFirst(
            "ypub6XPx9oD6rLKvKS94NmFTeBPN38TUtkqSL2vk7hpxvdfy6GqnV6gsYri7JtYs47VBpfUsYn52BWpkgxkHgbmeZKszLYuzgrdNipsFproGpSu",
            AddressScriptType.P2SH_P2WPKH,
            listOf("3D7gCoiv5tp13pKbxh5Rkte93AViQpZcS7"),
        )
        assertFirst(
            "zpub6s2EvLxwvDpaHNVP5vfordTyi8cH1fR8usmEjz7RsSQjfTTGU2qA5VEcEyYYBxpZAyBarJoTraB4VRJKVz97Au9jRNYfLAeeHC5UnRZbz8Y",
            AddressScriptType.P2WPKH,
            listOf("bc1q8tart7tv6da6ylqauf2fde23vwgpsakxznkf7c"),
        )
        // fixtures/coldcardmk4/sparrow-export.json (xfp 086ee178) bip49/bip84 _pub keys
        assertFirst(
            "ypub6XhEgVV3GhJLpa3J7HfebGDC8B46Uhb9evBEceWPPjjQSsRhSdgDXm5AQN8HdNDZVZqWPh8K4ah5u2GWfYXeoR3E3chhJJbnjoDjgnzrviL",
            AddressScriptType.P2SH_P2WPKH,
            listOf("3DCeGZNxxhQDh5kAMi1EQQcpbBpo6pocHU"),
        )
        assertFirst(
            "zpub6rW3584UKGy7FobPcwDTCBgY64LPWQNSbMbqEBANM9gr6DGaFJJGF2qP4RpVixCu5fC9L7r3bZGqPHhiEGd1aZvuX7ipaLCvVUm6xBzBhzQ",
            AddressScriptType.P2WPKH,
            listOf("bc1q5y4r767v5fzx74ez4nw36hjqrhr4ayeyut5px6"),
        )
    }

    // --- Descriptors ---

    @Test
    fun abandon_descriptors_match_the_mnemonic_wallet() {
        // wallet-descriptor.test.ts: watch-only from each descriptor equals the HD wallet, receive and change 0..2
        val mnemonic = deriveWatchWallet(ABANDON, WatchGaps(3, 3))
        val descriptors =
            mapOf(
                "pkh([73c5da0a/44'/0'/0']$XPUB_44)" to AddressScriptType.P2PKH,
                "sh(wpkh([73c5da0a/49'/0'/0']$XPUB_49))" to AddressScriptType.P2SH_P2WPKH,
                "wpkh([73c5da0a/84'/0'/0']$XPUB_84)" to AddressScriptType.P2WPKH,
                "tr([73c5da0a/86'/0'/0']$XPUB_86)" to AddressScriptType.P2TR,
            )
        for ((descriptor, type) in descriptors) {
            val expected = (0..2).map { mnemonic.hd(type, it).address } to (0..2).map { mnemonic.hd(type, it, change = true).address }
            assertEquals(expected, addresses(descriptor, type, 3), descriptor)
        }
        // wallet-descriptor.test.ts first receive addresses per script
        assertEquals("1LqBGSKuX5yYUonjxT5qGfpUsXKYYWeabA", mnemonic.hd(AddressScriptType.P2PKH, 0).address)
        assertEquals("37VucYSaXLCAsxYyAPfbSi9eh4iEcbShgf", mnemonic.hd(AddressScriptType.P2SH_P2WPKH, 0).address)
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", mnemonic.hd(AddressScriptType.P2WPKH, 0).address)
        assertEquals("bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr", mnemonic.hd(AddressScriptType.P2TR, 0).address)
    }

    @Test
    fun coldcard_mk4_descriptors() {
        // fixtures/coldcardmk4/descriptor.txt; integration/import.test.ts "can import coldcard mk4 descriptor.txt"
        assertFirst(COLDCARD_MK4_WPKH, AddressScriptType.P2WPKH, listOf("bc1q5y4r767v5fzx74ez4nw36hjqrhr4ayeyut5px6"))
        assertEquals(KeyOrigin(0x086ee178, KeyPath("m/84'/0'/0'")), parseWatchKey(COLDCARD_MK4_WPKH).origin)
        // fixtures/coldcardmk4/sparrow-export.json bip44 and bip49 "desc" and "first"
        assertFirst(
            "pkh([086ee178/44h/0h/0h]xpub6DNdnDgd2ULEUeRfpQhqJ8S9aqxZCWi9e4YijwPX1DsVfzPRPn2fk9jy72pn7rS2SAMWgfQK1JU3WsusseeConHH2LVjPEZkhxRBhFTdX69/<0;1>/*)#estefkna",
            AddressScriptType.P2PKH,
            listOf("1CuhYHhWe4awuUtgX5qREo5xYaoReXPLnZ"),
        )
        assertFirst(
            "sh(wpkh([086ee178/49h/0h/0h]xpub6CryNpp881kryGrBGvt2PB7gxCueY5bejof1qFcW1jMXPmcUByWeuhR2PAAhdTZe5viheDXkbvLY1jewwr7e1BMdBH1GiPnJU5A6JGHyV95/<0;1>/*))#76em9dr6",
            AddressScriptType.P2SH_P2WPKH,
            listOf("3DCeGZNxxhQDh5kAMi1EQQcpbBpo6pocHU"),
        )
    }

    @Test
    fun coldcard_q_descriptors_need_their_wildcard() {
        // fixtures/coldcardQ.json stores "/<0;1>/)" (no "*"); its checksums were computed over "/<0;1>/*)".
        rejects("descriptor checksum does not match", "pkh([b68af6e4/44h/0h/0h]$COLDCARD_Q_BIP44_XPUB/<0;1>/)#ewl4cmty")
        assertFirst(
            "pkh([b68af6e4/44h/0h/0h]$COLDCARD_Q_BIP44_XPUB/<0;1>/*)#ewl4cmty",
            AddressScriptType.P2PKH,
            listOf("1DFavpj8xohigXrXwk1PpdF3rwTeHk494L"),
        )
    }

    @Test
    fun xpub_with_bip84_origin_inside_wpkh() {
        // watch-only-wallet.test.js "can import wallet descriptor for BIP84, but with xpub instead of zpub" (h markers)
        val descriptor =
            "wpkh([dafedf1c/84h/0h/0h]xpub6DFMZMLizqqnyyHoWTG7qzmCR1irpiDEGT4JQX7ubeoFtV838ABKPfgAPQbM1TEekEyCuJF1BrmnA7JPrnzqi2VbycD3tVE3v5xsDQqYA3A)"
        assertFirst(descriptor, AddressScriptType.P2WPKH, listOf("bc1q68y6r45k4kvxe42xl37dgjueg2suqwnh4ze0sr"))
        assertEquals("m/84'/0'/0'", parseWatchKey(descriptor).accountPath)
    }

    @Test
    fun descriptor_checksum_of_an_addr_descriptor() {
        // storage.test.js
        assertEquals("pl83f4nc", descriptorChecksum("addr(bc1qaxxc4gwx6rd6rymq08qwpxhesd4jqu93lvjsyt)"))
    }

    // --- Taproot ---

    @Test
    fun taproot_xpub_receive_change_and_internal_keys() {
        // hd-taproot-wallet.test.ts "can create" / "can generate addresses only via zpub"
        val descriptor = "tr([73c5da0a/86'/0'/0']$XPUB_86)"
        assertFirst(
            descriptor,
            AddressScriptType.P2TR,
            listOf(
                "bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr",
                "bc1p4qhjn9zdvkux4e44uhx8tc55attvtyu358kutcqkudyccelu0was9fqzwh",
            ),
            listOf("bc1p3qkhfews2uk44qtvauqyr2ttdsw7svhkl9nkm9s9c3x4ax5h60wqwruhk7"),
        )
        val key = parseWatchKey(descriptor)
        assertEquals("cc8a4bc64d897bddc5fbc2f670f7a8ba0b386779106cf1223c6fc5d7cd6fc115", XonlyPublicKey(key.publicKeyAt(false, 0)).value.toHex())
        assertEquals("399f1b2f4393f29a18c937859c5dd8a77350103157eb880f02e8c08214277cef", XonlyPublicKey(key.publicKeyAt(true, 0)).value.toHex())
        assertEquals("m/86'/0'/0'/1/1", key.bip32Path(change = true, index = 1).asString('\''))
    }

    @Test
    fun taproot_descriptor_forms() {
        // watch-only-wallet.test.js "can import BIP86 (taproot) wallet descriptor": multipath and bare forms
        val receive0 = "bc1p4k8tkyf4va4nczy30lutfqardahsywgfftdnrmqwupnucfmx9yuqjp3dxh"
        for (suffix in listOf("/<0;1>/*", "")) {
            val descriptor = "tr([97311f91/86'/0'/0']$PASSPORT_XPUB$suffix)"
            assertFirst(descriptor, AddressScriptType.P2TR, listOf(receive0))
            assertEquals(0x97311f91, parseWatchKey(descriptor).bip32Fingerprint())
        }
        // watch-only-wallet.test.js: a zpub inside tr() still pays to taproot
        val zpubInTr =
            "tr([b68af6e4/86'/0'/0']zpub6rxQT4vrGrdLmFicJZnLxx1odj1C8xNtHW5pW84hMSXdtoFnCbqBFJm3bF5PrwYL5ScxFhdzRuv3pb9beyoraQLMuQWkV9faGuxstBPgLw4)"
        assertFirst(zpubInTr, AddressScriptType.P2TR, listOf("bc1pyren45uwytsghuxelahgyjflrx9dhq9zhavangrcmw2avfre6spqtwxgm4"))
        assertEquals(0xb68af6e4, parseWatchKey(zpubInTr).bip32Fingerprint())
    }

    @Test
    fun origin_path_that_is_not_the_standard_account_path() {
        // watch-only-wallet.test.js "can import ... descriptor with custom path": [97311f91/0/0]; the wrapper picks the script
        val cases =
            mapOf(
                "tr([97311f91/0/0]$PASSPORT_XPUB/<0;1>/*)" to (AddressScriptType.P2TR to "bc1p4k8tkyf4va4nczy30lutfqardahsywgfftdnrmqwupnucfmx9yuqjp3dxh"),
                "wpkh([97311f91/0/0]$PASSPORT_XPUB)" to (AddressScriptType.P2WPKH to "bc1qhwpcrjj8cqvu3rwgny4rgukwm06lwlvdulmv6a"),
                "pkh([97311f91/0/0]$PASSPORT_XPUB)" to (AddressScriptType.P2PKH to "1J6Uu92MtuzpGqecjDkmVsWHBpWfutjW7K"),
                "sh(wpkh([97311f91/0/0]$PASSPORT_XPUB))" to (AddressScriptType.P2SH_P2WPKH to "3HAXxTBo88zdnUUpewWfNuWi99PFCSTMNi"),
            )
        for ((descriptor, expected) in cases) {
            val (type, receive0) = expected
            assertFirst(descriptor, type, listOf(receive0))
            val key = parseWatchKey(descriptor)
            assertEquals("m/0/0", key.accountPath)
            assertEquals(0x97311f91, key.bip32Fingerprint())
            assertEquals("m/0/0/1/3", key.bip32Path(change = true, index = 3).asString('\''))
        }
    }

    // --- PSBT origin ---

    @Test
    fun psbt_uses_the_descriptor_master_fingerprint_and_full_path() {
        // watch-only-wallet.test.js "can import Electrum compatible backup wallet, and create a tx with master fingerprint"
        // (fixtures/skeleton-electrum.txt: root fingerprint 168dd603, derivation m/84'/0'/1')
        val secret = "wpkh([168dd603/84'/0'/1']$ELECTRUM_SKELETON_ZPUB)"
        val wallet = deriveWatchWallet(secret, WatchGaps(1, 1))
        val coin = wallet.hd(AddressScriptType.P2WPKH, 0)
        val change = wallet.hd(AddressScriptType.P2WPKH, 0, change = true)
        val funding = testFundingTx(coin.scriptPubKey, 66_600L)
        val result =
            buildSend(
                BuildSendTxParams(
                    secret = secret,
                    wallet = wallet,
                    utxos = listOf(SendInputUtxo(funding.txid, 0, 66_600L, coin.scriptPubKey, funding.bytes)),
                    toAddress = "bc1qdamevhw3zwm0ajsmyh39x8ygf0jr0syadmzepn",
                    amountSats = SendAmount.Exact(5_000L),
                    feeRateSatPerVb = 1.0,
                    changeAddress = change.address,
                ),
            ) as PsbtSendResult
        val psbt = Psbt.read(hexToBytes(result.psbtHex)).right ?: error("failed to parse psbt")
        val input =
            psbt.inputs[0]
                .derivationPaths.values
                .single()
        assertEquals(0x168dd603, input.masterKeyFingerprint)
        assertEquals("m/84'/0'/1'/0/0", input.keyPath.asString('\''))
        val changeIndex =
            psbt.global.tx.txOut
                .indexOfFirst { it.publicKeyScript.toByteArray().contentEquals(change.scriptPubKey) }
        val output =
            psbt.outputs[changeIndex]
                .derivationPaths.values
                .single()
        assertEquals(0x168dd603, output.masterKeyFingerprint)
        assertEquals("m/84'/0'/1'/1/0", output.keyPath.asString('\''))
        assertEquals(KeyPath(listOf(hardened(84), hardened(0), hardened(1), 1, 0)), output.keyPath)
    }

    // --- Rejections ---

    @Test
    fun rejects_truncated_keys_and_bad_origins() {
        // watch-only-wallet.test.js "can validate xpub"
        rejects("invalid xpub", "xpub6CQdfC3v9gU86eaSn7AhUFcBVxiGhdtYxdC5Cw2vLmFkfth2KXCMmYcPpvZviA89X6D")
        rejects("invalid ypub", "ypub6XRzrn3HB1tjhhvrHbk1vnXCecZEdXohGzCk3GXwwbDoJ3VBzZ34jNGWbC6WrS7idXr")
        // watch-only-wallet.test.js: fingerprint "73c5da0g" is not hex
        rejects("invalid key origin", "wpkh([73c5da0g/84'/0'/0']$XPUB_84)")
        assertFailsWith<IllegalArgumentException> { parseWalletSecret("[73c5da0g/84'/0'/0']$XPUB_84") }
    }

    @Test
    fun rejects_testnet_keys() {
        // wallet-descriptor.test.ts normalizeToXpub tpub/vpub (testnet keys; Blueberry is mainnet only)
        val tpub = "tpubDC5FSnBiZDMmhiuCmWAYsLwgLYrrT9rAqvTySfuCCrgsWz8wxMXUS9Tb9iVMvcRbvFcAHGkMD5Kx8koh4GquNGNTfohfk7pgjhaPCdXpoba"
        val vpub = "vpub5Y6cjg78GGuNLsaPhmYsiw4gYX3HoQiRBiSwDaBXKUafCt9bNwWQiitDk5VZ5BVxYnQdwoTyXSs2JHRPAgjAvtbBrf8ZhDYe2jWAqvZVnsc"
        rejects("only mainnet account xpub, ypub or zpub is supported", tpub)
        rejects("only mainnet account xpub, ypub or zpub is supported", vpub)
        rejects("only mainnet descriptor keys are supported", "wpkh($tpub)")
        rejects("only mainnet descriptor keys are supported", "wpkh($vpub)")
    }

    @Test
    fun rejects_breadwallet_root_level_key() {
        // wallet-descriptor.test.ts "describes Breadwallet as p2pkh at m/0h": depth-1 key, not an account key
        rejects(
            "descriptor key must be account-level (depth 3)",
            "pkh([73c5da0a/0']xpub68jrRzQopSUQm76hJ6TNtiJMJfhj38u1X12xCzExrw388hcN443UVnYpswdUkV7vPJ3KayiCdp3Q5E23s4wvkucohVTh7eSstJdBFyn2DMx)",
        )
    }

    // --- Single-address watch ---

    @Test
    fun address_validation() {
        // watch-only-wallet.test.js "can validate address"; isValidBech32Address.test.ts (mainnet entries)
        val valid =
            listOf(
                "bc1quhnve8q4tk3unhmjts7ymxv8cd6w9xv8wy29uv",
                "BC1QUHNVE8Q4TK3UNHMJTS7YMXV8CD6W9XV8WY29UV",
                "12eQ9m4sgAwTSQoNXkRABKhCXCsjm2jdVG",
                "3BDsBDxDimYgNZzsqszNZobqQq3yeUoJf2",
                "bc1qatswv5uv7qetzz4n8u9u2x2ckmaxvc8qng5s7r",
                "bc1ph76f32dqjkvd523g02ucylqstljj5pysqe3lmyuepnuyz5d7lw9sl0pp4m",
                "16X9EwoL5fgUr2ordTy8bs7wT4Ff3QGQPW",
                "3HFvmZJhc7KbqVXXQXaa34StUPk4gxcQyR",
            )
        for (address in valid) {
            assertTrue(isAddressValid(address), address)
            assertEquals(WalletSecretKind.ADDRESS, parseWalletSecret(address).kind, address)
        }
        val invalid =
            listOf(
                "moKVV6XEhfrBCE3QCYq6ppT7AaMF8KsZ1B",
                "tb1ql4jps5nxnyz7qxgle9dp3q0mww2jk4ckfua6lr",
                "tb1p4tp4l6glyr2gs94neqcpr5gha7344nfyznfkc8szkreflscsdkgqsdent4",
                "bc1zw508d6qejxtdg4y5r3zarvaryvg6kdaj",
                "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kyd39",
                "BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KYGT080",
                "bcrt1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
                "",
            )
        for (address in invalid) assertFalse(isAddressValid(address), address)
    }

    private companion object {
        // wallet-descriptor.test.ts: account xpubs of the "abandon ... about" mnemonic (master fingerprint 73c5da0a)
        const val XPUB_44 =
            "xpub6BosfCnifzxcFwrSzQiqu2DBVTshkCXacvNsWGYJVVhhawA7d4R5WSWGFNbi8Aw6ZRc1brxMyWMzG3DSSSSoekkudhUd9yLb6qx39T9nMdj"
        const val XPUB_49 =
            "xpub6C6nQwHaWbSrzs5tZ1q7m5R9cPK9eYpNMFesiXsYrgc1P8bvLLAet9JfHjYXKjToD8cBRswJXXbbFpXgwsswVPAZzKMa1jUp2kVkGVUaJa7"
        const val XPUB_84 =
            "xpub6CatWdiZiodmUeTDp8LT5or8nmbKNcuyvz7WyksVFkKB4RHwCD3XyuvPEbvqAQY3rAPshWcMLoP2fMFMKHPJ4ZeZXYVUhLv1VMrjPC7PW6V"
        const val XPUB_86 =
            "xpub6BgBgsespWvERF3LHQu6CnqdvfEvtMcQjYrcRzx53QJjSxarj2afYWcLteoGVky7D3UKDP9QyrLprQ3VCECoY49yfdDEHGCtMMj92pReUsQ"

        /** fixtures/skeleton-electrum.txt and skeleton-coldcard.txt keystore key. */
        const val ELECTRUM_SKELETON_ZPUB =
            "zpub6rFDtF1nuXZ9PUL4XzKURh3vJBW6Kj6TUrYL4qPtFNtDXtcTVfiqjQDyrZNwjwzt5HS14qdqo3Co2282Lv3Re6Y5wFZxAVuMEpeygnnDwfx"

        /** watch-only-wallet.test.js taproot key with origin 97311f91. */
        const val PASSPORT_XPUB =
            "xpub6C85eQDGy5NKEqCPnrnf4QcvxQCzRiTZFTa6YfuDU1hSQGWQHf6QBHogKXaS8hUhtvk6ND4btTdiWic26UKrk1pWrU4CQGrQoGxd6DP33Sw"

        /** fixtures/coldcardQ.json bip44 xpub. */
        const val COLDCARD_Q_BIP44_XPUB =
            "xpub6CMpeBXdEKC3kxA96LeyvJgxQfPbxXuJu91wVcLjYKMcSf1dMr9HEkNUydPTxM1SkmutnYjFikr27JUtUSqP9gq1299SP7eKrKFr9YjsrPE"

        /** fixtures/coldcardmk4/descriptor.txt. */
        const val COLDCARD_MK4_WPKH =
            "wpkh([086ee178/84h/0h/0h]xpub6CqWTnie1ut9ZDD9xDeCn1VXk83VdAPSm8ZPfPNbb8w5z1e7jyy8zuX721uKj8u4GNxXqAevgEZjciUansnyz6ZhnSKyQWZwx2dpAxuCuDe/<0;1>/*)#mthwej8w"
    }
}
