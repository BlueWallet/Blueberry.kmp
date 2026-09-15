package io.bluewallet.blueberry.wallet

const val BIP44_ACCOUNT_PATH = "m/44'/0'/0'"
const val BIP49_ACCOUNT_PATH = "m/49'/0'/0'"
const val BIP84_ACCOUNT_PATH = "m/84'/0'/0'"
const val BIP86_ACCOUNT_PATH = "m/86'/0'/0'"
const val WALLET_SECRET_KEY = "wallet_secret"
const val WATCH_EXTERNAL_KEY = "watch_external"
const val WATCH_INTERNAL_KEY = "watch_internal"
const val WATCH_EXTERNAL_P2PKH_KEY = "watch_external_p2pkh"
const val WATCH_INTERNAL_P2PKH_KEY = "watch_internal_p2pkh"
const val WATCH_EXTERNAL_P2SH_P2WPKH_KEY = "watch_external_p2sh_p2wpkh"
const val WATCH_INTERNAL_P2SH_P2WPKH_KEY = "watch_internal_p2sh_p2wpkh"
const val WATCH_EXTERNAL_P2WPKH_KEY = "watch_external_p2wpkh"
const val WATCH_INTERNAL_P2WPKH_KEY = "watch_internal_p2wpkh"
const val WATCH_EXTERNAL_P2TR_KEY = "watch_external_p2tr"
const val WATCH_INTERNAL_P2TR_KEY = "watch_internal_p2tr"
const val RECEIVE_SCRIPT_TYPE_KEY = "receive_script_type"
const val GAP_LIMIT = 20
const val INITIAL_WATCH_COUNT = GAP_LIMIT * 2
const val MAX_WATCH_COUNT = 10_000
const val WALLET_BIRTHDAY_HEIGHT_KEY = "wallet_birthday_height"
const val WALLET_BIRTHDAY_PENDING = "pending"
const val BC_UR_PSBT_CAPACITY = 175
