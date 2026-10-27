package app.trouvetout.android.ui

/** Screens of the app and how they reach each other; capture is home (lot 3), places and items arrive in lot 4. */
object Routes {
    const val CAPTURE = "capture"
    const val SETTINGS = "settings"
    const val PLACES = "places"
    const val PLACE = "place/{id}"
    const val CONTAINER = "container/{id}"
    const val ITEM = "item/{id}"

    fun place(id: String) = "place/$id"

    fun container(id: String) = "container/$id"

    fun item(id: String) = "item/$id"
}
