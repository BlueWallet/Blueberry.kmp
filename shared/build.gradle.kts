import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import javax.inject.Inject

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
        iosTarget.binaries.all {
            linkerOpts("-lsqlite3")
        }
    }

    jvm()

    android {
        namespace = "io.bluewallet.blueberry.shared"
        compileSdk {
            version =
                release(
                    libs.versions.android.compileSdk
                        .get()
                        .toInt(),
                ) {
                    minorApiLevel =
                        libs.versions.android.compileSdkMinor
                            .get()
                            .toInt()
                }
        }
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        androidResources {
            enable = true
        }
        withHostTest {
            isIncludeAndroidResources = true
        }
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.camera.camera2)
            implementation(libs.androidx.camera.lifecycle)
            implementation(libs.androidx.camera.view)
        }
        jvmMain.dependencies {
            implementation(libs.webcam.capture)
            implementation(libs.slf4j.nop)
        }
        commonMain {
            kotlin.srcDir(layout.buildDirectory.dir("generated/buildSecrets/kotlin"))
            dependencies {
                implementation(project(":storage"))
                implementation(project(":wallet"))
                implementation(project(":bus"))
                implementation(project(":peers"))
                implementation(project(":headers"))
                implementation(project(":filters"))
                implementation(project(":blocks"))
                implementation(project(":sync"))
                implementation(project(":parse"))
                implementation(project(":broadcast"))
                implementation(project(":labels"))
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material3)
                implementation(libs.compose.ui)
                implementation(libs.compose.uiBackhandler)
                implementation(libs.compose.components.resources)
                implementation(libs.compose.uiToolingPreview)
                implementation(libs.navigation.compose)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.qrose)
                implementation(libs.haze)
                implementation(libs.haze.blur)
                implementation(libs.androidx.lifecycle.viewmodelCompose)
                implementation(libs.androidx.lifecycle.runtimeCompose)
                implementation(libs.bitcoin.headers)
                implementation(libs.bip324)
                implementation(libs.bip157)
                implementation(libs.bip158)
                implementation(libs.echalote)
                implementation(libs.qr)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.bitcoin.kmp)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.bitcoin.kmp)
        }
        jvmTest.dependencies {
            implementation(libs.secp256k1.jni.jvm)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.secp256k1.jni.jvm)
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.sqlite.jdbc)
        }
    }
}

/** Runs git with [Params.args] in [Params.repoDir]; empty when git is missing, fails, or there is no repo. */
abstract class GitOutput : ValueSource<String, GitOutput.Params> {
    interface Params : ValueSourceParameters {
        val repoDir: DirectoryProperty
        val args: ListProperty<String>
    }

    @get:Inject
    abstract val exec: ExecOperations

    override fun obtain(): String =
        try {
            val out = ByteArrayOutputStream()
            val result =
                exec.exec {
                    workingDir = parameters.repoDir.get().asFile
                    commandLine(listOf("git") + parameters.args.get())
                    standardOutput = out
                    errorOutput = OutputStream.nullOutputStream()
                    isIgnoreExitValue = true
                }
            if (result.exitValue == 0) out.toString(Charsets.UTF_8) else ""
        } catch (_: Exception) {
            ""
        }
}

// The changelog starts at GIT_COMMIT when set, else HEAD. A value starting with '-' would be read as a git option.
val gitRev =
    providers
        .environmentVariable("GIT_COMMIT")
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("-") }
        .orElse("HEAD")

val generateBuildSources =
    tasks.register("generateBuildSources") {
        val outDir = layout.buildDirectory.dir("generated/buildSecrets/kotlin")
        outputs.dir(outDir)
        val envKey = providers.environmentVariable("ROCKETX_API_KEY").orElse("")
        val envFileText =
            providers.fileContents(rootProject.layout.projectDirectory.file(".env")).asText.orElse("")
        // NUL-separated "<sha>\n<subject>" records; parsed by parseGitLog in commonMain.
        val gitLog =
            providers.of(GitOutput::class) {
                parameters.repoDir.set(rootProject.layout.projectDirectory)
                parameters.args.addAll("log", "-z", "--no-merges", "-20", "--format=%H%n%s")
                parameters.args.add(gitRev)
            }
        // The exact commit built, merge commits included; "" when git fails.
        val gitSha =
            providers.of(GitOutput::class) {
                parameters.repoDir.set(rootProject.layout.projectDirectory)
                parameters.args.addAll("rev-parse", "--verify")
                parameters.args.add(gitRev.map { "$it^{commit}" })
            }
        // -PversionName (Android and desktop CI), else Xcode's MARKETING_VERSION build setting, else "dev".
        val buildVersion =
            providers
                .gradleProperty("versionName")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .orElse(
                    providers
                        .environmentVariable("MARKETING_VERSION")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() },
                ).orElse("dev")
        inputs.property("rocketxEnvKey", envKey)
        inputs.property("rocketxEnvFile", envFileText)
        inputs.property("gitLog", gitLog)
        inputs.property("gitSha", gitSha)
        inputs.property("buildVersion", buildVersion)
        doLast {
            // Every char outside printable ASCII, and " \ $, becomes \uXXXX: no templates, any Unicode.
            fun kotlinString(value: String) =
                value.asIterable().joinToString("", "\"", "\"") { c ->
                    if (c in ' '..'~' && c !in "\"\\$") "$c" else "\\u%04x".format(c.code)
                }
            var key = envKey.get().trim()
            if (key.isEmpty()) {
                envFileText.get().lineSequence().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("#") || "=" !in trimmed) return@forEach
                    val name = trimmed.substringBefore("=").trim().lowercase()
                    val value =
                        trimmed
                            .substringAfter("=")
                            .trim()
                            .trim('"')
                            .trim('\'')
                    if (name == "rocketx_api_key" || name == "x-api-key" || name == "api_key") {
                        key = value
                        return@forEach
                    }
                }
            }
            val pkg = outDir.get().asFile.resolve("io/bluewallet/blueberry")
            pkg.mkdirs()
            pkg.resolve("BuildSecrets.kt").writeText(
                """
                package io.bluewallet.blueberry

                object BuildSecrets {
                    const val ROCKETX_API_KEY = ${kotlinString(key)}
                }

                """.trimIndent() + "\n",
            )
            pkg.resolve("BuildInfo.kt").writeText(
                """
                package io.bluewallet.blueberry

                internal object BuildInfo {
                    val GIT_LOG = ${kotlinString(gitLog.get())}
                    val VERSION = ${kotlinString(buildVersion.get())}
                    val GIT_SHA = ${kotlinString(gitSha.get().trim())}
                }

                """.trimIndent() + "\n",
            )
        }
    }

tasks
    .matching {
        it.name.startsWith("compile") ||
            it.name.contains("Ktlint", ignoreCase = true)
    }.configureEach {
        dependsOn(generateBuildSources)
    }

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}
