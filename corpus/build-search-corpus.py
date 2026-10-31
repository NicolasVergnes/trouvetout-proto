"""Builds corpus/fiches-reference.json and corpus/recherche.jsonl (docs/06 section 2). Deterministic: run and commit.

    python -I corpus/build-search-corpus.py        (from the repository root)

Everything here is synthetic and hand-written (origin "synthetic"); no user phrase ever enters these files
(ADR-013, docs/06 section 4). The questions were written from real-world wording, independently of the
search engine's own vocabulary (synonym list, normalisation), so that the corpus measures the engine and
not itself.

fiches-reference.json - the reference household (formatVersion 1)
    places      {id, name, parent}              hierarchy, parent null for a root (Maison, Voiture...)
    containers  {id, label, place}              movable containers (cartons, bacs, valises...)
    items       {id, name, description, tags, place, container, rawInput, status, lentTo, archived,
                 sensitive, daysAgo}
                Exactly one of `place` / `container` is set. status is STORED | TAKEN | LENT; for TAKEN and
                LENT, `place` / `container` is the last known storage spot. rawInput is the capture sentence
                as typed or dictated (null for about 40 % of the items). daysAgo = age of the last event.

recherche.jsonl - one question per line
    {"id","lang","origin","query","expect","kind","split"[, "accept"]}
    lang    language of the question (fr | en); the item may be named in the other language (crosslang)
    kind    exact | typo | synonym | paraphrase | category | crosslang | place | absent
    expect  id of the expected item, or null for kind "absent" (something not in the household)
    accept  optional: other item ids that are equally right (identical duplicates, sibling contents)
    split   holdout for every 5th of the 360 real questions (q0005, q0010...), dev otherwise (and for absent)
Order: 300 French, 60 English, then 20 absent questions (10 FR, 10 EN) which are not part of the 360.

Rules for the questions: phrased the way people ask (with or without "ou est", articles, possessives,
lowercase, oral hesitations, typed fast); the item's exact name is only reused for kind "exact"; an item is
the target of at most two questions; near-duplicates (gloves, drills, cables, batteries, screws) are only
targeted with a distinguishing word or a place. A self-check at the end of the script enforces the
structure and the distribution (+/- 2 points per kind).
"""
import io
import json
import os
import unicodedata
import zlib
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))

# ---------------------------------------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------------------------------------

PLACES = []      # (id, name, parent)
CONTAINERS = []  # (id, label, place)
ITEMS = []       # dicts, in order


def P(id, name, parent=None):
    PLACES.append((id, name, parent))


def C(id, label, place):
    CONTAINERS.append((id, label, place))


def _h(s, mod):
    return zlib.crc32(s.encode("utf-8")) % mod


def I(id, name, where, d=None, t=None, r=None, st="STORED", lent=None, arch=False, sens=False, days=None):
    """d: description; t: comma-separated tags; r: raw input (None = decided by hash, "" = never)."""
    ITEMS.append({
        "id": id, "name": name, "where": where, "d": d,
        "t": [x.strip() for x in t.split(",")] if t else [],
        "r": r, "st": st, "lent": lent, "arch": arch, "sens": sens, "days": days,
    })


# Query tables: (kind, query, expect[, accept])
Q_FR = []
Q_EN = []
Q_ABSENT_FR = []
Q_ABSENT_EN = []


def _q(table, kind, query, expect, accept):
    # ids are written without the "item_" prefix to keep the tables short
    table.append((kind, query, "item_" + expect if expect else None,
                  ["item_" + a for a in accept] if accept else None))


def qf(kind, query, expect, accept=None):
    _q(Q_FR, kind, query, expect, accept)


def qe(kind, query, expect, accept=None):
    _q(Q_EN, kind, query, expect, accept)


def qa(lang, query):
    _q(Q_ABSENT_FR if lang == "fr" else Q_ABSENT_EN, "absent", query, None, None)

# ---------------------------------------------------------------------------------------------------------
# Places and containers
# ---------------------------------------------------------------------------------------------------------

P("pl_maison", "Maison")
P("pl_entree", "Entrée", "pl_maison")
P("pl_entree_meuble_chaussures", "Meuble à chaussures", "pl_entree")
P("pl_entree_placard", "Placard de l'entrée", "pl_entree")
P("pl_entree_vide_poches", "Vide-poches", "pl_entree")
P("pl_cuisine", "Cuisine", "pl_maison")
P("pl_cuisine_tiroir_couverts", "Tiroir à couverts", "pl_cuisine")
P("pl_cuisine_tiroir_bazar", "Tiroir du bazar", "pl_cuisine")
P("pl_cuisine_placard_haut", "Placard du haut", "pl_cuisine")
P("pl_cuisine_placard_evier", "Placard sous l'évier", "pl_cuisine")
P("pl_cuisine_garde_manger", "Garde-manger", "pl_cuisine")
P("pl_salon", "Salon", "pl_maison")
P("pl_salon_meuble_tv", "Meuble TV", "pl_salon")
P("pl_salon_bibliotheque", "Bibliothèque", "pl_salon")
P("pl_salon_buffet", "Buffet", "pl_salon")
P("pl_salon_table_basse", "Table basse", "pl_salon")
P("pl_chambre", "Chambre", "pl_maison")
P("pl_chambre_commode", "Commode", "pl_chambre")
P("pl_chambre_commode_tiroir_haut", "Tiroir du haut", "pl_chambre_commode")
P("pl_chambre_commode_tiroir_bas", "Tiroir du bas", "pl_chambre_commode")
P("pl_chambre_armoire", "Armoire", "pl_chambre")
P("pl_chambre_table_nuit", "Table de nuit", "pl_chambre")
P("pl_enfants", "Chambre des enfants", "pl_maison")
P("pl_enfants_coffre", "Coffre à jouets", "pl_enfants")
P("pl_enfants_etagere", "Étagère des enfants", "pl_enfants")
P("pl_bureau", "Bureau", "pl_maison")
P("pl_bureau_tiroir", "Tiroir du bureau", "pl_bureau")
P("pl_bureau_etagere", "Étagère du bureau", "pl_bureau")
P("pl_bureau_classeur", "Classeur", "pl_bureau")
P("pl_bureau_coffre", "Coffre-fort", "pl_bureau")
P("pl_sdb", "Salle de bain", "pl_maison")
P("pl_sdb_meuble", "Meuble sous la vasque", "pl_sdb")
P("pl_sdb_pharmacie", "Armoire à pharmacie", "pl_sdb")
P("pl_buanderie", "Buanderie", "pl_maison")
P("pl_buanderie_placard", "Placard de la buanderie", "pl_buanderie")
P("pl_cave", "Cave", "pl_maison")
P("pl_cave_etagere_fond", "Étagère du fond", "pl_cave")
P("pl_cave_etagere_gauche", "Étagère de gauche", "pl_cave")
P("pl_cave_casier_vin", "Casier à vin", "pl_cave")
P("pl_grenier", "Grenier", "pl_maison")
P("pl_grenier_etagere", "Étagère du grenier", "pl_grenier")
P("pl_grenier_coin", "Coin sous le toit", "pl_grenier")
P("pl_garage", "Garage", "pl_maison")
P("pl_garage_etabli", "Établi", "pl_garage")
P("pl_garage_etagere", "Étagère métallique", "pl_garage")
P("pl_garage_mur", "Panneau mural", "pl_garage")
P("pl_garage_armoire", "Armoire à produits", "pl_garage")
P("pl_jardin", "Jardin", "pl_maison")
P("pl_abri", "Abri de jardin", "pl_jardin")
P("pl_abri_etagere", "Étagère de l'abri", "pl_abri")
P("pl_terrasse", "Terrasse", "pl_jardin")
P("pl_terrasse_coffre", "Coffre de terrasse", "pl_terrasse")
P("pl_voiture", "Voiture")
P("pl_voiture_coffre", "Coffre", "pl_voiture")
P("pl_voiture_boite_gants", "Boîte à gants", "pl_voiture")
P("pl_voiture_console", "Console centrale", "pl_voiture")
P("pl_voiture_portiere", "Poche de portière", "pl_voiture")
P("pl_rs", "Résidence secondaire")
P("pl_rs_cuisine", "Cuisine de la résidence secondaire", "pl_rs")
P("pl_rs_placard", "Placard du couloir", "pl_rs")
P("pl_rs_chambre", "Chambre bleue", "pl_rs")
P("pl_rs_cellier", "Cellier", "pl_rs")
P("pl_gm", "Garde-meuble")
P("pl_gm_box", "Box 214", "pl_gm")
P("pl_gm_etagere", "Étagère du box", "pl_gm_box")

C("ct_carton_7", "Carton 7", "pl_cave_etagere_fond")
C("ct_carton_3", "Carton 3", "pl_cave_etagere_fond")
C("ct_carton_ski", "Carton ski", "pl_cave_etagere_gauche")
C("ct_carton_5", "Carton 5", "pl_cave_etagere_gauche")
C("ct_bac_noel", "Bac Noël", "pl_grenier_etagere")
C("ct_malle_deguisements", "Malle déguisements", "pl_grenier_coin")
C("ct_valise_rouge", "Valise rouge", "pl_grenier_coin")
C("ct_valise_cabine", "Valise cabine", "pl_chambre_armoire")
C("ct_boite_couture", "Boîte à couture", "pl_chambre_armoire")
C("ct_boite_archives", "Boîte archives", "pl_bureau_etagere")
C("ct_caisse_outils", "Caisse à outils", "pl_garage_etabli")
C("ct_bac_bleu", "Bac bleu", "pl_garage_etagere")
C("ct_bac_lego", "Bac Lego", "pl_enfants_etagere")
C("ct_sac_plage", "Sac de plage", "pl_entree_placard")
C("ct_trousse_velo", "Trousse vélo", "pl_garage_mur")
C("ct_carton_20", "Carton 20", "pl_gm_etagere")
C("ct_carton_21", "Carton 21", "pl_gm_etagere")
C("ct_trousse_toilette", "Trousse de toilette", "pl_sdb_meuble")

# ---------------------------------------------------------------------------------------------------------
# Items - French household, part A: entrée, cuisine, salon, chambre, enfants
# ---------------------------------------------------------------------------------------------------------

# Entrée
I("item_cles_voiture", "Clés de la voiture", "pl_entree_vide_poches", r="clés de la voiture @ vide-poches")
I("item_cadenas_velo_cle", "Clé du cadenas de vélo", "pl_entree_vide_poches", t="vélo")
I("item_cle_boite_lettres", "Clé de la boîte aux lettres", "pl_entree_vide_poches", d="Petite clé plate avec un bout de scotch rouge")
I("item_badge_parking", "Badge du parking", "pl_entree_vide_poches", d="Badge noir qui ouvre la barrière")
I("item_parapluie", "Parapluie", "pl_entree_placard", r="j'ai rangé le parapluie dans le placard de l'entrée")
I("item_laisse_chien", "Laisse du chien", "pl_entree_placard", t="chien")
I("item_lampe_torche", "Lampe torche", "pl_entree_placard", d="Rechargeable, avec une dragonne")
I("item_echarpes_bonnets", "Écharpes et bonnets", "pl_entree_placard", t="hiver")
I("item_sacs_courses", "Sacs de courses en tissu", "pl_entree_placard")
I("item_bottes_pluie", "Bottes de pluie", "pl_entree_meuble_chaussures")
I("item_baskets_course", "Baskets de course", "pl_entree_meuble_chaussures", t="sport")
I("item_cirage", "Cirage noir", "pl_entree_meuble_chaussures")
I("item_chausse_pied", "Chausse-pied", "pl_entree_meuble_chaussures")
I("item_serviettes_plage", "Serviettes de plage", "ct_sac_plage", t="été")
I("item_creme_solaire", "Crème solaire indice 50", "ct_sac_plage", t="été")
I("item_masque_tuba", "Masque et tuba", "ct_sac_plage")

