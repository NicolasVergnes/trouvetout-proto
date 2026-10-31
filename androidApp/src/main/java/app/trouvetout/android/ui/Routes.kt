package app.trouvetout.android.ui

/** Screens of the app and how they reach each other; capture is home (lot 3), places and items arrive in lot 4. */
object Routes {
    const val CAPTURE = "capture"
    const val SETTINGS = "settings"
    const val PLACES = "places"
    const val PLACE = "place/{id}"
    const val CONTAINER = "container/{id}"
    const val ITEM = "item/{id}"

    /** Search (lot 6), optionally opened on a question; the text travels inside the app only. */
    const val SEARCH = "search?q={q}"

    fun place(id: String) = "place/$id"

    fun container(id: String) = "container/$id"

    fun item(id: String) = "item/$id"

    fun search(query: String) = "search?q=${android.net.Uri.encode(query)}"
}
