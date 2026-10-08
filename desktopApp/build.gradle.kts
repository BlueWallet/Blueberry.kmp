import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
}

val desktopPackageVersion =
    (findProperty("packageVersion") as String?)?.ifBlank { null } ?: "1.0.0"

compose.desktop {
    application {
        mainClass = "io.bluewallet.blueberry.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "io.bluewallet.blueberry"
            packageVersion = desktopPackageVersion
        }
    }
}

tasks.register("printDesktopPackageVersion") {
    val version = desktopPackageVersion
    doLast {
        println(version)
    }
}