# Cuisine
I("item_tire_bouchon", "Tire-bouchon", "pl_cuisine_tiroir_bazar", r="le tire-bouchon est dans le tiroir du bazar")
I("item_ouvre_boite", "Ouvre-boîte", "pl_cuisine_tiroir_bazar")
I("item_ciseaux_cuisine", "Ciseaux de cuisine", "pl_cuisine_tiroir_couverts")
I("item_econome", "Économe", "pl_cuisine_tiroir_couverts")
I("item_couteau_pain", "Couteau à pain", "pl_cuisine_tiroir_couverts", d="Lame crantée, manche en bois")
I("item_piles_aa", "Piles AA", "pl_cuisine_tiroir_bazar", d="Un paquet de 8 piles alcalines", t="piles", r="j'ai mis les piles AA dans le tiroir du bazar")
I("item_ficelle_cuisine", "Ficelle de cuisine", "pl_cuisine_tiroir_bazar")
I("item_papier_alu", "Papier aluminium", "pl_cuisine_tiroir_bazar")
I("item_sacs_congelation", "Sacs de congélation", "pl_cuisine_tiroir_bazar")
I("item_bougies_anniv", "Bougies d'anniversaire", "pl_cuisine_tiroir_bazar", t="fête")
I("item_allumettes", "Allumettes", "pl_cuisine_tiroir_bazar")
I("item_rouleau_patisserie", "Rouleau à pâtisserie", "pl_cuisine_placard_haut")
I("item_balance_cuisine", "Balance de cuisine", "pl_cuisine_placard_haut", d="Électronique, la pile bouton est à changer")
I("item_moule_manque", "Moule à manqué", "pl_cuisine_placard_haut", t="pâtisserie")
I("item_cafetiere_italienne", "Cafetière italienne", "pl_cuisine_placard_haut", d="Pour 6 tasses")
I("item_filtres_cafe", "Filtres à café", "pl_cuisine_placard_haut")
I("item_thermos", "Thermos", "pl_cuisine_placard_haut", d="Bouteille isotherme d'un litre")
I("item_mandoline", "Mandoline", "pl_cuisine_placard_haut")
I("item_cocotte_fonte", "Cocotte en fonte rouge", "pl_cuisine_placard_evier")
I("item_mixeur_plongeant", "Mixeur plongeant", "pl_cuisine_placard_evier", r="mixeur plongeant dans le placard sous l'évier")
I("item_robot_patissier", "Robot pâtissier", "pl_cuisine_placard_evier", d="Avec le fouet et le crochet pétrisseur")
I("item_passoire", "Passoire", "pl_cuisine_placard_evier")
I("item_pastilles_lv", "Pastilles lave-vaisselle", "pl_cuisine_placard_evier")
I("item_eponges", "Éponges", "pl_cuisine_placard_evier")
I("item_gants_vaisselle", "Gants de vaisselle jaunes", "pl_cuisine_placard_evier")
I("item_the_vert", "Thé vert", "pl_cuisine_garde_manger", t="thé")
I("item_levure", "Levure de boulanger", "pl_cuisine_garde_manger")
I("item_sucre_vanille", "Sucre vanillé", "pl_cuisine_garde_manger")

# Salon
I("item_telecommande_tv", "Télécommande de la télé", "pl_salon_meuble_tv", r="télécommande de la télé @ meuble tv", days=3)
I("item_cable_hdmi", "Câble HDMI", "pl_salon_meuble_tv", d="3 mètres")
I("item_manettes", "Manettes de la console", "pl_salon_meuble_tv", t="jeux")
I("item_jeu_cartes", "Jeu de 54 cartes", "pl_salon_buffet")
I("item_puzzle", "Puzzle 1000 pièces", "pl_salon_buffet", d="Paysage de montagne, il manque peut-être une pièce")
I("item_nappes", "Nappes en lin", "pl_salon_buffet")
I("item_bougeoirs", "Bougeoirs en laiton", "pl_salon_buffet")
I("item_catan", "Jeu Catan", "pl_salon_buffet", t="jeux", st="LENT", lent="Antoine", days=45, r="j'ai prêté Catan à Antoine")
I("item_albums_photo", "Albums photo", "pl_salon_bibliotheque", t="souvenirs")
I("item_dictionnaire", "Dictionnaire Larousse", "pl_salon_bibliotheque")
I("item_livres_cuisine", "Livres de cuisine", "pl_salon_bibliotheque")
I("item_guide_portugal", "Guide de voyage Portugal", "pl_salon_bibliotheque")
I("item_livre_dune", "Roman Dune", "pl_salon_bibliotheque", st="LENT", lent="Léa", days=70)
I("item_plaid", "Plaid en laine", "pl_salon_table_basse")
I("item_magazines_deco", "Magazines de déco", "pl_salon_table_basse")

# Chambre
I("item_passeport", "Passeport", "pl_chambre_commode_tiroir_haut", t="papiers", r="passeport dans le tiroir du haut de la commode", days=40)
I("item_carnets_sante", "Carnets de santé", "pl_chambre_commode_tiroir_haut", t="papiers,santé")
I("item_livret_famille", "Livret de famille", "pl_chambre_commode_tiroir_haut", t="papiers")
I("item_maillots_bain", "Maillots de bain", "pl_chambre_commode_tiroir_bas", t="été")
I("item_bijoux_boite", "Boîte à bijoux", "pl_chambre_commode_tiroir_bas", d="Boîte en bois avec les boucles d'oreilles et les colliers", sens=True)
I("item_pull_gris", "Pull en laine gris", "pl_chambre_armoire", t="hiver")
I("item_manteau_hiver", "Manteau d'hiver", "pl_chambre_armoire")
I("item_cravates", "Cravates", "pl_chambre_armoire")
I("item_ceinture_cuir", "Ceinture en cuir", "pl_chambre_armoire")
I("item_robe_soiree", "Robe de soirée", "pl_chambre_armoire", d="Robe longue noire, sous housse")
I("item_couette_ete", "Couette d'été", "pl_chambre_armoire", t="linge")
I("item_draps_140", "Draps housse 140", "pl_chambre_armoire", t="linge")
I("item_boules_quies", "Boules Quies", "pl_chambre_table_nuit")
I("item_lunettes_lecture", "Lunettes de lecture", "pl_chambre_table_nuit", r="j'ai posé mes lunettes de lecture sur la table de nuit", days=2)
I("item_reveil", "Réveil", "pl_chambre_table_nuit")
I("item_cable_lightning", "Câble Lightning", "pl_chambre_table_nuit", d="Pour l'ancien iPhone")
I("item_melatonine", "Mélatonine", "pl_chambre_table_nuit", t="santé")
I("item_aiguilles_fil", "Aiguilles et fil", "ct_boite_couture")
I("item_boutons_rechange", "Boutons de rechange", "ct_boite_couture")
I("item_ruban_thermocollant", "Ruban thermocollant", "ct_boite_couture", d="Pour les ourlets de pantalons")
I("item_ciseaux_tissu", "Ciseaux à tissu", "ct_boite_couture", d="Ne servent qu'au tissu !")
I("item_metre_couturiere", "Mètre de couturière", "ct_boite_couture")
I("item_adaptateur_uk", "Adaptateur de prise anglais", "ct_valise_cabine", t="voyage", r="adaptateur de prise anglais dans la valise cabine")
I("item_cadenas_code", "Cadenas à code", "ct_valise_cabine", t="voyage")
I("item_etiquettes_bagage", "Étiquettes de bagage", "ct_valise_cabine", t="voyage")
I("item_coussin_voyage", "Coussin de voyage", "ct_valise_cabine", t="voyage")

# Chambre des enfants
I("item_doudou", "Doudou lapin", "pl_enfants_coffre", d="Le préféré, un peu usé", t="bébé", r="doudou lapin dans le coffre à jouets")
I("item_tetines", "Tétines de rechange", "pl_enfants_coffre", t="bébé")
I("item_babyphone", "Babyphone", "pl_enfants_coffre", t="bébé", d="Le récepteur et l'émetteur avec leur chargeur")
I("item_pate_modeler", "Pâte à modeler", "pl_enfants_etagere")
I("item_feutres", "Feutres", "pl_enfants_etagere")
I("item_albums_enfants", "Albums pour enfants", "pl_enfants_etagere")
I("item_cartable", "Cartable", "pl_enfants_etagere")
I("item_briques_lego", "Briques Lego", "ct_bac_lego", t="jeux")
I("item_plaques_lego", "Plaques Lego vertes", "ct_bac_lego")
I("item_notices_lego", "Notices de montage Lego", "ct_bac_lego", d="Pliées dans une pochette plastique")
I("item_figurines_pirates", "Figurines de pirates", "ct_bac_lego", t="jeux")
I("item_costume_pirate", "Costume de pirate", "ct_malle_deguisements", t="fête")
I("item_cape_superheros", "Cape de super-héros", "ct_malle_deguisements")
I("item_perruque", "Perruque arc-en-ciel", "ct_malle_deguisements", t="fête")
I("item_masque_loup", "Masque de loup", "ct_malle_deguisements")
I("item_chapeau_sorciere", "Chapeau de sorcière", "ct_malle_deguisements", t="Halloween")
I("item_vetements_bebe", "Vêtements bébé 6 mois", "ct_valise_rouge", t="bébé")
I("item_sacs_sous_vide", "Sacs sous vide", "ct_valise_rouge")
I("item_gigoteuse", "Gigoteuse", "ct_valise_rouge", t="bébé")

# ---------------------------------------------------------------------------------------------------------
# Items - French household, part B: bureau, salle de bain, buanderie, cave, grenier
# ---------------------------------------------------------------------------------------------------------

# Bureau
I("item_cable_usbc_blanc", "Câble USB-C blanc", "pl_bureau_tiroir", d="1 mètre", r="câble usb-c blanc dans le tiroir du bureau")
I("item_cable_micro_usb", "Câble micro-USB", "pl_bureau_tiroir")
I("item_chargeur_pc", "Chargeur du PC portable", "pl_bureau_tiroir", d="Dell 65 W")
I("item_cle_usb", "Clé USB 64 Go", "pl_bureau_tiroir")
I("item_carte_sd", "Carte SD 32 Go", "pl_bureau_tiroir")
I("item_piles_aaa", "Piles AAA", "pl_bureau_tiroir", t="piles")
I("item_piles_bouton", "Piles bouton CR2032", "pl_bureau_tiroir", t="piles", d="Pour la télécommande de la voiture et la balance")
I("item_agrafeuse", "Agrafeuse", "pl_bureau_tiroir", r="euh l'agrafeuse est dans le tiroir du bureau")
I("item_ruban_adhesif", "Ruban adhésif", "pl_bureau_tiroir")
I("item_trombones", "Trombones", "pl_bureau_tiroir")
I("item_timbres", "Carnet de timbres", "pl_bureau_tiroir")
I("item_enveloppes", "Enveloppes", "pl_bureau_tiroir")
I("item_calculatrice", "Calculatrice", "pl_bureau_tiroir")
I("item_carnet_adresses", "Carnet d'adresses", "pl_bureau_tiroir")
I("item_post_it", "Post-it", "pl_bureau_tiroir")
I("item_multiprise", "Multiprise parafoudre", "pl_bureau_etagere")
I("item_disque_dur", "Disque dur externe 2 To", "pl_bureau_etagere", d="Sauvegarde des photos de famille", t="photos")
I("item_cartouches_encre", "Cartouches d'encre", "pl_bureau_etagere", d="Noir et couleur, pour l'imprimante Epson")
I("item_ramettes", "Ramettes de papier A4", "pl_bureau_etagere")
I("item_appareil_photo", "Appareil photo reflex", "pl_bureau_etagere", st="TAKEN", days=6, r="je prends l'appareil photo")
I("item_avis_imposition", "Avis d'imposition", "pl_bureau_classeur", t="papiers,impôts")
I("item_factures_edf", "Factures d'électricité", "pl_bureau_classeur", t="papiers")
I("item_assurance_habitation", "Contrat d'assurance habitation", "pl_bureau_classeur", t="papiers")
I("item_garanties", "Garanties électroménager", "pl_bureau_classeur", t="papiers")
I("item_releves_bancaires", "Relevés bancaires", "ct_boite_archives", t="papiers")
I("item_diplomes", "Diplômes", "ct_boite_archives", t="papiers")
I("item_bulletins_salaire", "Bulletins de salaire", "ct_boite_archives", t="papiers")
I("item_cartes_voeux", "Cartes de vœux", "ct_boite_archives")
I("item_acte_naissance", "Acte de naissance", "pl_bureau_coffre", t="papiers", sens=True)
I("item_double_cles", "Double des clés de la maison", "pl_bureau_coffre", sens=True)
I("item_bague_mamie", "Bague de fiançailles de mamie", "pl_bureau_coffre", sens=True)
I("item_carnet_mdp", "Carnet de mots de passe", "pl_bureau_coffre", sens=True)
I("item_vieux_nokia", "Vieux téléphone Nokia", "pl_bureau_tiroir", arch=True)
I("item_ancien_disque_dur", "Ancien disque dur 500 Go", "pl_bureau_etagere", arch=True)

