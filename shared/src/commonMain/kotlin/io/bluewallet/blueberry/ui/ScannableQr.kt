package io.bluewallet.blueberry.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.alexzhirkevich.qrose.options.QrBrush
import io.github.alexzhirkevich.qrose.options.solid
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

data class ScannableQrStyle(
    val moduleArgb: Long,
    val plateArgb: Long,
    val quietZoneDp: Int,
) {
    val moduleColor: Color get() = Color(moduleArgb)
    val plateColor: Color get() = Color(plateArgb)
}

fun scannableQrStyle(): ScannableQrStyle =
    ScannableQrStyle(
        moduleArgb = 0xFF000000L,
        plateArgb = 0xFFFFFFFFL,
        quietZoneDp = 12,
    )

@Composable
fun ScannableQr(
    data: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val style = scannableQrStyle()
    Image(
        painter =
            rememberQrCodePainter(
                data = data,
                darkBrush = QrBrush.solid(style.moduleColor),
                lightBrush = QrBrush.solid(style.plateColor),
                ballBrush = QrBrush.solid(style.moduleColor),
                frameBrush = QrBrush.solid(style.moduleColor),
            ),
        contentDescription = contentDescription,
        modifier =
            modifier
                .background(style.plateColor)
                .padding(style.quietZoneDp.dp),
    )
}
