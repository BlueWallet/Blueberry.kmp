# Behavioral invariants

These rules must keep working. Read this file before you change wallet behavior. Do not break a listed rule.

Each entry is one feature. Start it with a heading `## INV-N`. The paragraphs under the heading state what the user sees, the important cases, and what must stay true.

End each entry with `Check:`. Put the test path and the test name on that line when an automated test locks the behavior. Put manual steps under `Check:` when a test cannot lock it.

Number entries in order: INV-1, INV-2, and so on.

## INV-1

A receive address can have a label. Every coin paid to that address uses that label as its default name. The default is the address label at the moment you look at the coin: renaming the address renames those coins, and clearing the address label clears the default. A name saved on the coin itself replaces the default. Clearing that coin name brings the address label back. The default is only a display name. Saving the coin without typing a new name does not store the address label as the coin's own name, and a label backup does not export it as a coin label.

Check: `shared/src/commonTest/kotlin/io/bluewallet/blueberry/UtxoDefaultLabelTest.kt` — `UtxoDefaultLabelTest`
