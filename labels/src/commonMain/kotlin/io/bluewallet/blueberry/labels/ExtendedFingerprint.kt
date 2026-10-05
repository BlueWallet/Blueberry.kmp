package io.bluewallet.blueberry.labels

import fr.acinq.bitcoin.DeterministicWallet

/**
 * BIP32 identifier of the master key derived from [seed]: the 20-byte HASH160 of the compressed
 * master public key. The 4-byte wallet fingerprint is the first four bytes of this value.
 */
fun extendedFingerprint(seed: ByteArray): ByteArray = DeterministicWallet.generate(seed).publicKey.hash160()
