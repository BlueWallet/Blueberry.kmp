# Behavioral invariants

These rules must keep working. Read this file before you change wallet behavior. Do not break a listed rule.

Each entry is one feature. Start it with a heading `## INV-N`. The paragraphs under the heading state what the user sees, the important cases, and what must stay true.

End each entry with `Check:`. Put the test path and the test name on that line when an automated test locks the behavior. Put manual steps under `Check:` when a test cannot lock it.

Number entries in order: INV-1, INV-2, and so on.

## INV-1

A receive address can have a label. Every coin paid to that address uses that label as its default name. The default is the address label at the moment you look at the coin: renaming the address renames those coins, and clearing the address label clears the default. A name saved on the coin itself replaces the default. Clearing that coin name brings the address label back when the transaction that created the coin has no label of its own. The default is only a display name. Saving the coin without typing a new name does not store the address label as the coin's own name, and a label backup does not export it as a coin label.

Check: `shared/src/commonTest/kotlin/io/bluewallet/blueberry/UtxoDefaultLabelTest.kt` — `UtxoDefaultLabelTest`

## INV-2

A receive address can have a label. Every transaction that pays that address, or spends a coin paid to it, uses that label as its default name. The default is the address label at the moment you look at the transaction: renaming the address renames those transactions, and clearing the address label clears the default. A note saved on the transaction replaces the default. A name saved on a coin this transaction created also replaces the default. Clearing that note or that coin name brings the address label back. When the transaction pays more than one labelled address, the earliest output wins. When it only spends labelled addresses, the first one it spends wins. The default is only a display name. It is not stored as the transaction's note, and a label backup does not export it as a transaction label.

Check: `shared/src/commonTest/kotlin/io/bluewallet/blueberry/TxDisplayTest.kt` — `address_label_is_the_default_transaction_name`; `shared/src/commonTest/kotlin/io/bluewallet/blueberry/PaymentLabelResolveTest.kt` — `spend_of_a_labelled_address_uses_that_label_when_no_output_does`

## INV-3

A transaction can have a label. Each coin that transaction creates shows that label as its default name. The default is the transaction label at the moment you look at the coin: renaming the transaction renames those coins, and clearing the transaction label clears the default. A name saved on the coin itself replaces the default. Clearing that coin name brings the transaction label back. When the transaction has no label, the coin falls back to its address label. Saving the transaction label also writes that name onto coins that have no name, or that still carry the previous transaction label. A coin that already has a different name keeps it. Opening a coin and saving it without typing a new name does not store the transaction label as the coin's own name, and a label backup does not export that unsaved default as a coin label.

Check: `shared/src/commonTest/kotlin/io/bluewallet/blueberry/UtxoDefaultLabelTest.kt` — `transaction_label_is_the_default_when_the_coin_has_no_name`, `transaction_label_labels_its_coin_without_saving_a_coin_name`; `shared/src/commonTest/kotlin/io/bluewallet/blueberry/TxNotesActionsTest.kt` — `set_note_names_empty_coins_and_keeps_a_different_name`

## INV-4

A coin can have a name. The transaction that created that coin uses the coin's name as its default label. The default is the coin name at the moment you look at the transaction: renaming the coin renames that transaction, and clearing the coin name clears the default. The name still applies after the coin is spent. A note saved on the transaction replaces the coin name. When the transaction created several named coins, the earliest output's name is the default. When the coin has no name of its own, the transaction falls back to the address label. The default is only a display name. It is not stored as the transaction's note, and a label backup does not export it as a transaction label.

Check: `shared/src/commonTest/kotlin/io/bluewallet/blueberry/TxWalletDisplayTest.kt` — `coin_name_is_the_default_title_of_the_transaction_that_created_it`

## INV-5

A coin can have a name. The address of that coin uses the coin's name as its default label. You see that name in the address label field on the coin and on the coin's transaction, when the address has no label of its own. A label saved on the address replaces the coin name. Clearing the address label brings the coin name back. Clearing the coin name clears the default. Two coins paid to one address each use their own name. The default is only a display hint. Saving the address without typing a new label does not store the coin name as the address label, and a label backup does not export it as an address label.

Check: `shared/src/commonTest/kotlin/io/bluewallet/blueberry/CoinSheetModelTest.kt` — `coin_name_is_the_default_address_label`; `shared/src/commonTest/kotlin/io/bluewallet/blueberry/TxDisplayTest.kt` — `coin_name_is_the_default_address_label_on_its_card`

## INV-6

When you create a payment and set its label, each change coin that payment creates is named `change from: {label}`. The label is trimmed first, so spaces around it are not part of the name. A payment that sends the whole coin creates no change, so it stores the payment label and no change name. A blank label is not saved. This name is the coin's own saved name.

Check: `parse/src/commonTest/kotlin/io/bluewallet/blueberry/parse/PaymentLabelTest.kt` — `trims_label_and_names_change_outpoints`, `send_max_stores_label_without_utxo_name`

## INV-7

A receive address can already hold a coin with a saved name. Another coin on that address shows that name when it has no name of its own, the transaction that created it has no label, and the address has no label. The name comes from the newest other received coin on that address, including a coin that was already spent. A change coin's name does not spread. Renaming or clearing the source coin updates the default. A name saved on the coin itself replaces the default. The name field starts empty, so you can type a new name without first deleting the inferred one. The default is only a display name. It is not stored as the coin's own name, and a label backup does not export it as a coin label.

Check: `shared/src/commonTest/kotlin/io/bluewallet/blueberry/UtxoDefaultLabelTest.kt` — `a_named_coin_names_another_coin_on_the_same_address_without_saving_it`, `a_spent_coins_name_still_labels_a_new_coin_on_that_address`