# Salle de bain
I("item_seche_cheveux", "Sèche-cheveux", "pl_sdb_meuble", r="sèche-cheveux dans le meuble sous la vasque")
I("item_rasoir_electrique", "Rasoir électrique", "pl_sdb_meuble")
I("item_serviettes_bain", "Serviettes de bain", "pl_sdb_meuble")
I("item_papier_toilette", "Papier toilette", "pl_sdb_meuble")
I("item_brosses_dents", "Brosses à dents de rechange", "pl_sdb_meuble")
I("item_rasoir_voyage", "Rasoir de voyage", "ct_trousse_toilette", t="voyage")
I("item_brosse_dents_pliable", "Brosse à dents pliable", "ct_trousse_toilette", t="voyage")
I("item_flacons_voyage", "Flacons de voyage", "ct_trousse_toilette", t="voyage")
I("item_paracetamol", "Paracétamol", "pl_sdb_pharmacie", d="Boîte de 16 comprimés", t="santé")
I("item_pansements", "Pansements", "pl_sdb_pharmacie", t="santé")
I("item_thermometre_frontal", "Thermomètre frontal", "pl_sdb_pharmacie", t="santé")
I("item_sirop_toux", "Sirop contre la toux", "pl_sdb_pharmacie", t="santé")
I("item_serum_physio", "Sérum physiologique", "pl_sdb_pharmacie", t="bébé")
I("item_desinfectant", "Désinfectant", "pl_sdb_pharmacie")
I("item_creme_arnica", "Crème à l'arnica", "pl_sdb_pharmacie")
I("item_tensiometre", "Tensiomètre", "pl_sdb_pharmacie", d="Au poignet, avec sa pochette")
I("item_antihistaminiques", "Antihistaminiques", "pl_sdb_pharmacie", t="santé,allergie")
I("item_pince_epiler", "Pince à épiler", "pl_sdb_pharmacie")
I("item_ordonnances", "Ordonnances en cours", "pl_sdb_pharmacie", t="santé")

# Buanderie
I("item_lessive", "Lessive", "pl_buanderie_placard")
I("item_adoucissant", "Adoucissant", "pl_buanderie_placard")
I("item_fer_repasser", "Fer à repasser", "pl_buanderie_placard")
I("item_table_repasser", "Table à repasser", "pl_buanderie_placard")
I("item_pinces_linge", "Pinces à linge", "pl_buanderie_placard")
I("item_detachant", "Détachant", "pl_buanderie_placard")
I("item_sacs_aspirateur", "Sacs d'aspirateur", "pl_buanderie_placard")
I("item_ampoules", "Ampoules de rechange", "pl_buanderie_placard", d="Culot E27 et GU10")
I("item_cintres", "Cintres", "pl_buanderie_placard")
I("item_produit_vitres", "Produit pour les vitres", "pl_buanderie_placard")
I("item_serpilliere", "Serpillère", "pl_buanderie_placard")

# Cave
I("item_bordeaux", "Bouteilles de bordeaux", "pl_cave_casier_vin", t="vin")
I("item_champagne", "Champagne", "pl_cave_casier_vin", t="fête", d="Deux bouteilles pour les occasions")
I("item_bocaux_vides", "Bocaux vides", "pl_cave_etagere_fond")
I("item_confiture_fraises", "Confiture de fraises", "pl_cave_etagere_fond", t="maison")
I("item_conserves_tomates", "Conserves de tomates", "pl_cave_etagere_fond")
I("item_appareil_raclette", "Appareil à raclette", "ct_carton_7", t="hiver", r="appareil à raclette dans le carton 7")
I("item_service_fondue", "Service à fondue", "ct_carton_7", t="hiver")
I("item_flutes_champagne", "Flûtes à champagne", "ct_carton_3", d="Six flûtes, emballées dans du papier bulle")
I("item_plateau_service", "Plateau de service", "ct_carton_3")
I("item_peinture_blanche", "Pot de peinture blanche", "pl_cave_etagere_gauche", d="Mat, il en reste la moitié")
I("item_rouleau_peinture", "Rouleau de peinture", "pl_cave_etagere_gauche")
I("item_pinceaux", "Pinceaux", "pl_cave_etagere_gauche")
I("item_bache_protection", "Bâche de protection", "pl_cave_etagere_gauche")
I("item_tente_4p", "Tente 4 places", "pl_cave_etagere_gauche", st="LENT", lent="Marc", days=21, t="camping", r="j'ai prêté la tente à Marc")
I("item_sacs_couchage", "Sacs de couchage", "pl_cave_etagere_gauche", t="camping")
I("item_matelas_gonflable", "Matelas gonflable", "pl_cave_etagere_gauche", t="camping")
I("item_rechaud_camping", "Réchaud de camping", "pl_cave_etagere_gauche", t="camping")
I("item_lampe_frontale", "Lampe frontale", "pl_cave_etagere_gauche", t="camping")
I("item_sac_dos_rando", "Sac à dos de randonnée", "pl_cave_etagere_gauche", st="TAKEN", days=9, r="je prends le sac à dos de randonnée")
I("item_batons_marche", "Bâtons de marche", "pl_cave_etagere_gauche")
I("item_raquettes_tennis", "Raquettes de tennis", "pl_cave_etagere_gauche", t="sport")
I("item_gants_ski", "Gants de ski", "ct_carton_ski", t="ski", r="les gants de ski sont dans le carton ski")
I("item_casque_ski", "Casque de ski", "ct_carton_ski", t="ski")
I("item_masque_ski", "Masque de ski", "ct_carton_ski", t="ski")
I("item_chaussettes_ski", "Chaussettes de ski", "ct_carton_ski", t="ski")

# Grenier
I("item_guirlandes", "Guirlandes lumineuses", "ct_bac_noel", t="Noël", r="guirlandes lumineuses dans le bac Noël")
I("item_boules_noel", "Boules de sapin", "ct_bac_noel", t="Noël")
I("item_creche", "Crèche en bois", "ct_bac_noel", t="Noël")
I("item_etoile_sapin", "Étoile du sapin", "ct_bac_noel")
I("item_sapin_artificiel", "Sapin artificiel", "pl_grenier_coin", t="Noël", d="Deux mètres, en trois morceaux")
I("item_disques_vinyles", "Disques vinyles", "pl_grenier_etagere", t="musique")
I("item_machine_coudre", "Machine à coudre", "pl_grenier_etagere", d="Héritée de ma grand-mère, dans sa valise")
I("item_ventilateur", "Ventilateur", "pl_grenier_coin")
I("item_chauffage_appoint", "Chauffage d'appoint", "pl_grenier_coin")
I("item_berceau", "Berceau", "pl_grenier_coin", t="bébé")
I("item_poussette", "Poussette", "pl_grenier_coin", t="bébé", arch=True)
I("item_cartons_demenagement", "Cartons de déménagement vides", "pl_grenier_coin")
I("item_velo_appartement", "Vélo d'appartement", "pl_grenier_coin", t="sport")
I("item_magnetoscope", "Magnétoscope", "pl_grenier_etagere", arch=True)

# ---------------------------------------------------------------------------------------------------------
# Items - French household, part C: garage, jardin, voiture, résidence secondaire, garde-meuble
# ---------------------------------------------------------------------------------------------------------

# Garage - établi
I("item_perceuse_bosch", "Perceuse Bosch", "pl_garage_etabli", d="Filaire, à percussion, dans sa mallette bleue", r="perceuse Bosch dans le garage sur l'établi")
I("item_visseuse_makita", "Visseuse Makita sans fil", "pl_garage_etabli", d="Deux batteries et un chargeur", st="TAKEN", days=4, r="je prends la visseuse")
I("item_marteau", "Marteau", "pl_garage_etabli", r="le marteau est sur l'établi du garage")
I("item_cle_molette", "Clé à molette", "pl_garage_etabli", r="clé à molette sur l'établi")
I("item_pince_multiprise", "Pince multiprise", "pl_garage_etabli")
I("item_scie_sauteuse", "Scie sauteuse", "pl_garage_etabli")
I("item_niveau_bulle", "Niveau à bulle", "pl_garage_etabli")
I("item_metre_ruban", "Mètre ruban", "pl_garage_etabli", d="5 mètres, jaune")
I("item_scie_metaux", "Scie à métaux", "pl_garage_etabli")
I("item_ponceuse", "Ponceuse excentrique", "pl_garage_etabli", st="TAKEN", days=12)
I("item_cutter", "Cutter", "pl_garage_etabli")
I("item_colle_forte", "Colle forte", "pl_garage_etabli")
I("item_ruban_isolant", "Ruban isolant", "pl_garage_etabli", t="électricité")
I("item_testeur_tension", "Testeur de tension", "pl_garage_etabli", t="électricité")
I("item_rallonge", "Rallonge électrique 10 m", "pl_garage_etabli", t="électricité")
I("item_fil_electrique", "Fil électrique 1,5 mm", "pl_garage_etabli", t="électricité", d="Une couronne de 25 mètres, bleue")
I("item_forets_beton", "Forets à béton", "pl_garage_etabli", d="Du 6 au 10 mm")
I("item_meches_bois", "Mèches à bois", "pl_garage_etabli")
I("item_cles_allen", "Clés Allen", "ct_caisse_outils")
I("item_cles_pipe", "Clés à pipe", "ct_caisse_outils")
I("item_jeu_tournevis", "Jeu de tournevis", "ct_caisse_outils", r="jeu de tournevis dans la caisse à outils")
I("item_pince_coupante", "Pince coupante", "ct_caisse_outils")
I("item_vis_m4", "12 vis M4", "ct_bac_bleu", r="12 vis M4 dans le bac bleu")
I("item_vis_bois", "Vis à bois 4x40", "ct_bac_bleu", d="Boîte de 200")
I("item_boulons_m6", "Boulons M6 avec écrous", "ct_bac_bleu")
I("item_chevilles", "Chevilles de 8", "ct_bac_bleu")
I("item_clous", "Clous", "ct_bac_bleu")
I("item_rondelles", "Rondelles", "ct_bac_bleu")

