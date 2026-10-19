package io.bluewallet.blueberry

internal const val ABOUT_GITHUB_URL = "https://github.com/BlueWallet/Blueberry.kmp"
internal const val ABOUT_LICENSE_URL = "$ABOUT_GITHUB_URL/blob/master/LICENSE"

/** A third-party library shown on the About screen. [name] is its GitHub owner/repo. */
internal data class AppDependency(
    val name: String,
    val version: String,
    val license: String,
)

internal val aboutDependencies =
    listOf(
        AppDependency("ACINQ/bitcoin-kmp", "0.31.0", "Apache-2.0"),
        AppDependency("ACINQ/secp256k1-kmp", "0.23.0", "Apache-2.0"),
        AppDependency("sqldelight/sqldelight", "2.3.2", "Apache-2.0"),
        AppDependency("alexzhirkevich/qrose", "1.1.2", "MIT"),
        AppDependency("sarxos/webcam-capture", "0.3.12", "MIT"),
        AppDependency("xerial/sqlite-jdbc", "3.53.2.1", "Apache-2.0"),
        AppDependency("qos-ch/slf4j", "2.0.17", "MIT"),
    )

/** Pulled in by the libraries above or by our vendored libs. Alphabetical. */
internal val transitiveDependencies =
    listOf(
        // Compiled into secp256k1-kmp's native libs; pinned commit between v0.7.1 and v0.8.0.
        AppDependency("bitcoin-core/secp256k1", "57315a6", "MIT"),
        AppDependency("google/auto", "auto-value-annotations 1.6.3", "Apache-2.0"),
        AppDependency("google/guava", "listenablefuture 1.0", "Apache-2.0"),
        AppDependency("ionspin/kotlin-multiplatform-bignum", "0.3.10", "Apache-2.0"),
        AppDependency("jspecify/jspecify", "1.0.0", "Apache-2.0"),
        AppDependency("KotlinCrypto/bitops", "0.3.0", "Apache-2.0"),
        AppDependency("KotlinCrypto/core", "0.8.0", "Apache-2.0"),
        AppDependency("KotlinCrypto/error", "0.4.0", "Apache-2.0"),
        AppDependency("KotlinCrypto/hash", "0.8.0", "Apache-2.0"),
        AppDependency("nativelibs4java/BridJ", "0.7.0", "BSD-3-Clause"),
        AppDependency("touchlab/SQLiter", "1.3.3", "Apache-2.0"),
        AppDependency("touchlab/Stately", "2.1.0", "Apache-2.0"),
    )
