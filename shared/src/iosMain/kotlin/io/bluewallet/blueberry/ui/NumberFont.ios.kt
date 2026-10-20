@file:OptIn(ExperimentalTextApi::class)

package io.bluewallet.blueberry.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIFont
import platform.UIKit.UIFontDescriptorSystemDesignRounded

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun numberFontFamily(weight: FontWeight): FontFamily {
    val family = remember { roundedFamilyName() }
    return remember(family, weight) {
        if (family == null) {
            FontFamily.Default
        } else {
            FontFamily(SystemFont(family, weight, FontStyle.Normal))
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun roundedFamilyName(): String? {
    val base = UIFont.systemFontOfSize(17.0)
    val descriptor =
        base.fontDescriptor.fontDescriptorWithDesign(UIFontDescriptorSystemDesignRounded)
            ?: return null
    return UIFont.fontWithDescriptor(descriptor, 17.0).familyName
}