# Garage - étagère, panneau, armoire
I("item_vis_placo", "Vis à placo", "pl_garage_etagere", d="Une grosse boîte, vis noires")
I("item_huile_moteur", "Huile moteur 5W30", "pl_garage_etagere")
I("item_lave_glace", "Bidon de lave-glace", "pl_garage_etagere")
I("item_bidon_essence", "Bidon d'essence", "pl_garage_etagere", d="5 litres, pour la tondeuse")
I("item_gonfleur", "Gonfleur électrique", "pl_garage_etagere")
I("item_rustines", "Rustines", "ct_trousse_velo", t="vélo")
I("item_demonte_pneus", "Démonte-pneus", "ct_trousse_velo", t="vélo")
I("item_chambre_air", "Chambre à air 700", "ct_trousse_velo", t="vélo")
I("item_casques_velo", "Casques de vélo", "pl_garage_mur", t="vélo")
I("item_corde", "Corde", "pl_garage_mur")
I("item_sangles", "Sangles d'arrimage", "pl_garage_mur")
I("item_echelle", "Échelle télescopique", "pl_garage_mur", st="LENT", lent="Julien", days=33, r="j'ai prêté l'échelle à Julien")
I("item_white_spirit", "White-spirit", "pl_garage_armoire")
I("item_bombe_noire", "Peinture en bombe noire", "pl_garage_armoire")
I("item_wd40", "WD-40", "pl_garage_armoire")
I("item_cire_voiture", "Cire pour voiture", "pl_garage_armoire")
I("item_chiffons", "Chiffons", "pl_garage_armoire")
I("item_colle_bois", "Colle à bois", "pl_garage_armoire")
I("item_nettoyeur_hp", "Nettoyeur haute pression", "pl_garage_armoire", st="LENT", lent="Sophie", days=58)

# Jardin
I("item_gants_jardin", "Gants de jardinage", "pl_abri_etagere", r="gants de jardinage dans l'abri")
I("item_secateur", "Sécateur", "pl_abri_etagere")
I("item_arrosoir", "Arrosoir", "pl_abri_etagere")
I("item_graines_tomates", "Graines de tomates", "pl_abri_etagere", t="potager")
I("item_engrais", "Engrais", "pl_abri_etagere", t="potager")
I("item_pulverisateur", "Pulvérisateur", "pl_abri_etagere")
I("item_tondeuse", "Tondeuse", "pl_abri", d="Thermique, à tracter")
I("item_taille_haie", "Taille-haie", "pl_abri")
I("item_tuyau_arrosage", "Tuyau d'arrosage", "pl_abri", d="25 mètres, sur son dévidoir")
I("item_rateau", "Râteau", "pl_abri")
I("item_beche", "Bêche", "pl_abri")
I("item_terreau", "Sac de terreau", "pl_abri", t="potager")
I("item_brouette", "Brouette", "pl_abri")
I("item_coussins_salon_jardin", "Coussins du salon de jardin", "pl_terrasse_coffre")
I("item_pinces_barbecue", "Pinces à barbecue", "pl_terrasse_coffre", t="barbecue")
I("item_allume_feu", "Allume-feu", "pl_terrasse_coffre", t="barbecue")
I("item_charbon", "Charbon de bois", "pl_terrasse_coffre", t="barbecue")
I("item_boules_petanque", "Boules de pétanque", "pl_terrasse_coffre", t="été")
I("item_parasol", "Parasol", "pl_terrasse_coffre", t="été")

# Voiture
I("item_carte_grise", "Carte grise", "pl_voiture_boite_gants", t="papiers")
I("item_attestation_auto", "Attestation d'assurance auto", "pl_voiture_boite_gants", t="papiers")
I("item_carnet_entretien", "Carnet d'entretien", "pl_voiture_boite_gants")
I("item_lingettes", "Lingettes", "pl_voiture_boite_gants")
I("item_cable_usbc_noir", "Câble USB-C noir 2 m", "pl_voiture_console")
I("item_monnaie_parking", "Pièces pour le parking", "pl_voiture_console")
I("item_lunettes_soleil", "Lunettes de soleil", "pl_voiture_console", r="lunettes de soleil @ console de la voiture")
I("item_chargeur_allume_cigare", "Chargeur allume-cigare", "pl_voiture_console")
I("item_triangle", "Triangle de signalisation", "pl_voiture_coffre")
I("item_cables_demarrage", "Câbles de démarrage", "pl_voiture_coffre")
I("item_cric", "Cric", "pl_voiture_coffre")
I("item_grattoir_glace", "Grattoir à glace", "pl_voiture_coffre", t="hiver")
I("item_chaines_neige", "Chaînes à neige", "pl_voiture_coffre", t="hiver")
I("item_couverture_pique_nique", "Couverture de pique-nique", "pl_voiture_coffre", t="été")
I("item_gilets_jaunes", "Gilets jaunes", "pl_voiture_portiere")
I("item_parapluie_pliant", "Parapluie pliant", "pl_voiture_portiere", d="Petit, noir, tient dans la poche")
I("item_carte_routiere", "Carte routière", "pl_voiture_portiere")

# Résidence secondaire
I("item_cafetiere_filtre", "Cafetière à filtre", "pl_rs_cuisine")
I("item_planche_decouper", "Planche à découper", "pl_rs_cuisine")
I("item_moulin_poivre", "Moulin à poivre", "pl_rs_cuisine")
I("item_draps_campagne", "Draps de la maison de campagne", "pl_rs_placard", t="linge")
I("item_plaids_polaire", "Plaids en polaire", "pl_rs_placard")
I("item_bouillotte", "Bouillotte", "pl_rs_chambre")
I("item_jumelles", "Jumelles", "pl_rs_chambre", st="TAKEN", days=15)
I("item_reserve_bougies", "Réserve de bougies", "pl_rs_cellier")
I("item_cle_portail", "Clé du portail", "pl_rs_cellier", d="Pendue à un clou, derrière la porte")
I("item_lampe_tempete", "Lampe tempête", "pl_rs_cellier")

# Garde-meuble
I("item_vaisselle_mariage", "Vaisselle de mariage", "ct_carton_20", d="Douze assiettes plates et douze creuses")
I("item_service_verres", "Service de verres", "ct_carton_20")
I("item_livres_droit", "Livres de droit", "ct_carton_21", t="livres")
I("item_cadres_photos", "Cadres photos", "ct_carton_21")
I("item_lit_bebe", "Lit bébé démonté", "pl_gm_box", t="bébé")
I("item_tapis_salon", "Tapis du salon", "pl_gm_box", d="Roulé dans une housse")
I("item_velo_enfant", "Petit vélo d'enfant", "pl_gm_box")
I("item_machine_expresso", "Ancienne machine à expresso", "pl_gm_box")

# ---------------------------------------------------------------------------------------------------------
# Items - French household, part D: more everyday things (near-duplicates by place or attribute)
# ---------------------------------------------------------------------------------------------------------

I("item_agrafeuse_murale", "Agrafeuse murale", "pl_garage_etabli", d="Pour les tasseaux et la toile de la terrasse")
I("item_pistolet_silicone", "Pistolet à silicone", "pl_garage_etabli")
I("item_serre_joints", "Serre-joints", "pl_garage_etabli")
I("item_equerre", "Équerre", "pl_garage_etabli")
I("item_escabeau", "Escabeau", "pl_garage_mur")
I("item_trottinette", "Trottinette", "pl_garage_mur", t="enfants")
I("item_ballon_foot", "Ballon de foot", "pl_terrasse_coffre", t="sport")
I("item_raquettes_badminton", "Raquettes de badminton", "pl_terrasse_coffre", t="sport")
I("item_cerf_volant", "Cerf-volant", "pl_terrasse_coffre")
I("item_presse_agrumes", "Presse-agrumes", "pl_cuisine_placard_haut")
I("item_fouet", "Fouet à main", "pl_cuisine_tiroir_couverts")
I("item_louche", "Louche", "pl_cuisine_tiroir_couverts")
I("item_cuit_vapeur", "Cuit-vapeur", "pl_cuisine_placard_evier")
I("item_sacs_poubelle", "Sacs poubelle 50 L", "pl_cuisine_placard_evier")
I("item_biberons", "Biberons", "pl_cuisine_placard_haut", t="bébé")
I("item_papier_cadeau", "Papier cadeau", "pl_chambre_armoire", t="fête")
I("item_album_mariage", "Album de mariage", "pl_salon_bibliotheque", t="souvenirs")
I("item_tablette", "Tablette Android", "pl_salon_table_basse", d="Celle des enfants, avec une coque verte")
I("item_decodeur_tv", "Décodeur TV", "pl_salon_meuble_tv")
I("item_cable_ethernet", "Câble Ethernet 5 m", "pl_salon_meuble_tv")
I("item_routeur_secours", "Routeur wifi de secours", "pl_bureau_etagere")
I("item_telecommande_portail", "Télécommande du portail", "pl_voiture_console", d="Bouton de gauche pour le garage")
I("item_couteau_suisse", "Couteau suisse", "pl_entree_placard", d="Le rouge, avec le tire-bouchon cassé")
I("item_bottes_jardin", "Bottes de jardin", "pl_abri")
I("item_piquets_tomates", "Piquets à tomates", "pl_abri", t="potager")
I("item_filet_oiseaux", "Filet anti-oiseaux", "pl_abri", t="potager")
I("item_gourde", "Gourde", "pl_entree_placard", t="sport")
I("item_sac_sport", "Sac de sport", "pl_entree_meuble_chaussures", t="sport")
I("item_masques_chirurgicaux", "Masques chirurgicaux", "pl_sdb_pharmacie")
I("item_gel_hydro", "Gel hydroalcoolique", "pl_sdb_pharmacie")
I("item_collyre", "Collyre", "pl_sdb_pharmacie", t="santé")
I("item_cle_cave", "Clé de la cave", "pl_entree_vide_poches")
I("item_cle_garage", "Clé du garage", "pl_entree_vide_poches", d="Sur le porte-clés vert")
I("item_balai", "Balai", "pl_buanderie_placard")
I("item_pelle_balayette", "Pelle et balayette", "pl_buanderie_placard")
I("item_lecteur_mp3", "Ancien lecteur MP3", "pl_bureau_tiroir", arch=True)
I("item_sorbetiere", "Sorbetière", "ct_carton_3", t="été")

# ---------------------------------------------------------------------------------------------------------
# Items - English-named things of a bilingual household
# ---------------------------------------------------------------------------------------------------------

