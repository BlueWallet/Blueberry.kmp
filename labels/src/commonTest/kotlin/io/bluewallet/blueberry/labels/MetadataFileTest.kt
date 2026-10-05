package io.bluewallet.blueberry.labels

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MetadataFileTest {
    @Test
    fun slip15_account_key_derives_the_published_filename() {
        // SLIP-0015 example: account key v5kCxSKL…, filename 08108c3a….mtdt.
        assertEquals(SLIP15_FILENAME, metadataFilename(SLIP15_ACCOUNT_KEY))
    }

    @Test
    fun slip15_encrypt_with_the_example_iv_matches_the_published_file() {
        val file = slip15ExampleFile()
        val encrypted =
            encryptMetadataFile(
                SLIP15_ACCOUNT_KEY,
                SLIP15_PLAINTEXT.encodeToByteArray(),
                file.copyOfRange(0, 12),
            )
        assertContentEquals(file, encrypted)
    }

    @Test
    fun slip15_decrypt_returns_the_example_labels() {
        val plaintext = decryptMetadataFile(SLIP15_ACCOUNT_KEY, slip15ExampleFile())
        assertEquals(SLIP15_PLAINTEXT, plaintext.decodeToString())
    }

    @Test
    fun slip15_decrypt_rejects_a_tampered_file() {
        val file = slip15ExampleFile()
        file[file.lastIndex] = (file[file.lastIndex].toInt() xor 0x01).toByte()
        assertFailsWith<IllegalArgumentException> {
            decryptMetadataFile(SLIP15_ACCOUNT_KEY, file)
        }
    }

    @Test
    fun slip15_roundtrip_uses_a_fresh_iv() {
        val plaintext = "utxo label".encodeToByteArray()
        val first = encryptMetadataFile(SLIP15_ACCOUNT_KEY, plaintext)
        val second = encryptMetadataFile(SLIP15_ACCOUNT_KEY, plaintext)
        assertEquals(false, first.contentEquals(second))
        assertContentEquals(plaintext, decryptMetadataFile(SLIP15_ACCOUNT_KEY, first))
        assertContentEquals(plaintext, decryptMetadataFile(SLIP15_ACCOUNT_KEY, second))
    }
}

private const val SLIP15_ACCOUNT_KEY = "v5kCxSKLTsnwmgPBeaRyFDWeG9zXouF34L72763zjLrS4LWy8"
private const val SLIP15_FILENAME =
    "08108c3a46882bb71a5df59f4962e02f89a63efb1cf5f32ded94694528be6cec.mtdt"
private const val SLIP15_PLAINTEXT =
    """{"accountLabel":"Saving account","addressLabels":{"1JAd7XCBzGudGpJQSDSfpmJhiygtLQWaGL":"My receiving address","1GWFxtwWmNVqotUPXLcKVL2mUKpshuJYo":""},"version":"1.0.0","outputLabels":{"350eebc1012ce2339b71b5fca317a0d174abc3a633684bc65a71845deb596539":{"0":"Money to Adam"},"ebbd138134e2c8acfee4fd4edb6f7f9175ee7b4020bcc82aba9a13ce06fae85b":{"0":"Feeding bitcoin eater"}}}"""

// SLIP-0015 example file: 12-byte IV, 16-byte tag, then ciphertext.
private const val SLIP15_FILE_HEX =
    """
    d32a5831b74ba04cdf44309fbb96a1b464fe5d4a27d1e753c30602ba1947
    3cca7d8734e8b9442dbd41d530c42e03fea59a5d38b21392f3e4a135eb07
    009d5a8b9996055b7aff076918c4ed63ee49db56c5a6b069cac7f221f704
    5af7197cdbb562ba004d7a6f06eb7cffd1dfb177fd652e66c2d05d944b58
    85d6a104853a0d07e4cebff3513a2f6a1c8ff6f4f98ce222f3d601f1c796
    d070b7523649e10242dfe78cb2db50e826dd18b1f65213f5c0748577ecc9
    7b8e13ab9cd0c5fe7b76635717c64ad352064a3321df6bbfa2db8ef8c692
    55ef9d8a8dfbce9c6ad3029bbdcf1b2bb04795fd96aa95d27e6ca1ed2658
    bfb108b44dac2159184d6e3cabe341e2ec5d83756aeb8c408e92fe6ca3e6
    3d4c0d644aa2648341506324574d205934c65f54979b1d684f7a2442e8d5
    2149ed67449019e6091aa182afcaf5aa1fa8bf3114ee7b46e47b4c6648d1
    d1355cefd10081be6e8c7bdf1b2ff14d8896b1ede811fa1aa2c024a6ebf3
    6baf0a8d6afa2975bf551e8bc3f03117b42dc4cbe2a6bd700f2fda40c78a
    48627ebc130286ba98
    """

private fun slip15ExampleFile(): ByteArray = hexToBytes(SLIP15_FILE_HEX)

private fun hexToBytes(hex: String): ByteArray {
    val compact = hex.filterNot { it.isWhitespace() }
    return ByteArray(compact.length / 2) { index ->
        compact.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
