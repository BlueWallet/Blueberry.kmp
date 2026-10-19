package io.bluewallet.blueberry

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AboutVersionsTest {
    // Dependency name -> (dir of the libs.versions.toml that declares it, its key in [versions]).
    // Transitive pins not declared in this repo (from published POMs or prebuilt natives) are not checked.
    private val catalogKeys =
        mapOf(
            "ACINQ/bitcoin-kmp" to (".." to "bitcoin-kmp"),
            "ACINQ/secp256k1-kmp" to (".." to "secp256k1-kmp"),
            "sqldelight/sqldelight" to (".." to "sqldelight"),
            "alexzhirkevich/qrose" to (".." to "qrose"),
            "sarxos/webcam-capture" to (".." to "webcam-capture"),
            "xerial/sqlite-jdbc" to (".." to "sqlite-jdbc"),
            "qos-ch/slf4j" to (".." to "slf4j"),
            "ionspin/kotlin-multiplatform-bignum" to ("../vendor/bitcoin-headers.kmp" to "ionspin-bignum"),
            "KotlinCrypto/hash" to ("../vendor/bitcoin-headers.kmp" to "kotlincrypto-hash"),
        )

    private fun catalogVersions(dir: String): Map<String, String> =
        File(dir, "gradle/libs.versions.toml")
            .readLines()
            .dropWhile { it.trim() != "[versions]" }
            .drop(1)
            .takeWhile { !it.trim().startsWith("[") }
            .mapNotNull { Regex("""^\s*([\w-]+)\s*=\s*"([^"]*)"""").find(it)?.destructured }
            .associate { (key, version) -> key to version }

    @Test
    fun every_direct_dependency_is_mapped_to_a_catalog_key() {
        val unmapped = aboutDependencies.map { it.name } - catalogKeys.keys
        assertTrue(unmapped.isEmpty(), "No catalog key for $unmapped")
    }

    @Test
    fun versions_match_their_own_catalog_keys() {
        val shown = (aboutDependencies + transitiveDependencies).associate { it.name to it.version }
        catalogKeys.forEach { (name, source) ->
            val (dir, key) = source
            assertEquals(catalogVersions(dir)[key], shown[name], "$name vs $dir/gradle/libs.versions.toml [versions] $key")
        }
    }
}