I("item_en_headphones", "Noise-cancelling headphones", "pl_bureau_tiroir", d="Black, with the carrying case")
I("item_en_wireless_mouse", "Wireless mouse", "pl_bureau_tiroir", r="wireless mouse in the desk drawer")
I("item_en_usbc_hdmi", "USB-C to HDMI adapter", "pl_bureau_tiroir")
I("item_en_webcam", "Webcam", "pl_bureau_tiroir")
I("item_en_laptop_stand", "Laptop stand", "pl_bureau_etagere", d="Folds flat")
I("item_en_power_bank", "Power bank", "pl_bureau_etagere", d="20000 mAh")
I("item_en_bluetooth_speaker", "Bluetooth speaker", "pl_salon_buffet")
I("item_en_watch_charger", "Apple Watch charger", "pl_chambre_table_nuit")
I("item_en_phone_tripod", "Phone tripod", "pl_bureau_etagere", d="Small, bendy legs")
I("item_en_soldering_iron", "Soldering iron", "pl_garage_etabli")
I("item_en_staple_gun", "Staple gun", "pl_garage_etabli")
I("item_en_sandpaper", "Sandpaper", "pl_garage_etabli", d="Assorted grits in a plastic folder")
I("item_en_duct_tape", "Duct tape", "pl_garage_etabli")
I("item_en_jigsaw_blades", "Jigsaw blades", "pl_garage_etabli")
I("item_en_bike_pump", "Bike pump", "pl_garage_etagere", t="vélo")
I("item_en_dumbbells", "Dumbbells", "pl_garage_etagere", t="sport")
I("item_en_hedge_shears", "Hedge shears", "pl_abri_etagere")
I("item_en_hose_nozzle", "Hose nozzle", "pl_abri_etagere")
I("item_en_weed_killer", "Weed killer", "pl_abri_etagere")
I("item_en_garden_twine", "Garden twine", "pl_abri_etagere")
I("item_en_bird_feeder", "Bird feeder", "pl_abri")
I("item_en_plant_pots", "Terracotta plant pots", "pl_abri")
I("item_en_kneeling_pad", "Garden kneeling pad", "pl_abri", r="garden kneeling pad in the shed")
I("item_en_harry_potter", "Harry Potter box set", "ct_carton_5", t="books")
I("item_en_christie", "Agatha Christie paperbacks", "ct_carton_5", t="books")
I("item_en_oxford_dict", "Oxford English dictionary", "ct_carton_5", t="books")
I("item_en_penguin_classics", "Penguin classics", "ct_carton_5", t="books")
I("item_en_jamie_oliver", "Jamie Oliver cookbook", "ct_carton_5", t="books")
I("item_en_baby_bath", "Baby bath", "pl_grenier_coin", t="bébé")
I("item_en_teething_rings", "Teething rings", "pl_enfants_coffre", t="bébé")
I("item_en_train_set", "Wooden train set", "pl_enfants_coffre", d="Track, two engines and a bridge")
I("item_en_night_light", "Night light", "pl_enfants_etagere")
I("item_en_bedtime_books", "Bedtime story books", "pl_enfants_etagere")
I("item_en_colouring_pencils", "Colouring pencils", "pl_enfants_etagere")
I("item_en_swim_armbands", "Swim armbands", "ct_sac_plage", t="été")
I("item_en_scooter_helmet", "Scooter helmet", "pl_garage_mur", t="enfants")
I("item_en_kids_rain_boots", "Kids' rain boots", "pl_entree_meuble_chaussures")
I("item_en_raincoat", "Yellow raincoat", "pl_entree_placard")
I("item_en_council_tax", "Council tax bill", "pl_bureau_classeur", t="papiers")
I("item_en_mortgage", "Mortgage documents", "pl_bureau_classeur", t="papiers")
I("item_en_dishwasher_warranty", "Dishwasher warranty card", "pl_bureau_classeur", t="papiers")
I("item_en_vaccination", "Vaccination records", "ct_boite_archives", t="papiers")
I("item_en_yoga_mat", "Yoga mat", "pl_chambre_armoire", t="sport")
I("item_en_gift_bags", "Gift bags", "pl_chambre_armoire", t="fête")
I("item_en_cheese_grater", "Cheese grater", "pl_cuisine_tiroir_couverts")
I("item_en_measuring_jug", "Measuring jug", "pl_cuisine_placard_haut")
I("item_en_tea_strainer", "Tea strainer", "pl_cuisine_tiroir_bazar", t="thé")
I("item_en_slow_cooker", "Slow cooker", "pl_cuisine_placard_evier")
I("item_en_waffle_iron", "Waffle iron", "pl_cuisine_placard_evier")
I("item_en_muffin_tin", "Muffin tin", "pl_cuisine_placard_haut", t="pâtisserie")
I("item_en_pizza_cutter", "Pizza cutter", "pl_cuisine_tiroir_couverts")
I("item_en_picnic_basket", "Picnic basket", "pl_voiture_coffre", t="été")
I("item_en_frisbee", "Frisbee", "pl_terrasse_coffre")
I("item_en_swingball", "Swingball set", "pl_terrasse_coffre", d="Pole, ball and two rackets")
I("item_en_cat_carrier", "Cat carrier", "pl_cave_etagere_gauche", t="chat")
I("item_en_dog_treats", "Dog treats", "pl_entree_placard", t="chien")
I("item_en_cat_litter", "Cat litter", "pl_buanderie_placard", t="chat")
I("item_en_christmas_wrap", "Christmas wrapping paper", "ct_bac_noel", t="Noël")
I("item_en_pumpkin_kit", "Pumpkin carving kit", "ct_malle_deguisements", t="Halloween")
I("item_en_hiking_socks", "Hiking socks", "pl_cave_etagere_gauche", t="camping")
I("item_en_waterproof_jacket", "Waterproof jacket", "pl_cave_etagere_gauche", t="camping")

# ---------------------------------------------------------------------------------------------------------
# Free-text descriptions and tags added to existing items (kept apart to keep the item lines short)
# ---------------------------------------------------------------------------------------------------------

DESC = {
    "item_tire_bouchon": "Le modèle serveur, avec le petit couteau",
    "item_econome": "Manche orange",
    "item_allumettes": "Grandes allumettes pour la cheminée et les bougies",
    "item_filtres_cafe": "Taille 4, boîte de 100",
    "item_cocotte_fonte": "28 cm, très lourde",
    "item_passoire": "Grande, en inox",
    "item_telecommande_tv": "La noire avec le bouton Netflix",
    "item_manettes": "Deux manettes sans fil, rechargeables",
    "item_nappes": "Une blanche, une écrue",
    "item_albums_photo": "De 2008 à 2015",
    "item_plaid": "Gris chiné, très doux",
    "item_carnets_sante": "Ceux des deux enfants",
    "item_livret_famille": "Dans sa pochette cartonnée verte",
    "item_pull_gris": "Col rond, taille L",
    "item_manteau_hiver": "Doudoune noire, taille M",
    "item_ceinture_cuir": "Marron, boucle argentée",
    "item_couette_ete": "Housse blanche, 220x240",
    "item_boules_quies": "Boîte de 10 paires",
    "item_reveil": "Radio-réveil avec projection de l'heure",
    "item_aiguilles_fil": "Fils de toutes les couleurs",
    "item_adaptateur_uk": "Pour l'Angleterre, avec deux ports USB",
    "item_cadenas_code": "Code d'origine, à changer",
    "item_coussin_voyage": "En forme de U, mousse à mémoire de forme",
    "item_feutres": "Boîte de 24",
    "item_cartable": "Bleu, avec la trousse dedans",
    "item_briques_lego": "En vrac, plusieurs kilos",
    "item_costume_pirate": "Avec le chapeau et le sabre en plastique",
    "item_perruque": "Pour le carnaval",
    "item_vetements_bebe": "Bodies et pyjamas",
    "item_cle_usb": "Bleue, avec un petit logo de salon",
    "item_carte_sd": "Pour l'appareil photo",
    "item_agrafeuse": "Rouge, avec une boîte d'agrafes",
    "item_calculatrice": "Scientifique, celle du lycée",
    "item_multiprise": "6 prises avec interrupteur",
    "item_avis_imposition": "Les trois dernières années",
    "item_garanties": "Tickets de caisse agrafés aux notices",
    "item_releves_bancaires": "Les dix dernières années",
    "item_acte_naissance": "Copie intégrale récente",
    "item_seche_cheveux": "Avec le diffuseur",
    "item_thermometre_frontal": "Infrarouge, sans contact",
    "item_sirop_toux": "Pour la toux sèche",
    "item_antihistaminiques": "Pour le rhume des foins",
    "item_lessive": "Liquide, bidon de 3 litres",
    "item_fer_repasser": "À vapeur, avec réservoir",
    "item_bordeaux": "Quatre bouteilles de 2016",
    "item_conserves_tomates": "Faites en septembre",
    "item_pinceaux": "Une brosse plate et une brosse à rechampir",
    "item_sacs_couchage": "Deux sacs confort -5 degrés",
    "item_matelas_gonflable": "Double, avec la pompe électrique",
    "item_rechaud_camping": "À gaz, avec deux cartouches",
    "item_batons_marche": "Télescopiques, la paire",
    "item_gants_ski": "Noirs, taille M, imperméables",
    "item_casque_ski": "Blanc, taille adulte",
    "item_boules_noel": "Rouges et dorées, rangées dans des boîtes d'œufs",
    "item_creche": "Avec tous les santons",
    "item_disques_vinyles": "Une trentaine, surtout des 33 tours",
    "item_chauffage_appoint": "Radiateur à bain d'huile",
    "item_marteau": "Manche en fibre, 500 g",
    "item_cle_molette": "Grande, 30 cm",
    "item_niveau_bulle": "60 cm, aimanté",
    "item_testeur_tension": "Tournevis testeur",
    "item_cles_allen": "Jeu métrique de 1,5 à 10",
    "item_huile_moteur": "Bidon de 5 litres",
    "item_casques_velo": "Un adulte, un enfant",
    "item_sangles": "Quatre sangles à cliquet",
    "item_wd40": "Spray de 400 ml",
    "item_nettoyeur_hp": "1800 W, avec rallonge de tuyau",
    "item_gants_jardin": "Cuir, taille 8",
    "item_arrosoir": "Zinc, 10 litres",
    "item_graines_tomates": "Variété cœur de bœuf",
    "item_boules_petanque": "Jeu de six dans leur sacoche",
    "item_carte_grise": "Pochette avec le contrôle technique",
    "item_cric": "Rouleur, hydraulique",
    "item_chaines_neige": "Dans leur sac, pour pneus de 205",
    "item_jumelles": "Pour observer les oiseaux",
    "item_lampe_tempete": "À pétrole",
    "item_service_verres": "Douze verres à pied",
    "item_cadres_photos": "Emballés dans du papier journal",
    "item_lit_bebe": "Les barreaux et les vis dans un sachet",
    "item_machine_expresso": "À réparer",
    "item_gourde": "En inox, bleue",
    "item_trottinette": "Rose, pliable",
    "item_decodeur_tv": "Boîtier de la box avec sa télécommande",
}

TAGS = {
    "item_tire_bouchon": "vin",
    "item_balance_cuisine": "pâtisserie",
    "item_rouleau_patisserie": "pâtisserie",
    "item_telecommande_tv": "télé",
    "item_cable_hdmi": "télé",
    "item_decodeur_tv": "télé",
    "item_jeu_cartes": "jeux",
    "item_puzzle": "jeux",
    "item_plaid": "hiver",
    "item_manteau_hiver": "hiver",
    "item_couette_ete": "été",
    "item_cle_usb": "informatique",
    "item_multiprise": "électricité",
    "item_marteau": "bricolage",
    "item_perceuse_bosch": "bricolage",
    "item_cle_molette": "bricolage",
    "item_jeu_tournevis": "bricolage",
    "item_scie_sauteuse": "bricolage",
    "item_niveau_bulle": "bricolage",
    "item_vis_m4": "bricolage",
    "item_vis_bois": "bricolage",
    "item_vis_placo": "bricolage",
    "item_peinture_blanche": "peinture",
    "item_pinceaux": "peinture",
    "item_rouleau_peinture": "peinture",
    "item_tondeuse": "jardin",
    "item_secateur": "jardin",
    "item_arrosoir": "jardin",
    "item_gants_jardin": "jardin",
    "item_tuyau_arrosage": "jardin",
    "item_triangle": "sécurité",
    "item_gilets_jaunes": "sécurité",
    "item_cric": "voiture",
    "item_cables_demarrage": "voiture",
    "item_fer_repasser": "repassage",
    "item_table_repasser": "repassage",
    "item_gants_ski": "ski",
    "item_cle_cave": "clés",
    "item_cle_garage": "clés",
    "item_cles_voiture": "clés",
    "item_cle_portail": "clés",
    "item_lunettes_soleil": "été",
    "item_en_phone_tripod": "photo",
    "item_en_headphones": "musique",
    "item_en_bluetooth_speaker": "musique",
    "item_pansements": "santé",
    "item_paracetamol": "santé",
    "item_desinfectant": "santé",
}

# ---------------------------------------------------------------------------------------------------------
# French questions (300): exact 60, typo 45, synonym 60, paraphrase 60, category 30, crosslang 15, place 30
# Ids are written without the "item_" prefix.
# ---------------------------------------------------------------------------------------------------------

