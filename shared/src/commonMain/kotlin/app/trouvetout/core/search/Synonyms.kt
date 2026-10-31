package app.trouvetout.core.search

import app.trouvetout.core.capture.Text

/**
 * Household vocabulary that names the same thing differently (C3 « synonymes métier »), French and English in
 * the same group so that a question in one language finds an item written in the other. Word level: each word
 * of a group expands to the others. Written naturally; folded and stemmed once like the indexed text. A word
 * belonging to several groups expands to all of them.
 */
internal object Synonyms {
    private val GROUPS: List<String> =
        listOf(
            // Keys, papers, wallets
            "clé clef key",
            "trousseau porte-clés porte-clefs keyring",
            "passeport passport",
            "papiers documents paperasse papers paperwork",
            "portefeuille porte-monnaie wallet purse",
            "facture invoice bill receipt",
            "garantie warranty",
            "notice manuel manual instructions",
            "permis licence license",
            "carnet livret booklet notebook",
            "agenda diary planner",
            // Electronics
            "chargeur câble alimentation alim adaptateur cordon charger cable cord adapter adaptor",
            "multiprise rallonge powerstrip extension",
            "batterie pile accu accumulateur battery",
            "écouteurs oreillettes casque earphones earbuds headphones headset",
            "téléphone portable smartphone mobile phone",
            "ordinateur pc laptop computer",
            "tablette ipad tablet",
            "liseuse kindle ereader e-reader",
            "télécommande zapette remote",
            "enceinte haut-parleur speaker",
            "ampoule bulb",
            "lampe lamp light",
            "torche flashlight torch",
            "caméra camera camescope camcorder",
            "imprimante printer",
            "souris mouse",
            "clavier keyboard",
            "écran moniteur screen monitor display",
            "manette controller gamepad joystick",
            "routeur modem router wifi",
            // Tools
            "outil outillage tool",
            "perceuse visseuse drill",
            "tournevis screwdriver",
            "marteau maillet hammer mallet",
            "pince tenaille pliers pincers",
            "molette wrench spanner",
            "scie saw",
            "mètre tape measure",
            "niveau level",
            "vis screw",
            "clou nail",
            "cheville anchor rawlplug",
            "boulon bolt",
            "écrou nut",
            "colle glue",
            "scotch adhésif tape",
            "ficelle corde cordelette string rope twine",
            "échelle escabeau ladder stepladder",
            "pinceau brosse rouleau brush roller",
            "peinture paint",
            "gant glove",
            "lunettes glasses spectacles",
            "solaires sunglasses",
            "aspirateur vacuum hoover",
            "balai broom",
            "seau bucket pail",
            "tuyau hose pipe",
            "arrosoir watering",
            "tondeuse mower lawnmower",
            "sécateur pruner secateurs",
            "pelle shovel spade",
            "râteau rake",
            "brouette wheelbarrow",
            "cadenas padlock lock",
            // Storage
            "carton boîte boite caisse bac box crate bin",
            "valise bagage suitcase luggage",
            "sac sacoche bag",
            "sac-à-dos backpack rucksack",
            "tiroir drawer",
            "étagère rayonnage shelf shelving",
            "placard armoire penderie cupboard wardrobe closet",
            "commode dresser",
            "coffre malle trunk chest",
            "panier corbeille basket",
            "pochette trousse étui case pouch",
            "enveloppe envelope",
            "classeur dossier chemise binder folder file",
            // Kitchen
            "tire-bouchon corkscrew",
            "décapsuleur opener",
            "ouvre-boîte ouvre-boite can-opener",
            "casserole poêle pan saucepan",
            "couteau knife",
            "fourchette fork",
            "cuillère cuiller spoon",
            "verre glass",
            "tasse mug cup",
            "assiette plate",
            "saladier bol bowl",
            "mixeur blender mixer",
            "bouilloire kettle",
            "cafetière coffee",
            "thermos gourde flask",
            "torchon serviette towel",
            "nappe tablecloth",
            "recette recipe",
            // Clothes, linen
            "vêtement habit fringue clothes clothing",
            "chaussure soulier shoe",
            "botte boot",
            "basket sneaker trainer",
            "manteau veste blouson parka coat jacket",
            "pull sweat pullover sweater jumper",
            "écharpe foulard scarf",
            "bonnet chapeau casquette hat cap beanie",
            "chaussette sock",
            "pantalon jean trousers pants jeans",
            "drap housse sheet",
            "couette duvet comforter",
            "oreiller coussin pillow cushion",
            "couverture plaid blanket throw",
            "maillot swimsuit swimwear",
            "parapluie umbrella",
            // Leisure, sport, travel
            "vélo bicyclette bike bicycle",
            "ski skis",
            "tente tent",
            "duvet sleeping",
            "ballon balle ball",
            "raquette racket racquet",
            "jeu game",
            "jouet toy",
            "puzzle jigsaw",
            "livre bouquin book",
            "photo photograph picture",
            "guitare guitar",
            "pompe gonfleur pump",
            "glacière cooler coolbox",
            "barbecue bbq grill",
            "jumelles binoculars",
            // Seasons, events
            "noël christmas xmas",
            "décoration déco decoration ornament",
            "guirlande garland tinsel",
            "sapin tree",
            "anniversaire birthday",
            "cadeau gift present",
            // Health, family
            "médicament médoc medicine medication pill",
            "pharmacie first-aid",
            "pansement plaster bandage",
            "thermomètre thermometer",
            "ordonnance prescription",
            "santé health",
            "bébé baby",
            "poussette pram stroller pushchair",
            "biberon baby-bottle",
            "couche nappy diaper",
            // Car, house
            "voiture auto car",
            "pneu tyre tire",
            "cric jack",
            "gilet vest",
            "fusible fuse",
            "cave sous-sol cellar basement",
            "grenier combles attic loft",
            "jardin garden yard",
            "cuisine kitchen",
            "chambre bedroom",
            "salon séjour lounge living",
            "bain bathroom",
            "bureau office desk study",
            "entrée hall hallway entrance",
            "buanderie laundry",
            "cellier pantry larder",
        )

    /** Folded and stemmed word → the other words of its groups. */
    private val INDEX: Map<String, Set<String>> by lazy {
        val out = HashMap<String, MutableSet<String>>()
        for (group in GROUPS) {
            val words = group.split(' ').flatMap(::termsOf).toSet()
            for (word in words) out.getOrPut(word) { HashSet() } += words - word
        }
        out
    }

    /** A hyphenated entry (« tire-bouchon ») stands for its joined form, as the indexed text also gives it. */
    private fun termsOf(entry: String): List<String> = listOf(SearchText.stem(Text.fold(entry).replace("-", "")))

    fun of(term: String): Set<String> = INDEX[term].orEmpty()

    /** Every word the groups know, for typo tolerance on words no item uses yet. */
    val vocabulary: Set<String> get() = INDEX.keys
}
