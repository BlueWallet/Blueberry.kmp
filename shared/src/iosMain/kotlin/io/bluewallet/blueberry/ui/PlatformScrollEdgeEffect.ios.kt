package io.bluewallet.blueberry.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGContextDrawLinearGradient
import platform.CoreGraphics.CGFloatVar
import platform.CoreGraphics.CGGradientCreateWithColorComponents
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.UIKit.UIBlurEffect
import platform.UIKit.UIBlurEffectStyle
import platform.UIKit.UIColor
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetCurrentContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageView
import platform.UIKit.UIView
import platform.UIKit.UIVisualEffectView

private val TopFeather = 20.dp

@Composable
actual fun PlatformScrollEdge(
    modifier: Modifier,
    top: Dp,
    bottom: Dp,
    content: @Composable () -> Unit,
) {
    val showEdges = LocalScrollEdgeOverlaysEnabled.current
    Box(modifier) {
        content()
        if (showEdges) {
            Box(Modifier.fillMaxSize()) {
                FadeBlur(
                    fadeFromTop = true,
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(top + TopFeather),
                )
                FadeBlur(
                    fadeFromTop = false,
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(bottom + IosBottomEdgeFeather),
                )
            }
        }
    }
}

/**
 * System blur, faded by a mask on the container. Masking the effect view itself
 * leaves a hard edge.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun FadeBlur(
    fadeFromTop: Boolean,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val dark = LocalDarkPalette.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    UIKitView(
        factory = { FadeBlurView(fadeFromTop) },
        modifier = modifier.onSizeChanged { size = it },
        update = { view ->
            view.applyTone(dark)
            view.place(
                width = size.width / density.density.toDouble(),
                height = size.height / density.density.toDouble(),
            )
        },
        properties =
            UIKitInteropProperties(
                placedAsOverlay = true,
                isNativeAccessibilityEnabled = false,
            ),
    )
}

@OptIn(ExperimentalForeignApi::class)
private class FadeBlurView(
    private val fadeFromTop: Boolean,
) : UIView(CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    private val effectView: UIVisualEffectView
    private val tintFill = UIView(CGRectMake(0.0, 0.0, 0.0, 0.0))
    private val fadeMask = UIImageView()
    private var placedWidth = -1.0
    private var placedHeight = -1.0
    private var darkTone: Boolean? = null

    init {
        userInteractionEnabled = false
        backgroundColor = UIColor.clearColor
        clipsToBounds = true
        effectView = UIVisualEffectView()
        effectView.userInteractionEnabled = false
        effectView.backgroundColor = UIColor.clearColor
        tintFill.userInteractionEnabled = false
        effectView.contentView.addSubview(tintFill)
        addSubview(effectView)
        applyTone(dark = true)
    }

    fun applyTone(dark: Boolean) {
        if (darkTone == dark) return
        darkTone = dark
        val style =
            if (dark) {
                UIBlurEffectStyle.UIBlurEffectStyleDark
            } else {
                UIBlurEffectStyle.UIBlurEffectStyleLight
            }
        effectView.effect = UIBlurEffect.effectWithStyle(style)
        val color = if (dark) UIColor.blackColor else UIColor.whiteColor
        tintFill.backgroundColor = color.colorWithAlphaComponent(0.82)
    }

    fun place(
        width: Double,
        height: Double,
    ) {
        if (width <= 0.0 || height <= 0.0) return
        val rect = CGRectMake(0.0, 0.0, width, height)
        effectView.setFrame(rect)
        tintFill.setFrame(rect)
        if (width == placedWidth && height == placedHeight) return
        placedWidth = width
        placedHeight = height
        fadeMask.image = edgeFadeImage(width, height, fadeFromTop)
        fadeMask.setFrame(rect)
        maskView = fadeMask
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun edgeFadeImage(
    width: Double,
    height: Double,
    fadeFromTop: Boolean,
): UIImage? {
    // 0.0 = device scale so the mask stays sharp on Retina.
    UIGraphicsBeginImageContextWithOptions(CGSizeMake(width, height), false, 0.0)
    val context = UIGraphicsGetCurrentContext()
    if (context != null) {
        memScoped {
            val components = allocArray<CGFloatVar>(12)
            val alphas = if (fadeFromTop) doubleArrayOf(1.0, 1.0, 0.0) else doubleArrayOf(0.0, 1.0, 1.0)
            alphas.forEachIndexed { index, alpha ->
                val offset = index * 4
                components[offset] = 1.0
                components[offset + 1] = 1.0
                components[offset + 2] = 1.0
                components[offset + 3] = alpha
            }
            val locations = allocArray<CGFloatVar>(3)
            locations[0] = 0.0
            locations[1] = if (fadeFromTop) 0.42 else 0.58
            locations[2] = 1.0
            val gradient =
                CGGradientCreateWithColorComponents(
                    CGColorSpaceCreateDeviceRGB(),
                    components,
                    locations,
                    3u,
                )
            CGContextDrawLinearGradient(
                context,
                gradient,
                CGPointMake(0.0, 0.0),
                CGPointMake(0.0, height),
                0u,
            )
        }
    }
    val image = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()
    return image
}