# --- exact (60)
qf("exact", "où est la clé à molette ?", "cle_molette")
qf("exact", "les clés de la voiture", "cles_voiture")
qf("exact", "où sont mes lunettes de lecture ?", "lunettes_lecture")
qf("exact", "dictionnaire Larousse", "dictionnaire")
qf("exact", "la tente 4 places", "tente_4p")
qf("exact", "guirlandes lumineuses", "guirlandes")
qf("exact", "où sont les piles AAA ?", "piles_aaa")
qf("exact", "le câble HDMI", "cable_hdmi")
qf("exact", "où est ma perceuse Bosch", "perceuse_bosch")
qf("exact", "la visseuse Makita", "visseuse_makita")
qf("exact", "le jeu Catan", "catan")
qf("exact", "où est le thermos ?", "thermos")
qf("exact", "le plaid en laine", "plaid")
qf("exact", "où est la tondeuse ?", "tondeuse")
qf("exact", "ou est le sapin artificiel", "sapin_artificiel")
qf("exact", "où est le tensiomètre ?", "tensiometre")
qf("exact", "carnets de santé", "carnets_sante")
qf("exact", "où est le livret de famille", "livret_famille")
qf("exact", "le rasoir électrique", "rasoir_electrique")
qf("exact", "où sont les boules de sapin ?", "boules_noel")
qf("exact", "clé usb 64 go", "cle_usb")
qf("exact", "où est l'escabeau ?", "escabeau")
qf("exact", "le nettoyeur haute pression", "nettoyeur_hp")
qf("exact", "l'échelle télescopique", "echelle")
qf("exact", "où est le triangle de signalisation ?", "triangle")
qf("exact", "les chaînes à neige", "chaines_neige")
qf("exact", "le disque dur externe", "disque_dur")
qf("exact", "où est la balance de cuisine", "balance_cuisine")
qf("exact", "où sont les jumelles ?", "jumelles")
qf("exact", "le doudou lapin", "doudou")
qf("exact", "où est le babyphone", "babyphone")
qf("exact", "la machine à coudre", "machine_coudre")
qf("exact", "où est le matelas gonflable ?", "matelas_gonflable")
qf("exact", "le réchaud de camping", "rechaud_camping")
qf("exact", "ciseaux à tissu", "ciseaux_tissu")
qf("exact", "où sont les 12 vis M4", "vis_m4")
qf("exact", "la carte grise", "carte_grise")
qf("exact", "le parasol ?", "parasol")
qf("exact", "où est l'agrafeuse murale", "agrafeuse_murale")
qf("exact", "papier cadeau", "papier_cadeau")
qf("exact", "la cafetière italienne", "cafetiere_italienne")
qf("exact", "où sont les cartouches d'encre ?", "cartouches_encre")
qf("exact", "le costume de pirate", "costume_pirate")
qf("exact", "mes piles bouton", "piles_bouton")
qf("exact", "où est la lampe frontale", "lampe_frontale")
qf("exact", "le fer à repasser", "fer_repasser")
qf("exact", "casque de ski", "casque_ski")
qf("exact", "table à repasser", "table_repasser")
qf("exact", "le tapis du salon", "tapis_salon")
qf("exact", "où est le pot de peinture blanche ?", "peinture_blanche")
qf("exact", "la crème solaire", "creme_solaire")
qf("exact", "où est le chargeur allume-cigare ?", "chargeur_allume_cigare")
qf("exact", "le parapluie pliant", "parapluie_pliant")
qf("exact", "câble USB-C noir 2 m", "cable_usbc_noir")
qf("exact", "le grattoir à glace", "grattoir_glace")
qf("exact", "la lampe torche", "lampe_torche")
qf("exact", "où est le marteau ?", "marteau")
qf("exact", "les cintres", "cintres")
qf("exact", "Passoire", "passoire")
qf("exact", "où est la mandoline ?", "mandoline")

# --- typo (45): missing accents, doubled or dropped letters, phonetic spellings
qf("typo", "perseuse bosh", "perceuse_bosch")
qf("typo", "clé a molette", "cle_molette")
qf("typo", "lunnettes de lecture", "lunettes_lecture")
qf("typo", "sapin artificel", "sapin_artificiel")
qf("typo", "papier toilete", "papier_toilette")
qf("typo", "tansiomètre", "tensiometre")
qf("typo", "thermometre frontal", "thermometre_frontal")
qf("typo", "echelle telescopique", "echelle")
qf("typo", "réchau de camping", "rechaud_camping")
qf("typo", "la tondeuze", "tondeuse")
qf("typo", "chaussetes de ski", "chaussettes_ski")
qf("typo", "ou sont les ampoul de rechange", "ampoules")
qf("typo", "pinces a linge", "pinces_linge")
qf("typo", "coussin de voiyage", "coussin_voyage")
qf("typo", "lessiv", "lessive")
qf("typo", "adoussissant", "adoucissant")
qf("typo", "serpiliere", "serpilliere")
qf("typo", "paracetamole", "paracetamol")
qf("typo", "pensements", "pansements")
qf("typo", "multiprize", "multiprise")
qf("typo", "rallonge electrique", "rallonge")
qf("typo", "niveau a bulles", "niveau_bulle")
qf("typo", "scie sauteze", "scie_sauteuse")
qf("typo", "mètre rubant", "metre_ruban")
qf("typo", "tournvis", "jeu_tournevis")
qf("typo", "pince multiprize", "pince_multiprise")
qf("typo", "papier alluminium", "papier_alu")
qf("typo", "ouvre botte", "ouvre_boite")
qf("typo", "pasoire", "passoire")
qf("typo", "robo patissier", "robot_patissier")
qf("typo", "pele et balayete", "pelle_balayette")
qf("typo", "chargeur pc portble", "chargeur_pc")
qf("typo", "cable micro-usb", "cable_micro_usb")
qf("typo", "bocos vides", "bocaux_vides")
qf("typo", "conserve de tomate", "conserves_tomates")
qf("typo", "confiture de fraize", "confiture_fraises")
qf("typo", "les bouteilles de bordo", "bordeaux")
qf("typo", "sacs de couchagge", "sacs_couchage")
qf("typo", "bâtons de marsh", "batons_marche")
qf("typo", "raquette de tenis", "raquettes_tennis")
qf("typo", "bottes de plui", "bottes_pluie")
qf("typo", "écharpe et bonet", "echarpes_bonnets")
qf("typo", "sizeaux de cuisine", "ciseaux_cuisine")
qf("typo", "cuit vapeure", "cuit_vapeur")
qf("typo", "alumettes", "allumettes")

# --- synonym (60): another common word for the same thing
qf("synonym", "le chargeur du Kindle", "cable_usbc_blanc")
qf("synonym", "la clef de la voiture", "cles_voiture")
qf("synonym", "où est la zapette ?", "telecommande_tv")
qf("synonym", "le doliprane", "paracetamol")
qf("synonym", "la clef du garage", "cle_garage")
qf("synonym", "les boules de Noël", "boules_noel")
qf("synonym", "ma lampe de poche", "lampe_torche")
qf("synonym", "la casserole en fonte", "cocotte_fonte")
qf("synonym", "le mètre enrouleur", "metre_ruban")
qf("synonym", "où est la clef USB ?", "cle_usb")
qf("synonym", "des piles de montre", "piles_bouton")
qf("synonym", "le chargeur de l'ordi", "chargeur_pc")
qf("synonym", "le câble de la télé", "cable_hdmi")
qf("synonym", "les clés hexagonales", "cles_allen")
qf("synonym", "le prolongateur", "rallonge")
qf("synonym", "mon gilet fluo", "gilets_jaunes")
qf("synonym", "le jerrican d'essence", "bidon_essence")
qf("synonym", "où sont les draps de bain ?", "serviettes_bain")
qf("synonym", "la couverture polaire", "plaids_polaire")
qf("synonym", "mon duvet", "sacs_couchage")
qf("synonym", "les bouchons d'oreilles", "boules_quies")
qf("synonym", "où est le coupe-haie ?", "taille_haie")
qf("synonym", "le karcher", "nettoyeur_hp")
qf("synonym", "la perceuse sans fil", "visseuse_makita")
qf("synonym", "où est le scotch ?", "ruban_adhesif")
qf("synonym", "le chatterton", "ruban_isolant")
qf("synonym", "le PQ", "papier_toilette")
qf("synonym", "la bombe de peinture", "bombe_noire")
qf("synonym", "le tuyau d'eau du jardin", "tuyau_arrosage")
qf("synonym", "la clef de l'antivol du vélo", "cadenas_velo_cle")
qf("synonym", "mon sac de rando", "sac_dos_rando")
qf("synonym", "les bâtons de rando", "batons_marche")
qf("synonym", "les ciseaux de couture", "ciseaux_tissu")
qf("synonym", "où sont les legos ?", "briques_lego")
qf("synonym", "le déguisement de pirate", "costume_pirate")
qf("synonym", "la clef de la boîte à lettres", "cle_boite_lettres")
qf("synonym", "mes chaussures de running", "baskets_course")
qf("synonym", "le vélo d'intérieur", "velo_appartement")
qf("synonym", "la moka", "cafetiere_italienne")
qf("synonym", "le robot de cuisine", "robot_patissier")
qf("synonym", "la plasticine", "pate_modeler")
qf("synonym", "les marqueurs", "feutres")
qf("synonym", "le sac de gym", "sac_sport")
qf("synonym", "la superglue", "colle_forte")
qf("synonym", "le plat à raclette", "appareil_raclette")
qf("synonym", "le caquelon", "service_fondue")
qf("synonym", "les sucettes de bébé", "tetines")
qf("synonym", "où est la turbulette ?", "gigoteuse")
qf("synonym", "les bouquins de recettes", "livres_cuisine")
qf("synonym", "le dico", "dictionnaire")
qf("synonym", "mon reflex", "appareil_photo")
qf("synonym", "le ballon de soccer", "ballon_foot")
qf("synonym", "mes solaires", "lunettes_soleil")
qf("synonym", "les assiettes de mon mariage", "vaisselle_mariage")
qf("synonym", "les pointes", "clous")
qf("synonym", "le bidon de lave-vitre", "lave_glace")
qf("synonym", "la couette légère", "couette_ete")
qf("synonym", "ma doudoune", "manteau_hiver")
qf("synonym", "le ceinturon", "ceinture_cuir")
qf("synonym", "le gilet en laine grise", "pull_gris")

