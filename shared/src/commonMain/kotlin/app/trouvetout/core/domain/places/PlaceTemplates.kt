package app.trouvetout.core.domain.places

import app.trouvetout.core.capture.Lang
import app.trouvetout.core.domain.model.PlaceKind

/** A proposed place with its proposed rooms or spots (D2: proposed, never imposed). */
public data class PlaceTemplate(
    val name: String,
    val kind: PlaceKind,
    val children: List<PlaceTemplate> = emptyList(),
)

/** The starter places offered on an empty places screen, in the user's language. */
public object PlaceTemplates {
    public fun forLang(lang: Lang): List<PlaceTemplate> = if (lang == Lang.FR) FR else EN

    private fun rooms(vararg names: String) = names.map { PlaceTemplate(it, PlaceKind.ROOM) }

    private fun spots(vararg names: String) = names.map { PlaceTemplate(it, PlaceKind.SPOT) }

    private val FR: List<PlaceTemplate> =
        listOf(
            PlaceTemplate(
                "Maison",
                PlaceKind.ROOT,
                rooms(
                    "Entrée",
                    "Cuisine",
                    "Salon",
                    "Chambre",
                    "Salle de bain",
                    "Bureau",
                    "Buanderie",
                    "Cave",
                    "Grenier",
                    "Garage",
                ),
            ),
            PlaceTemplate(
                "Appartement",
                PlaceKind.ROOT,
                rooms("Entrée", "Cuisine", "Salon", "Chambre", "Salle de bain", "Placard"),
            ),
            PlaceTemplate("Voiture", PlaceKind.ROOT, spots("Boîte à gants", "Coffre")),
            PlaceTemplate("Garage", PlaceKind.ROOT, spots("Établi", "Étagères")),
            PlaceTemplate("Cave", PlaceKind.ROOT),
            PlaceTemplate("Garde-meuble", PlaceKind.ROOT),
            PlaceTemplate("Résidence secondaire", PlaceKind.ROOT),
        )

    private val EN: List<PlaceTemplate> =
        listOf(
            PlaceTemplate(
                "Home",
                PlaceKind.ROOT,
                rooms(
                    "Hallway",
                    "Kitchen",
                    "Living room",
                    "Bedroom",
                    "Bathroom",
                    "Office",
                    "Laundry",
                    "Basement",
                    "Attic",
                    "Garage",
                ),
            ),
            PlaceTemplate(
                "Flat",
                PlaceKind.ROOT,
                rooms("Hallway", "Kitchen", "Living room", "Bedroom", "Bathroom", "Closet"),
            ),
            PlaceTemplate("Car", PlaceKind.ROOT, spots("Glove box", "Boot")),
            PlaceTemplate("Garage", PlaceKind.ROOT, spots("Workbench", "Shelves")),
            PlaceTemplate("Basement", PlaceKind.ROOT),
            PlaceTemplate("Storage unit", PlaceKind.ROOT),
            PlaceTemplate("Holiday home", PlaceKind.ROOT),
        )
}
