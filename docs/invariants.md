# Behavioral invariants

These rules must keep working. Read this file before you change wallet behavior. Do not break a listed rule.

Each entry is one feature. Start it with a heading `## INV-N`. The paragraphs under the heading state what the user sees, the important cases, and what must stay true.

End each entry with `Check:`. Put the test path and the test name on that line when an automated test locks the behavior. Put manual steps under `Check:` when a test cannot lock it.

Number entries in order: INV-1, INV-2, and so on.
