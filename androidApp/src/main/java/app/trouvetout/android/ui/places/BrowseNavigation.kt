package app.trouvetout.android.ui.places

/** Where the user navigates from a browse screen. */
data class BrowseNavigation(
    val onBack: () -> Unit,
    val onOpenPlace: (String) -> Unit,
    val onOpenContainer: (String) -> Unit,
    val onOpenItem: (String) -> Unit,
)
