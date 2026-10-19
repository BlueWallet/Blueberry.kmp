package io.bluewallet.blueberry

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class AboutVersionsTest {
    @Test
    fun versions_are_in_the_version_catalog() {
        val catalog =
            File("..")
                .canonicalFile
                .walkTopDown()
                .onEnter { it.name != "build" && !it.name.startsWith(".") }
                .filter { it.name == "libs.versions.toml" }
                .joinToString("\n") { it.readText() }
        aboutDependencies.forEach { assertTrue("\"${it.version}\"" in catalog, "${it.name} ${it.version} not in libs.versions.toml") }
    }
}
