import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
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
           version = release(libs.versions.android.compileSdk.get().toInt()) {
               minorApiLevel = libs.versions.android.compileSdkMinor.get().toInt()
           }
       }
       minSdk = libs.versions.android.minSdk.get().toInt()
    
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
        }
        commonMain.dependencies {
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
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.qrose)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.bitcoin.headers)
            implementation(libs.bip324)
            implementation(libs.bip157)
            implementation(libs.bip158)
            implementation(libs.echalote)
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

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}