import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.all {
            linkerOpts("-lsqlite3")
        }
    }
    jvm()
    android {
        namespace = "io.bluewallet.blueberry.parse"
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
        withHostTest { }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":peers"))
            api(project(":storage"))
            api(project(":bus"))
            api(project(":wallet"))
            implementation(project(":headers"))
            implementation(libs.bitcoin.kmp)
            implementation(libs.bip158)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.bitcoin.kmp)
            implementation(libs.bip158)
        }
        androidMain.dependencies {
            implementation(libs.secp256k1.jni.android)
        }
        jvmMain.dependencies {
            implementation(libs.secp256k1.jni.jvm)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.secp256k1.jni.jvm)
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.sqlite.jdbc)
        }
    }
}
