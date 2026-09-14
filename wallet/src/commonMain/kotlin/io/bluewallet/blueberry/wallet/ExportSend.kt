package io.bluewallet.blueberry.wallet

import kotlin.io.encoding.Base64

fun psbtBase64FromHex(psbtHex: String): String = Base64.encode(hexToBytes(psbtHex))
