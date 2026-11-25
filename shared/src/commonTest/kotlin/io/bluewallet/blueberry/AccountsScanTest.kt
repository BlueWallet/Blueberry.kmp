package io.bluewallet.blueberry

import io.bluewallet.blueberry.wallet.UR_NOT_ACCOUNT
import io.bluewallet.blueberry.wallet.UR_NOT_UR
import io.bluewallet.blueberry.wallet.encodeCryptoPsbtUrFragments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AccountsScanTest {
    @Test
    fun animated_account_frames_progress_and_end_in_accounts() {
        val scan = AccountsScan()
        var last = 0
        for (part in ABANDON_ACCOUNT_PARTS.dropLast(1)) {
            val percent = assertIs<AccountsScanState.Scanning>(scan.accept(part)).percent ?: error("no progress")
            assertTrue(percent >= last)
            last = percent
        }
        val done = assertIs<AccountsScanState.Accounts>(scan.accept(ABANDON_ACCOUNT_PARTS.last()))
        assertEquals(listOf("wpkh(", "sh(wpkh(", "pkh("), done.accounts.map { it.descriptor.substringBefore('[') })
    }

    @Test
    fun a_qr_that_is_not_a_ur_is_handed_over_as_text() {
        assertEquals(AccountsScanState.Text(ZPUB), AccountsScan().accept(ZPUB))
    }

    @Test
    fun text_after_an_animation_started_is_ignored_and_keeps_progress() {
        val scan = AccountsScan()
        val started = assertIs<AccountsScanState.Scanning>(scan.accept(ABANDON_ACCOUNT_PARTS[0]))
        assertEquals(AccountsScanState.Scanning(started.percent, UR_NOT_UR), scan.accept(ZPUB))
    }

    @Test
    fun text_after_a_failed_export_is_ignored_and_keeps_the_reason() {
        val scan = AccountsScan()
        scan.accept(ETH_HDKEY_UR)
        assertEquals(AccountsScanState.Scanning(null, NO_ACCOUNTS), scan.accept(ZPUB))
    }

    @Test
    fun a_psbt_qr_is_not_an_account_export() {
        val psbt = encodeCryptoPsbtUrFragments(byteArrayOf(0x70, 0x73, 0x62, 0x74)).single()
        assertEquals(AccountsScanState.Scanning(null, UR_NOT_ACCOUNT), AccountsScan().accept(psbt))
    }

    @Test
    fun an_export_without_bitcoin_accounts_says_so_and_keeps_scanning() {
        val scan = AccountsScan()
        assertEquals(AccountsScanState.Scanning(null, NO_ACCOUNTS), scan.accept(ETH_HDKEY_UR))
        assertEquals(AccountsScanState.Scanning(null, NO_ACCOUNTS), scan.accept(ETH_HDKEY_UR))
        assertEquals(NO_ACCOUNTS, assertIs<AccountsScanState.Scanning>(scan.accept(ABANDON_ACCOUNT_PARTS[0])).note)
        assertIs<AccountsScanState.Accounts>(ABANDON_ACCOUNT_PARTS.drop(1).map { scan.accept(it) }.last())
    }

    private companion object {
        const val ZPUB = "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"

        // BlueWallet's 3-account abandon crypto-account (watch-only-wallet.test.js line 1125), re-encoded by our UrEncoder at 120 bytes.
        val ABANDON_ACCOUNT_PARTS =
            listOf(
                "ur:crypto-account/1-4/lpadaacfadjecysopajslnhdhpoeadcyjksktnbkaolstaadmwtaaddlonaxhdclaojoknidzcpssajtptrpfrcecfkkamykjtvtcsbtbdtkcfiyvyoetneeykwfnbnyndaahdcxgegunbpyclrhuomdlnnsglmooyhscfglaxrtwsfhykadgeswmowkeosskoghmhztamtaaddytoosamld",
                "ur:crypto-account/2-4/lpaoaacfadjecysopajslnhdhpotadlncsghykaeykaeykaocyjksktnbkaxaxattaaddyoyadlraewklawkaycykbwfdnuytaadmhtaadmwtaaddlonaxhdclaownwfflldcwcxylhfmnplfmsolnnersrpkgsgrpwfhdeyjlbewpsscnhfrygomuntaahdcxjtpkvlihplbabksofgisgu",
                "ur:crypto-account/3-4/lpaxaacfadjecysopajslnhdhpbkpylreyhhzeketsjzfrmhmhecyaldvdtewnroflptnbkkkbsbamtaaddyotadlncsehykaeykaeykaocyjksktnbkaxaxattaaddyoyadlraewklawkaycyfsahzmkptaadmutaaddlonaxhdclaxktgsmebstkatzsmtlojtosmwwttlsgwevecagdut",
                "ur:crypto-account/4-4/lpaaaacfadjecysopajslnhdhpnyzedyqzgrlsylvobwrkmomubakiwkryaahdcxfsoxrfcfbkdslabycaehzsuruomhhedrlbjzvdkejlbenlcfbyjldafsfxfygmcfamtaaddyotadlncsdwykaeykaeykaocyjksktnbkaxaxattaaddyoyadlraewklawkaycybzhpsghkaepfrlsrrk",
            )

        // Keystone's first ETH key (m/44'/60'/0') from the crypto-multi-accounts vector, as a crypto-hdkey UR.
        const val ETH_HDKEY_UR =
            "ur:crypto-hdkey/pdaowkaxhdclaxsspmdacpimmkkogwkgkpfrjzkofrvybgsofzcsdmbdiymkmwgsntwsdafnwpfgkoaahdcxrsbefnzsrfpyeyhtjyiapyhhfxdrfzfwbemokktbsrgucelkhyosbgisgswfctemamtaaddyotadlncsdwykcsfnykaeykaocybggdrprfaxaxattaaddyoeadlraewklawkaxaeaycyiaskwdioasisgrihkkjkjyjljtihbkjohsiaiajlkpjtjydmjkjyhsjtiehsjpieatztvyga"
    }
}
