package io.bluewallet.blueberry.wallet

import io.bluewallet.blueberry.storage.Database

fun saveReceiveScriptType(
    db: Database,
    type: AddressScriptType,
) {
    db.keyValue.set(RECEIVE_SCRIPT_TYPE_KEY, type.wireName())
}

fun loadReceiveScriptType(db: Database): AddressScriptType {
    val raw = db.keyValue.get(RECEIVE_SCRIPT_TYPE_KEY) ?: return AddressScriptType.P2WPKH
    return runCatching { addressScriptTypeFromWire(raw) }.getOrDefault(AddressScriptType.P2WPKH)
}
