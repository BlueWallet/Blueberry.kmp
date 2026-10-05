import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64())
    jvm()
    android {
        namespace = "io.bluewallet.blueberry.labels"
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
            implementation(libs.bitcoin.kmp)
            implementation(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies {
            implementation(libs.secp256k1.jni.android)
        }
        jvmMain.dependencies {
            implementation(libs.secp256k1.jni.jvm)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.secp256k1.jni.jvm)
        }
    }
}
