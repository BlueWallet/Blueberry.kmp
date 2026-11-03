package io.bluewallet.blueberry.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost

@Composable
internal actual fun PlatformNavHost(
    navController: NavHostController,
    startDestination: Any,
    modifier: Modifier,
    builder: NavGraphBuilder.() -> Unit,
) {
    NavHost(navController = navController, startDestination = startDestination, modifier = modifier, builder = builder)
}
