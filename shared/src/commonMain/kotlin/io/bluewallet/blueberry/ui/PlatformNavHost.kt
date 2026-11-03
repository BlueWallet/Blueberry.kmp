package io.bluewallet.blueberry.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController

/**
 * NavHost with per-platform transitions. iOS passes explicit transitions (see the iOS actual for
 * why); other platforms keep the navigation-compose defaults.
 */
@Composable
internal expect fun PlatformNavHost(
    navController: NavHostController,
    startDestination: Any,
    modifier: Modifier,
    builder: NavGraphBuilder.() -> Unit,
)
