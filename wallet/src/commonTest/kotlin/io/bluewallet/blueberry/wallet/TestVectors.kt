package io.bluewallet.blueberry.wallet

import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxHash
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut

const val ABANDON =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
const val HONEY_BIP49 =
    "honey risk juice trip orient galaxy win situate shoot anchor bounce remind horse traffic exotic since escape mimic ramp skin judge owner topple erode"
const val BIP44_ABANDON_EXT_0 = "1LqBGSKuX5yYUonjxT5qGfpUsXKYYWeabA"
const val BIP44_ACCOUNT1_EXT_0 = "15qucUWKf95Fo58FdCBhUTSAtsm22HHE2Q"
const val BIP44_ACCOUNT1_INT_0 = "1DgjtFUiXvqxGic9A9fiDPrHNyKC4cGtTH"
const val BIP49_HONEY_EXT_0 = "3GcKN7q7gZuZ8eHygAhHrvPa5zZbG5Q1rK"
const val BIP49_HONEY_EXT_1 = "35p5LwCAE7mH2css7onyQ1VuS1jgWtQ4U3"
const val BIP49_HONEY_INT_0 = "32yn5CdevZQLk3ckuZuA8fEKBco8mEkLei"
const val BIP86_ABANDON_EXT_0 = "bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr"
const val BIP86_ABANDON_EXT_1 = "bc1p4qhjn9zdvkux4e44uhx8tc55attvtyu358kutcqkudyccelu0was9fqzwh"
const val BIP86_ABANDON_INT_0 = "bc1p3qkhfews2uk44qtvauqyr2ttdsw7svhkl9nkm9s9c3x4ax5h60wqwruhk7"

const val BLUE_ZPUB =
    "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"
const val BLUE_EXTERNAL_0 = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
const val BLUE_EXTERNAL_1 = "bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g"
const val BLUE_INTERNAL_0 = "bc1q8c6fshw2dlwun7ekn9qwf37cu2rn755upcp6el"
const val BLUE_EXTERNAL_0_SCRIPT = "0014c0cebcd6c3d3ca8c75dc5ec62ebe55330ef910e2"
const val SEEDSIGNER_ZPUB =
    "zpub6rutAggZJCvkgZg3BAqNGAxCkx1khxCE6g6jyJugMfZ1zgkVdUWSdnzSRpWX1GYVZXCpQFS87BUsvgXXJBpsJVroiHbu4Js2TY69zbWcTNb"
const val SEEDSIGNER_EXTERNAL_0 = "bc1q68y6r45k4kvxe42xl37dgjueg2suqwnh4ze0sr"
const val WIF_BECH32 = "L4vn2KxgMLrEVpxjfLwxfjnPPQMnx42DCjZJ2H7nN4mdHDyEUWXd"
const val ADDR_BECH32 = "bc1q3rl0mkyk0zrtxfmqn9wpcd3gnaz00yv9yp0hxe"
const val WIF_LEGACY = "L4ccWrPMmFDZw4kzAKFqJNxgHANjdy6b7YKNXMwB4xac4FLF3Tov"
const val ADDR_LEGACY = "14YZ6iymQtBVQJk6gKnLCk49UScJK7SH4M"
const val WIF_P2SH = "Ky1vhqYGCiCbPd8nmbUeGfwLdXB1h5aGwxHwpXrzYRfY5cTZPDo4"
const val ADDR_P2SH = "3CKN8HTCews4rYJYsyub5hjAVm5g5VFdQJ"
const val WIF_TAPROOT = "L4PKRVk1Peaar5WuH5LiKfkTygWtFfGrFeH2g2t3YVVqiwpJjMoF"
const val ADDR_TAPROOT = "bc1pm6lqlel3qxefsx0v39nshtghasvvp6ghn3e5hd5q280j5m9h7csqrkzssu"
const val BIP341_TAPROOT = "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0"
const val DEST_LEGACY = "1GX36PGBUrF8XahZEGQqHqnJGW2vCZteoB"
const val GENESIS_P2PKH = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"

data class TestFundingTx(
    val txid: String,
    val bytes: ByteArray,
)

/** One-output v2 funding transaction; `salt` makes each fixture a distinct txid. */
fun testFundingTx(
    scriptPubKey: ByteArray,
    valueSats: Long,
    salt: Int = 1,
): TestFundingTx {
    val prevHash = ByteArray(32)
    prevHash[0] = salt.toByte()
    val tx =
        Transaction(
            2L,
            listOf(TxIn(OutPoint(TxHash(prevHash), 0L), 0xffffffffL)),
            listOf(TxOut(Satoshi(valueSats), scriptPubKey)),
            0L,
        )
    return TestFundingTx(tx.txid.toString(), Transaction.write(tx))
}
