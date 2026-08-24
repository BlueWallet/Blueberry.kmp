rootProject.name = "Blueberry"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

fun androidSdkDir(): String? {
    val fromLocal = file("local.properties")
        .takeIf { it.isFile }
        ?.readLines()
        ?.firstOrNull { it.startsWith("sdk.dir=") }
        ?.substringAfter("sdk.dir=")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    return fromLocal
        ?: System.getenv("ANDROID_HOME")?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv("ANDROID_SDK_ROOT")?.trim()?.takeIf { it.isNotEmpty() }
}

fun File.writeSdkDir(sdkDir: String) {
    val line = "sdk.dir=$sdkDir"
    val lines = if (isFile) readLines().toMutableList() else mutableListOf()
    val index = lines.indexOfFirst { it.startsWith("sdk.dir=") }
    if (index >= 0) {
        if (lines[index] == line) return
        lines[index] = line
    } else {
        lines += line
    }
    writeText(lines.joinToString("\n", postfix = "\n"))
}

fun includeVendorBuild(dirName: String, module: String, projectPath: String) {
    val dir = file("vendor/$dirName")
    require(dir.resolve("settings.gradle.kts").isFile) {
        "Missing vendor/$dirName. Run: git submodule update --init"
    }
    androidSdkDir()?.let { dir.resolve("local.properties").writeSdkDir(it) }
    includeBuild(dir) {
        dependencySubstitution {
            substitute(module(module)).using(project(projectPath))
        }
    }
}

includeVendorBuild("bitcoin-headers.kmp", "io.bluewallet:bitcoin-headers", ":bitcoin-headers")
includeVendorBuild("bip324.kmp", "io.bluewallet:bip324", ":bip324")
includeVendorBuild("bip157.kmp", "io.bluewallet:bip157", ":bip157")
includeVendorBuild("bip158.kmp", "io.bluewallet:bip158", ":bip158")
includeVendorBuild("echalote.kmp", "io.bluewallet:echalote", ":echalote")
includeVendorBuild("qr.kmp", "io.bluewallet:qr", ":qr")

include(":androidApp")
include(":desktopApp")
include(":shared")
include(":storage")
include(":wallet")
include(":bus")
include(":peers")
include(":headers")
include(":filters")
include(":blocks")
include(":sync")
include(":parse")
include(":broadcast")