# --- paraphrase (60): a description instead of the name
qf("paraphrase", "le truc pour ouvrir les bouteilles", "tire_bouchon")
qf("paraphrase", "le machin qui sert à repasser les chemises", "fer_repasser")
qf("paraphrase", "l'appareil à air chaud pour les cheveux", "seche_cheveux")
qf("paraphrase", "le truc pour mesurer la fièvre", "thermometre_frontal")
qf("paraphrase", "ce qui sert à ouvrir les conserves", "ouvre_boite")
qf("paraphrase", "euh le truc qu'on met sur le sol pour peindre sans salir", "bache_protection")
qf("paraphrase", "le truc pour voir si le courant passe", "testeur_tension")
qf("paraphrase", "les petites pinces pour étendre le linge", "pinces_linge")
qf("paraphrase", "ce qu'il faut pour redémarrer la voiture quand la batterie est à plat", "cables_demarrage")
qf("paraphrase", "l'objet pour soulever la voiture quand on change une roue", "cric")
qf("paraphrase", "ce que doit porter chaque passager en cas de panne sur la route", "gilets_jaunes")
qf("paraphrase", "le truc en bois pour étaler la pâte", "rouleau_patisserie")
qf("paraphrase", "le moule rond pour les gâteaux", "moule_manque")
qf("paraphrase", "le truc pour écraser les oranges", "presse_agrumes")
qf("paraphrase", "le gros pot en fonte pour faire mijoter", "cocotte_fonte")
qf("paraphrase", "le truc pour cuire à la vapeur", "cuit_vapeur")
qf("paraphrase", "le truc qui chauffe le lit l'hiver", "bouillotte")
qf("paraphrase", "le jeu avec des îles à construire et des routes", "catan")
qf("paraphrase", "le truc pour protéger les yeux sur les pistes", "masque_ski")
qf("paraphrase", "ce qui protège les mains du froid sur les pistes", "gants_ski")
qf("paraphrase", "ce qui protège les mains dans les rosiers", "gants_jardin")
qf("paraphrase", "le truc pour s'asseoir sur l'herbe quand on mange dehors", "couverture_pique_nique")
qf("paraphrase", "ce qui sert à gratter le givre sur le pare-brise", "grattoir_glace")
qf("paraphrase", "les trucs à mettre sur les pneus pour rouler dans la neige", "chaines_neige")
qf("paraphrase", "ce que je mets tout en haut du sapin", "etoile_sapin")
qf("paraphrase", "le truc pour dormir pendant les vols", "coussin_voyage")
qf("paraphrase", "le bidule pour brancher mes appareils en Angleterre", "adaptateur_uk")
qf("paraphrase", "ce qui ferme ma valise avec un code", "cadenas_code")
qf("paraphrase", "le truc pour coudre un bouton", "aiguilles_fil")
qf("paraphrase", "ce qu'on met sous un ourlet pour ne pas coudre", "ruban_thermocollant")
qf("paraphrase", "le liquide pour nettoyer les plaies", "desinfectant")
qf("paraphrase", "ce qu'on prend quand le pollen pique", "antihistaminiques")
qf("paraphrase", "le truc pour dormir sans entendre les voisins", "boules_quies")
qf("paraphrase", "l'objet à planter pour soutenir les tomates", "piquets_tomates")
qf("paraphrase", "ce qu'on met sur les cerisiers contre les oiseaux", "filet_oiseaux")
qf("paraphrase", "le machin pour arroser les plantes à la main", "arrosoir")
qf("paraphrase", "le truc pour couper les branches", "secateur")
qf("paraphrase", "le truc qu'on allume avec le charbon pour démarrer", "allume_feu")
qf("paraphrase", "les trucs pour retourner les saucisses sur le grill", "pinces_barbecue")
qf("paraphrase", "là où j'ai sauvegardé les photos de famille", "disque_dur")
qf("paraphrase", "la recharge pour l'imprimante", "cartouches_encre")
qf("paraphrase", "le papier qui prouve que la voiture est assurée", "attestation_auto")
qf("paraphrase", "le document qui prouve que la voiture est à moi", "carte_grise")
qf("paraphrase", "le papier du médecin pour acheter les médicaments", "ordonnances")
qf("paraphrase", "le carnet où il y a les vaccins des enfants", "carnets_sante")
qf("paraphrase", "le papier pour les impôts", "avis_imposition")
qf("paraphrase", "les verres pour trinquer au champagne", "flutes_champagne")
qf("paraphrase", "le plateau pour servir l'apéro", "plateau_service")
qf("paraphrase", "le truc pour faire des glaces maison", "sorbetiere")
qf("paraphrase", "les petits personnages des pirates", "figurines_pirates")
qf("paraphrase", "la chose pour allumer les bougies", "allumettes")
qf("paraphrase", "de quoi nettoyer les carreaux", "produit_vitres")
qf("paraphrase", "ce qu'on met dans le lave-vaisselle pour laver", "pastilles_lv")
qf("paraphrase", "le truc pour que le linge sente bon", "adoucissant")
qf("paraphrase", "la lampe qu'on met sur la tête", "lampe_frontale")
qf("paraphrase", "l'appareil qu'on branche pour regarder les chaînes de la box", "decodeur_tv")
qf("paraphrase", "le truc pour relier la télé à internet par un fil", "cable_ethernet")
qf("paraphrase", "la petite carte mémoire de l'appareil photo", "carte_sd")
qf("paraphrase", "le truc pour attacher les feuilles de papier ensemble", "agrafeuse", ["trombones"])
qf("paraphrase", "l'objet doux du petit pour s'endormir", "doudou")

# --- category (30): the family the item belongs to; `accept` only where the family has a few clear members
qf("category", "mes papiers d'identité", "passeport")
qf("category", "un document fiscal", "avis_imposition")
qf("category", "une facture", "factures_edf")
qf("category", "un solvant", "white_spirit")
qf("category", "un appareil de chauffage", "chauffage_appoint")
qf("category", "un appareil de ventilation", "ventilateur")
qf("category", "un vêtement de cérémonie", "robe_soiree")
qf("category", "un accessoire pour costume", "cravates")
qf("category", "de l'alcool", "bordeaux", ["champagne"])
qf("category", "un jeu de construction", "briques_lego", ["plaques_lego"])
qf("category", "un produit solaire", "creme_solaire")
qf("category", "un moyen de transport pour enfant", "trottinette", ["velo_enfant"])
qf("category", "un équipement de sécurité routière", "triangle", ["gilets_jaunes"])
qf("category", "un document d'assurance", "assurance_habitation", ["attestation_auto"])
qf("category", "du linge de lit", "draps_140", ["couette_ete", "draps_campagne"])
qf("category", "une boisson chaude", "the_vert")
qf("category", "de la musique", "disques_vinyles", ["en_bluetooth_speaker"])
qf("category", "des souvenirs de famille", "albums_photo", ["album_mariage", "cadres_photos"])
qf("category", "un instrument de pesée", "balance_cuisine")
qf("category", "un somnifère", "melatonine")
qf("category", "un outil à frapper", "marteau")
qf("category", "du matériel scolaire", "cartable", ["feutres"])
qf("category", "une décoration d'anniversaire", "bougies_anniv", ["papier_cadeau", "en_gift_bags"])
qf("category", "les décos de Noël", "guirlandes", ["boules_noel", "creche", "etoile_sapin"])
qf("category", "un lubrifiant", "wd40", ["huile_moteur"])
qf("category", "un produit pour laver le linge", "lessive", ["adoucissant", "detachant"])
qf("category", "un justificatif d'achat", "garanties")
qf("category", "un produit contre les allergies", "antihistaminiques")
qf("category", "un appareil pour les desserts glacés", "sorbetiere")
qf("category", "un produit d'entretien pour la voiture", "cire_voiture", ["lave_glace"])

# --- crosslang (15): French question, item named in English
qf("crosslang", "le fer à souder", "en_soldering_iron")
qf("crosslang", "où sont les haltères ?", "en_dumbbells")
qf("crosslang", "la pompe à vélo", "en_bike_pump")
qf("crosslang", "le casque anti-bruit", "en_headphones")
qf("crosslang", "ma souris sans fil", "en_wireless_mouse")
qf("crosslang", "l'enceinte bluetooth", "en_bluetooth_speaker")
qf("crosslang", "la veilleuse des enfants", "en_night_light")
qf("crosslang", "les crayons de couleur", "en_colouring_pencils")
qf("crosslang", "le pistolet à agrafes", "en_staple_gun")
qf("crosslang", "le tapis de yoga", "en_yoga_mat")
qf("crosslang", "où est la gaufrière ?", "en_waffle_iron")
qf("crosslang", "la batterie externe", "en_power_bank")
qf("crosslang", "la mangeoire à oiseaux", "en_bird_feeder")
qf("crosslang", "la litière du chat", "en_cat_litter")
qf("crosslang", "la râpe à fromage", "en_cheese_grater")

# --- place (30): the place disambiguates between near-duplicates, or asks for what is in a container
qf("place", "les gants de la cave", "gants_ski")
qf("place", "les gants de l'abri de jardin", "gants_jardin")
qf("place", "les piles du tiroir de la cuisine", "piles_aa")
qf("place", "les piles du bureau", "piles_aaa", ["piles_bouton"])
qf("place", "le câble de la voiture", "cable_usbc_noir")
qf("place", "le câble dans la table de nuit", "cable_lightning")
qf("place", "ce qu'il y a dans le carton 7", "appareil_raclette", ["service_fondue"])
qf("place", "le parapluie de la voiture", "parapluie_pliant")
qf("place", "le parapluie de l'entrée", "parapluie")
qf("place", "les vis du bac bleu", "vis_m4", ["vis_bois", "boulons_m6"])
qf("place", "la boîte de vis sur l'étagère du garage", "vis_placo")
qf("place", "le cadenas de la valise cabine", "cadenas_code")
qf("place", "le casque dans le carton ski", "casque_ski")
qf("place", "la télécommande du meuble TV", "telecommande_tv")
qf("place", "la clef de la cave dans l'entrée", "cle_cave")
qf("place", "le truc isotherme du placard du haut", "thermos")
qf("place", "la corde du panneau mural", "corde")
qf("place", "les draps de la résidence secondaire", "draps_campagne")
qf("place", "les plaids de la résidence secondaire", "plaids_polaire")
qf("place", "la cafetière de la résidence secondaire", "cafetiere_filtre")
qf("place", "le lit du garde-meuble", "lit_bebe")
qf("place", "la bouillotte de la chambre bleue", "bouillotte")
qf("place", "l'agrafeuse du garage", "agrafeuse_murale")
qf("place", "l'agrafeuse du bureau", "agrafeuse")
qf("place", "le ballon de la terrasse", "ballon_foot")
qf("place", "les ciseaux de la cuisine", "ciseaux_cuisine")
qf("place", "le vélo du garde-meuble", "velo_enfant")
qf("place", "les lunettes de la voiture", "lunettes_soleil")
qf("place", "la brosse à dents de la trousse de toilette", "brosse_dents_pliable")
qf("place", "le rasoir de la trousse de toilette", "rasoir_voyage")

# ---------------------------------------------------------------------------------------------------------
# English questions (60): exact 12, typo 9, synonym 12, paraphrase 12, category 6, crosslang 3, place 6
# ---------------------------------------------------------------------------------------------------------

# --- exact (12)
qe("exact", "where is the soldering iron?", "en_soldering_iron")
qe("exact", "wireless mouse", "en_wireless_mouse")
qe("exact", "where's the bike pump", "en_bike_pump")
qe("exact", "Harry Potter box set", "en_harry_potter")
qe("exact", "where is the waffle iron?", "en_waffle_iron")
qe("exact", "garden kneeling pad", "en_kneeling_pad")
qe("exact", "where's the baby bath", "en_baby_bath")
qe("exact", "hose nozzle", "en_hose_nozzle")
qe("exact", "where's my yoga mat?", "en_yoga_mat")
qe("exact", "dishwasher warranty card", "en_dishwasher_warranty")
qe("exact", "where are the Penguin classics", "en_penguin_classics")
qe("exact", "jamie oliver cookbook", "en_jamie_oliver")

# --- typo (9)
qe("typo", "chese grater", "en_cheese_grater")
qe("typo", "pumkin carving kit", "en_pumpkin_kit")
qe("typo", "agatha cristie books", "en_christie")
qe("typo", "where is the slow coker", "en_slow_cooker")
qe("typo", "hedge sheers", "en_hedge_shears")
qe("typo", "swing ball set", "en_swingball")
qe("typo", "morgage papers", "en_mortgage")
qe("typo", "tea strainner", "en_tea_strainer")
qe("typo", "wheres the frisbe", "en_frisbee")

# --- synonym (12)
qe("synonym", "garden scissors", "en_hedge_shears")
qe("synonym", "where is the herbicide", "en_weed_killer")
qe("synonym", "garden string", "en_garden_twine")
qe("synonym", "the kids' night lamp", "en_night_light")
qe("synonym", "flower pots", "en_plant_pots")
qe("synonym", "where's my portable charger?", "en_power_bank")
qe("synonym", "portable speaker", "en_bluetooth_speaker")
qe("synonym", "the bird table", "en_bird_feeder")
qe("synonym", "wellies for the kids", "en_kids_rain_boots")
qe("synonym", "floaties", "en_swim_armbands")
qe("synonym", "the local tax bill", "en_council_tax")
qe("synonym", "home loan papers", "en_mortgage")

