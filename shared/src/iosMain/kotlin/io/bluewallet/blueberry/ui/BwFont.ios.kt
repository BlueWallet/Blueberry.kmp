@file:OptIn(ExperimentalTextApi::class)

package io.bluewallet.blueberry.ui

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIFont
import platform.UIKit.UIFontWeightSemibold

actual val BwDefaultFontWeight: FontWeight = FontWeight.Normal

@OptIn(ExperimentalForeignApi::class)
actual val BwFontFamily: FontFamily = sfProFamily()

@OptIn(ExperimentalForeignApi::class)
private fun sfProFamily(): FontFamily {
    val family = sfProFamilyName() ?: return FontFamily.Default
    val weights =
        listOf(
            FontWeight.Normal,
            FontWeight.Medium,
            FontWeight.SemiBold,
            FontWeight.Bold,
            FontWeight.ExtraBold,
        )
    return FontFamily(weights.map { SystemFont(family, it, FontStyle.Normal) })
}

@OptIn(ExperimentalForeignApi::class)
private fun sfProFamilyName(): String? {
    val semibold = UIFont.systemFontOfSize(17.0, UIFontWeightSemibold)
    return semibold.familyName
}
