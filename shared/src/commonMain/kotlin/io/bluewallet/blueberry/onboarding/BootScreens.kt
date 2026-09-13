package io.bluewallet.blueberry.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun InvalidSecretScreen(detail: String) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .safeContentPadding()
                .padding(16.dp),
    ) {
        Text("wallet_secret is present but invalid: $detail")
        Text("Fix or delete the wallet_secret key in the database, then restart.")
    }
}

@Composable
fun DatabaseOpenErrorScreen(message: String) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .safeContentPadding()
                .padding(16.dp),
    ) {
        Text(message)
    }
}