# --- paraphrase (12)
qe("paraphrase", "the thing for raising my laptop to eye level", "en_laptop_stand")
qe("paraphrase", "the gadget to connect my laptop to the TV", "en_usbc_hdmi")
qe("paraphrase", "what I use to smooth wood before painting", "en_sandpaper")
qe("paraphrase", "that silver sticky tape for repairs", "en_duct_tape")
qe("paraphrase", "the little blades for cutting curves in wood", "en_jigsaw_blades")
qe("paraphrase", "the toy with tracks and engines", "en_train_set")
qe("paraphrase", "the books I read to the kids at night", "en_bedtime_books")
qe("paraphrase", "head protection for the kids' scooter", "en_scooter_helmet")
qe("paraphrase", "the jug with ml marks for pouring", "en_measuring_jug")
qe("paraphrase", "the tray for baking little cakes", "en_muffin_tin")
qe("paraphrase", "the wheel for cutting pizza", "en_pizza_cutter")
qe("paraphrase", "the basket for eating outside on the grass", "en_picnic_basket")

# --- category (6)
qe("category", "a classic novel", "en_penguin_classics")
qe("category", "a cookbook", "en_jamie_oliver", ["livres_cuisine"])
qe("category", "a garden chemical", "en_weed_killer")
qe("category", "a reference book", "en_oxford_dict")
qe("category", "a legal document about the house", "assurance_habitation", ["en_mortgage"])
qe("category", "fitness equipment", "en_dumbbells", ["en_yoga_mat"])

# --- crosslang (3): English question, item named in French
qe("crosslang", "passport", "passeport")
qe("crosslang", "corkscrew", "tire_bouchon")
qe("crosslang", "hair dryer", "seche_cheveux")

# --- place (6)
qe("place", "the raincoat in the hall cupboard", "en_raincoat")
qe("place", "the waterproof jacket in the cellar", "en_waterproof_jacket")
qe("place", "the headphones in the desk drawer", "en_headphones")
qe("place", "the cat litter in the laundry room", "en_cat_litter")
qe("place", "the dog treats in the entrance cupboard", "en_dog_treats")
qe("place", "the cat carrier in the cellar", "en_cat_carrier")

# ---------------------------------------------------------------------------------------------------------
# Absent (20): something that is not in the household; expect null. Not part of the 360.
# ---------------------------------------------------------------------------------------------------------

qa("fr", "où est la guitare ?")
qa("fr", "le fer à friser")
qa("fr", "mon vélo électrique")
qa("fr", "où est le drone ?")
qa("fr", "la machine à pain")
qa("fr", "le jeu de fléchettes")
qa("fr", "où est mon casque de moto ?")
qa("fr", "la tronçonneuse")
qa("fr", "le bateau gonflable")
qa("fr", "le canoë")
qa("en", "where is the telescope?")
qa("en", "snowboard")
qa("en", "where's the hair straightener")
qa("en", "the violin")
qa("en", "fishing rod")
qa("en", "where is the metal detector?")
qa("en", "ukulele")
qa("en", "the typewriter")
qa("en", "where is the hot tub cover")
qa("en", "surfboard")

# ---------------------------------------------------------------------------------------------------------
# Build and self-check
# ---------------------------------------------------------------------------------------------------------

TARGETS = {  # share of the 360 questions, in percent
    "exact": 20, "typo": 15, "synonym": 20, "paraphrase": 20, "category": 10, "crosslang": 5, "place": 10,
}
FEMININE = ("valise", "boîte", "malle", "trousse", "caisse")


def build_fiches():
    place_name = {p[0]: p[1] for p in PLACES}
    cont_label = {c[0]: c[1] for c in CONTAINERS}
    items = []
    for it in ITEMS:
        desc = it["d"] if it["d"] is not None else DESC.get(it["id"])
        tags = it["t"] or ([x.strip() for x in TAGS[it["id"]].split(",")] if it["id"] in TAGS else [])
        where = it["where"]
        is_container = where.startswith("ct_")
        raw = it["r"]
        if raw is None and _h(it["id"] + "|raw", 100) < 60 and it["st"] == "STORED":
            if is_container:
                label = cont_label[where].lower()
                art = "la" if label.split()[0] in FEMININE else "le"
                raw = "%s dans %s %s" % (it["name"].lower(), art, label)
            else:
                raw = "%s @ %s" % (it["name"].lower(), place_name[where].lower())
        days = it["days"] if it["days"] is not None else _h(it["id"] + "|days", 900)
        items.append({
            "id": it["id"], "name": it["name"], "description": desc, "tags": tags,
            "place": None if is_container else where, "container": where if is_container else None,
            "rawInput": raw, "status": it["st"], "lentTo": it["lent"], "archived": it["arch"],
            "sensitive": it["sens"], "daysAgo": days,
        })
    return {
        "formatVersion": 1,
        "places": [{"id": i, "name": n, "parent": p} for i, n, p in PLACES],
        "containers": [{"id": i, "label": l, "place": p} for i, l, p in CONTAINERS],
        "items": items,
    }


def dump_fiches(fiches):
    def line(o):
        return "    " + json.dumps(o, ensure_ascii=False, separators=(", ", ": "))
    out = ["{", '  "formatVersion": %d,' % fiches["formatVersion"]]
    for key in ("places", "containers", "items"):
        out.append('  "%s": [' % key)
        out.append(",\n".join(line(o) for o in fiches[key]))
        out.append("  ]" + ("," if key != "items" else ""))
    out.append("}")
    return "\n".join(out) + "\n"


def build_questions():
    def ordered(rows):  # deterministic pseudo-shuffle so that every kind is spread over the file
        return sorted(rows, key=lambda r: (zlib.crc32((r[0] + "|" + r[1]).encode("utf-8")), r[1]))
    rows = [("fr", r) for r in ordered(Q_FR)] + [("en", r) for r in ordered(Q_EN)]
    out = []
    for n, (lang, (kind, query, expect, accept)) in enumerate(rows, 1):
        out.append((lang, kind, query, expect, accept, "holdout" if n % 5 == 0 else "dev"))
    for lang, table in (("fr", Q_ABSENT_FR), ("en", Q_ABSENT_EN)):
        for kind, query, expect, accept in table:
            out.append((lang, kind, query, expect, accept, "dev"))
    lines = []
    for n, (lang, kind, query, expect, accept, split) in enumerate(out, 1):
        o = {"id": "q%04d" % n, "lang": lang, "origin": "synthetic", "query": query, "expect": expect}
        if accept:
            o["accept"] = accept
        o["kind"] = kind
        o["split"] = split
        lines.append(o)
    return lines


def norm(s):
    s = unicodedata.normalize("NFD", s.lower())
    return "".join(ch for ch in s if not unicodedata.combining(ch))


def check(fiches, questions):
    pids = [p["id"] for p in fiches["places"]]
    cids = [c["id"] for c in fiches["containers"]]
    iids = [i["id"] for i in fiches["items"]]
    for name, ids in (("places", pids), ("containers", cids), ("items", iids)):
        assert len(ids) == len(set(ids)), "duplicate ids in " + name
    pset, cset, iset = set(pids), set(cids), set(iids)
    parent = {p["id"]: p["parent"] for p in fiches["places"]}
    for pid, par in parent.items():
        assert par is None or par in pset, "unknown parent of " + pid
        seen, cur = set(), pid
        while cur is not None:  # no cycle
            assert cur not in seen, "cycle at " + pid
            seen.add(cur)
            cur = parent[cur]
    for c in fiches["containers"]:
        assert c["place"] in pset, "unknown place of " + c["id"]
    for i in fiches["items"]:
        assert (i["place"] is None) != (i["container"] is None), "item needs exactly one location: " + i["id"]
        assert i["place"] is None or i["place"] in pset, "unknown place of " + i["id"]
        assert i["container"] is None or i["container"] in cset, "unknown container of " + i["id"]
        assert i["status"] in ("STORED", "TAKEN", "LENT")
        assert (i["status"] == "LENT") == (i["lentTo"] is not None), "lentTo mismatch " + i["id"]
        assert 0 <= i["daysAgo"] <= 900
    qids = [q["id"] for q in questions]
    assert len(qids) == len(set(qids)), "duplicate question ids"
    texts = [q["query"] for q in questions]
    assert len(texts) == len(set(texts)), "duplicate query text: %s" % [t for t, n in Counter(texts).items() if n > 1]
    targets = Counter()
    for q in questions:
        if q["kind"] == "absent":
            assert q["expect"] is None and "accept" not in q
            continue
        assert q["expect"] in iset, "unknown expect %s in %s" % (q["expect"], q["id"])
        for a in q.get("accept", []):
            assert a in iset and a != q["expect"], "bad accept %s in %s" % (a, q["id"])
        targets[q["expect"]] += 1
    too_many = sorted(k for k, v in targets.items() if v > 2)
    assert not too_many, "targeted more than twice: %s" % too_many
    real = [q for q in questions if q["kind"] != "absent"]
    assert len(real) == 360, len(real)
    langs = Counter(q["lang"] for q in real)
    assert langs == {"fr": 300, "en": 60}, langs
    absent = [q for q in questions if q["kind"] == "absent"]
    assert Counter(q["lang"] for q in absent) == {"fr": 10, "en": 10}
    kinds = Counter(q["kind"] for q in real)
    for kind, pct in TARGETS.items():
        got = 100.0 * kinds[kind] / 360
        assert abs(got - pct) <= 2.0, "kind %s: %d = %.1f%% (target %d%%)" % (kind, kinds[kind], got, pct)
    for n, q in enumerate(real, 1):
        assert q["split"] == ("holdout" if n % 5 == 0 else "dev")
    name_of = {i["id"]: norm(i["name"]) for i in fiches["items"]}
    reused = [q["id"] for q in real if q["kind"] not in ("exact", "place") and name_of[q["expect"]] in norm(q["query"])]
    return targets, reused


def write_lf(path, text):
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def main():
    fiches = build_fiches()
    questions = build_questions()
    targets, reused = check(fiches, questions)
    write_lf(os.path.join(HERE, "fiches-reference.json"), dump_fiches(fiches))
    write_lf(os.path.join(HERE, "recherche.jsonl"),
             "".join(json.dumps(q, ensure_ascii=False, separators=(",", ":")) + "\n" for q in questions))
    its = fiches["items"]
    print("places %d, containers %d, items %d (English-named %d)" % (
        len(fiches["places"]), len(fiches["containers"]), len(its), sum(1 for i in its if i["id"].startswith("item_en_"))))
    print("items: description %d, tags %d, rawInput %d, TAKEN %d, LENT %d, archived %d, sensitive %d" % (
        sum(1 for i in its if i["description"]), sum(1 for i in its if i["tags"]), sum(1 for i in its if i["rawInput"]),
        sum(1 for i in its if i["status"] == "TAKEN"), sum(1 for i in its if i["status"] == "LENT"),
        sum(1 for i in its if i["archived"]), sum(1 for i in its if i["sensitive"])))
    by = Counter((q["lang"], q["kind"]) for q in questions)
    print("questions: %d" % len(questions))
    for kind in list(TARGETS) + ["absent"]:
        print("  %-10s fr %3d  en %3d" % (kind, by[("fr", kind)], by[("en", kind)]))
    print("distinct target items: %d; accept lists: %d; item name reused outside exact/place: %s" % (
        len(targets), sum(1 for q in questions if "accept" in q), reused or "none"))


if __name__ == "__main__":
    main()
