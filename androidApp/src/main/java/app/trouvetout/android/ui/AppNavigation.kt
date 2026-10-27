package app.trouvetout.android.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.trouvetout.android.ui.capture.CaptureRoute
import app.trouvetout.android.ui.item.ItemRoute
import app.trouvetout.android.ui.places.BrowseNavigation
import app.trouvetout.android.ui.places.BrowseRoute
import app.trouvetout.android.ui.places.BrowseTarget
import app.trouvetout.android.ui.settings.SettingsRoute

@Composable
fun AppNavigation(controller: NavHostController = rememberNavController()) {
    val browse =
        BrowseNavigation(
            onBack = { controller.popBackStack() },
            onOpenPlace = { controller.navigate(Routes.place(it)) },
            onOpenContainer = { controller.navigate(Routes.container(it)) },
            onOpenItem = { controller.navigate(Routes.item(it)) },
        )
    NavHost(navController = controller, startDestination = Routes.CAPTURE) {
        composable(Routes.CAPTURE) {
            CaptureRoute(
                onOpenSettings = { controller.navigate(Routes.SETTINGS) },
                onOpenPlaces = { controller.navigate(Routes.PLACES) },
                onOpenItem = { controller.navigate(Routes.item(it)) },
            )
        }
        composable(Routes.SETTINGS) { SettingsRoute(onBack = { controller.popBackStack() }) }
        composable(Routes.PLACES) { BrowseRoute(BrowseTarget.Root, browse) }
        composable(Routes.PLACE) { entry -> BrowseRoute(BrowseTarget.Place(entry.id()), browse) }
        composable(Routes.CONTAINER) { entry -> BrowseRoute(BrowseTarget.Container(entry.id()), browse) }
        composable(Routes.ITEM) { entry ->
            ItemRoute(
                itemId = entry.id(),
                onBack = { controller.popBackStack() },
                onOpenPlace = { controller.navigate(Routes.place(it)) },
            )
        }
    }
}

private fun NavBackStackEntry.id(): String = checkNotNull(arguments?.getString("id")) { "route without id" }
