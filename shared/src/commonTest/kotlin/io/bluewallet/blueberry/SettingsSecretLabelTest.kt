package io.bluewallet.blueberry

import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsSecretLabelTest {
    @Test
    fun names_each_watch_key_kind() {
        assertEquals("xpub", secretKindLabel(XPUB))
        assertEquals("ypub", secretKindLabel(YPUB))
        assertEquals("zpub", secretKindLabel(ZPUB))
        assertEquals("Descriptor", secretKindLabel("tr($TAPROOT_XPUB)"))
        assertEquals("Missing", secretKindLabel(null))
        assertEquals("Stored", secretKindLabel("wpkh(not a key)"))
    }

    private companion object {
        const val XPUB =
            "xpub6BosfCnifzxcFwrSzQiqu2DBVTshkCXacvNsWGYJVVhhawA7d4R5WSWGFNbi8Aw6ZRc1brxMyWMzG3DSSSSoekkudhUd9yLb6qx39T9nMdj"
        const val YPUB =
            "ypub6Ww3ibxVfGzLrAH1PNcjyAWenMTbbAosGNB6VvmSEgytSER9azLDWCxoJwW7Ke7icmizBMXrzBx9979FfaHxHcrArf3zbeJJJUZPf663zsP"
        const val ZPUB =
            "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"
        const val TAPROOT_XPUB =
            "xpub6BgBgsespWvERF3LHQu6CnqdvfEvtMcQjYrcRzx53QJjSxarj2afYWcLteoGVky7D3UKDP9QyrLprQ3VCECoY49yfdDEHGCtMMj92pReUsQ"
    }
}
