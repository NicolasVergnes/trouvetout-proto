"""Builds corpus/parser.jsonl from hand-written sentences (docs/06 §1). Deterministic: run and commit.

Every sentence is synthetic or written by the developer (origin "synthetic"); no user phrase ever enters this
file (ADR-013, docs/06 §4). Expectations follow ADR-018: the first location preposition after the object opens
the place; a position preposition (sous, derrière, under, behind…) stays in the place text, a plain one
(dans, sur, à, in, on, at) is dropped, as are leading articles.
"""
import io, json, os

HERE = os.path.dirname(os.path.abspath(__file__))

def S(input, items, place=None, quantity=None, unit=None):
    e = {"intent": "Store", "items": items if isinstance(items, list) else [items]}
    if place is not None: e["place"] = place
    if quantity is not None: e["quantity"] = quantity
    if unit is not None: e["unit"] = unit
    return input, e

def T(input, items):
    return input, {"intent": "Take", "items": items if isinstance(items, list) else [items]}

def R(input, items, place=None):
    e = {"intent": "Return", "items": items if isinstance(items, list) else [items]}
    if place is not None: e["place"] = place
    return input, e

def M(input, items, place):
    return input, {"intent": "MoveTo", "items": items if isinstance(items, list) else [items], "place": place}

def L(input, items, person):
    return input, {"intent": "Lend", "items": items if isinstance(items, list) else [items], "person": person}

def RL(input, items, person):
    return input, {"intent": "ReturnFromLend", "items": items if isinstance(items, list) else [items], "person": person}

def Q(input, query):
    return input, {"intent": "Query", "query": query}

FR = [
    # --- Store: plain prepositions, articles, elisions
    S("passeport dans le tiroir du haut de la commode", "passeport", "tiroir du haut de la commode"),
    S("clés @ entrée", "clés", "entrée"),
    S("clés@entrée", "clés", "entrée"),
    S("les décos de Noël sont dans le carton 7 de l'étagère du fond de la cave", "décos de Noël", "carton 7 de l'étagère du fond de la cave"),
    S("euh le chargeur du kindle sur le bureau", "chargeur du kindle", "bureau"),
    S("la valise rouge est à la cave", "valise rouge", "cave"),
    S("j'ai mis les piles AA dans le tiroir de la cuisine", "piles AA", "tiroir de la cuisine"),
    S("12 vis M4 dans le bac bleu de l'atelier", "vis M4", "bac bleu de l'atelier", 12),
    S("3 m de câble HDMI sous le canapé", "câble HDMI", "sous le canapé", 3, "m"),
    S("livret de famille derrière les livres de la bibliothèque du salon", "livret de famille", "derrière les livres de la bibliothèque du salon"),
    S("passeport et carnet de santé dans le tiroir", ["passeport", "carnet de santé"], "tiroir"),
    S("le passeport est dans le coffre", "passeport", "coffre"),
    S("les passeports sont dans la pochette bleue du bureau", "passeports", "pochette bleue du bureau"),
    S("l'agrafeuse est sur l'étagère du bureau", "agrafeuse", "étagère du bureau"),
    S("alors les ampoules de rechange sont dans le placard de l'entrée", "ampoules de rechange", "placard de l'entrée"),
    S("bon j'ai rangé la raquette de tennis dans le garage", "raquette de tennis", "garage"),
    S("j'ai posé mes lunettes sur la table de nuit", "lunettes", "table de nuit"),
    S("je range le sèche-cheveux dans le meuble de la salle de bain", "sèche-cheveux", "meuble de la salle de bain"),
    S("je mets la perceuse dans la caisse à outils", "perceuse", "caisse à outils"),
    S("j'ai laissé le parapluie dans la voiture", "parapluie", "voiture"),
    S("mon passeport est dans le sac à dos noir", "passeport", "sac à dos noir"),
    S("la clé de la boîte aux lettres est au crochet de l'entrée", "clé de la boîte aux lettres", "crochet de l'entrée"),
    S("le fer à repasser est dans la buanderie", "fer à repasser", "buanderie"),
    S("la machine à coudre est au grenier", "machine à coudre", "grenier"),
    S("le sac à main beige est dans l'armoire de la chambre", "sac à main beige", "armoire de la chambre"),
    S("les skis sont à la cave", "skis", "cave"),
    S("les chaussures de ski sont aux archives du garage", "chaussures de ski", "archives du garage"),
    S("les papiers de la voiture sont dans la boîte à gants", "papiers de la voiture", "boîte à gants"),
    S("la pompe à vélo est dans le garage à côté de la porte", "pompe à vélo", "garage à côté de la porte"),
    S("le double des clés est chez ma mère", "double des clés", "chez ma mère"),
    S("l'échelle est contre le mur du garage", "échelle", "contre le mur du garage"),
    S("les valises sont au-dessus de l'armoire", "valises", "au-dessus de l'armoire"),
    S("le carton de photos est en haut de l'armoire de la chambre d'amis", "carton de photos", "en haut de l'armoire de la chambre d'amis"),
    S("les bottes sont en bas de l'escalier", "bottes", "en bas de l'escalier"),
    S("la boîte de fusibles est au fond du placard", "boîte de fusibles", "au fond du placard"),
    S("le ballon est derrière le canapé", "ballon", "derrière le canapé"),
    S("la lampe de poche est devant la porte de la cave", "lampe de poche", "devant la porte de la cave"),
    S("le tapis de yoga est sous le lit", "tapis de yoga", "sous le lit"),
    S("les câbles sont à côté de la télé", "câbles", "à côté de la télé"),
    S("le chargeur de l'appareil photo est près de l'imprimante", "chargeur de l'appareil photo", "près de l'imprimante"),
    S("voilà les gants de jardinage sont dans le cabanon", "gants de jardinage", "cabanon"),
    S("hum les tournevis sont dans le deuxième tiroir de l'établi", "tournevis", "deuxième tiroir de l'établi"),
    S("il y a des piles dans le tiroir du bureau", "piles", "tiroir du bureau"),
    S("on a mis les jouets de plage dans le coffre de la voiture", "jouets de plage", "coffre de la voiture"),
    S("les guirlandes sont dans la caisse rouge du grenier", "guirlandes", "caisse rouge du grenier"),
    S("les draps de rechange sont dans l'armoire du couloir", "draps de rechange", "armoire du couloir"),
    S("le dossier des impôts est dans le classeur vert", "dossier des impôts", "classeur vert"),
    S("la carte grise est dans la pochette de la boîte à gants", "carte grise", "pochette de la boîte à gants"),
    S("les médicaments sont dans la boîte en haut du frigo", "médicaments", "boîte en haut du frigo"),
    S("le disque dur externe est dans le tiroir du milieu", "disque dur externe", "tiroir du milieu"),
    S("ma montre est dans la coupelle de l'entrée", "montre", "coupelle de l'entrée"),
    S("ta brosse à dents de voyage est dans la trousse bleue", "brosse à dents de voyage", "trousse bleue"),
    S("ses lunettes de soleil sont dans la voiture", "lunettes de soleil", "voiture"),
    S("le câble USB-C blanc est dans le pot à crayons", "câble USB-C blanc", "pot à crayons"),
    S("le mètre ruban est dans la poche du tablier", "mètre ruban", "poche du tablier"),
    S("le testament est dans le coffre de la banque", "testament", "coffre de la banque"),
    S("la tente est dans le sac bleu de la cave", "tente", "sac bleu de la cave"),
    S("le réchaud de camping est dans le carton camping du garage", "réchaud de camping", "carton camping du garage"),
    S("les cartes routières sont dans la portière côté conducteur", "cartes routières", "portière côté conducteur"),
    S("les chargeurs sont dans le panier sur le frigo", "chargeurs", "panier sur le frigo"),
    S("la cafetière italienne est dans le placard au-dessus de l'évier", "cafetière italienne", "placard au-dessus de l'évier"),
    S("les sacs de congélation sont dans le tiroir sous le four", "sacs de congélation", "tiroir sous le four"),
    S("les clés de la cave sont sur le crochet rouge", "clés de la cave", "crochet rouge"),
    S("ok les enceintes sont dans le carton 3", "enceintes", "carton 3"),
    S("carnet de vaccination dans le tiroir du secrétaire", "carnet de vaccination", "tiroir du secrétaire"),
    S("télécommande de la clim dans le tiroir de la table basse", "télécommande de la clim", "tiroir de la table basse"),
    S("lunettes de natation dans le sac de piscine", "lunettes de natation", "sac de piscine"),
    S("mes bijoux sont dans la boîte en bois de la commode", "bijoux", "boîte en bois de la commode"),
    S("bon alors la colle forte est dans le tiroir des outils", "colle forte", "tiroir des outils"),
    # --- Store: quantities and units
    S("douze vis M4 dans le bac bleu", "vis M4", "bac bleu", 12),
    S("2 paires de gants dans le tiroir de l'entrée", "gants", "tiroir de l'entrée", 2, "paires"),
    S("5 kg de riz dans le placard du bas", "riz", "placard du bas", 5, "kg"),
    S("4 bouteilles d'huile d'olive dans le cellier", "huile d'olive", "cellier", 4, "bouteilles"),
    S("trois rouleaux de scotch dans le tiroir du bureau", "scotch", "tiroir du bureau", 3, "rouleaux"),
    S("10 m de corde dans le coffre du garage", "corde", "coffre du garage", 10, "m"),
    S("2 boîtes de clous dans le bac jaune", "clous", "bac jaune", 2, "boîtes"),
    S("20 sachets de graines dans la boîte à graines du cabanon", "graines", "boîte à graines du cabanon", 20, "sachets"),
    S("6 ampoules E27 dans le placard de l'entrée", "ampoules E27", "placard de l'entrée", 6),
    S("1,5 l de lessive sous l'évier", "lessive", "sous l'évier", 1.5, "l"),
    S("vingt enveloppes et 3 timbres dans le tiroir du haut", ["enveloppes", "timbres"], "tiroir du haut", 20),
    # --- Store: several objects
    S("passeport, carte d'identité et permis dans la pochette rouge", ["passeport", "carte d'identité", "permis"], "pochette rouge"),
    S("marteau et tournevis dans la caisse à outils", ["marteau", "tournevis"], "caisse à outils"),
    S("les raquettes et les balles sont dans le coffre de la voiture", ["raquettes", "balles"], "coffre de la voiture"),
    S("chargeur + câble dans le sac de l'ordinateur", ["chargeur", "câble"], "sac de l'ordinateur"),
    S("j'ai mis le masque et le tuba dans le sac de plage", ["masque", "tuba"], "sac de plage"),
    S("bonnet, écharpe et gants dans le panier de l'entrée", ["bonnet", "écharpe", "gants"], "panier de l'entrée"),
    # --- Store without a place (bare noun phrases)
    S("perceuse sans fil", "perceuse sans fil"),
    S("les clés de la voiture", "clés de la voiture"),
    # --- Take
    T("je prends la perceuse", "perceuse"),
    T("je prends les clés de la voiture", "clés de la voiture"),
    T("j'emporte la tente", "tente"),
    T("je sors la valise rouge", "valise rouge"),
    T("j'ai pris le vidéoprojecteur", "vidéoprojecteur"),
    T("euh je prends l'appareil photo", "appareil photo"),
    T("je prends la clé à molette", "clé à molette"),
    T("je récupère le chargeur du kindle", "chargeur du kindle"),
    T("bon je prends la perceuse et la visseuse", ["perceuse", "visseuse"]),
    T("j'embarque la glacière", "glacière"),
    T("je me sers de l'échelle", "échelle"),
    # --- Return (put back)
    R("perceuse remise au garage", "perceuse", "garage"),
    R("je remets la clé à molette", "clé à molette"),
    R("je remets la perceuse dans le garage", "perceuse", "garage"),
    R("j'ai remis les clés de la voiture dans la coupelle", "clés de la voiture", "coupelle"),
    R("je rapporte la tente à la cave", "tente", "cave"),
    R("j'ai rapporté l'échelle au garage", "échelle", "garage"),
    R("valise rouge remise à la cave", "valise rouge", "cave"),
    R("je repose le marteau dans la caisse à outils", "marteau", "caisse à outils"),
    R("la perceuse est de retour au garage", "perceuse", "garage"),
    R("appareil photo remis dans le tiroir du bureau", "appareil photo", "tiroir du bureau"),
    R("je remets le vidéoprojecteur", "vidéoprojecteur"),
    R("je ramène la glacière dans le cellier", "glacière", "cellier"),
    # --- MoveTo
    M("la perceuse est maintenant dans le garage", "perceuse", "garage"),
    M("les skis sont maintenant au grenier", "skis", "grenier"),
    M("le carton 7 est désormais dans la chambre d'amis", "carton 7", "chambre d'amis"),
    M("j'ai déplacé la tente vers le garage", "tente", "garage"),
    M("j'ai déplacé les archives dans le placard du couloir", "archives", "placard du couloir"),
    M("la machine à coudre est maintenant sur le bureau", "machine à coudre", "bureau"),
    M("les guirlandes sont désormais dans la caisse rouge du grenier", "guirlandes", "caisse rouge du grenier"),
    M("j'ai bougé le vélo d'appartement dans le salon", "vélo d'appartement", "salon"),
    M("le disque dur est maintenant dans le tiroir du milieu", "disque dur", "tiroir du milieu"),
    M("euh la cafetière est maintenant au-dessus du frigo", "cafetière", "au-dessus du frigo"),
    # --- Lend
    L("j'ai prêté la tente à Marc", "tente", "Marc"),
    L("prêté le vidéoprojecteur à Sophie", "vidéoprojecteur", "Sophie"),
    L("je prête la perceuse à Julien", "perceuse", "Julien"),
    L("j'ai prêté la clé à molette à Paul", "clé à molette", "Paul"),
    L("j'ai prêté les skis à ma sœur", "skis", "ma sœur"),
    L("j'ai prêté la glacière et les chaises de camping à Marc", ["glacière", "chaises de camping"], "Marc"),
    L("tente prêtée à Léa", "tente", "Léa"),
    L("j'ai prêté le livre de recettes à Camille", "livre de recettes", "Camille"),
    L("Marc a emprunté la tente", "tente", "Marc"),
    L("Sophie m'a emprunté le vidéoprojecteur", "vidéoprojecteur", "Sophie"),
    # --- ReturnFromLend
    RL("Marc a rendu la tente", "tente", "Marc"),
    RL("Sophie m'a rendu le vidéoprojecteur", "vidéoprojecteur", "Sophie"),
    RL("Julien m'a rapporté la perceuse", "perceuse", "Julien"),
    RL("Paul a rendu la clé à molette", "clé à molette", "Paul"),
    RL("Léa m'a ramené la tente", "tente", "Léa"),
    RL("tente rendue par Marc", "tente", "Marc"),
    RL("Camille a rendu le livre de recettes", "livre de recettes", "Camille"),
    RL("Marc a rendu la glacière et les chaises de camping", ["glacière", "chaises de camping"], "Marc"),
    # --- Query
    Q("où sont les clés de la voiture ?", "clés de la voiture"),
    Q("où est le passeport", "passeport"),
    Q("c'est où les lunettes de soleil", "lunettes de soleil"),
    Q("ou est le passeport", "passeport"),
    Q("où sont les décos de Noël", "décos de Noël"),
    Q("où se trouve la perceuse", "perceuse"),
    Q("où j'ai mis mes lunettes", "lunettes"),
    Q("où est-ce que j'ai mis le chargeur du kindle", "chargeur du kindle"),
    Q("où ai-je rangé le livret de famille ?", "livret de famille"),
    Q("les clés de la cave c'est où", "clés de la cave"),
    Q("je ne trouve plus la carte grise", "carte grise"),
    Q("je cherche la pompe à vélo", "pompe à vélo"),
    Q("t'as vu le chargeur de l'appareil photo", "chargeur de l'appareil photo"),
    Q("où sont passées les piles", "piles"),
    Q("euh où est la valise rouge ?", "valise rouge"),
    Q("où sont rangés les draps de rechange", "draps de rechange"),
    Q("la machine à coudre est où", "machine à coudre"),
    Q("où est le double des clés", "double des clés"),
    Q("où est la clé à molette", "clé à molette"),
    Q("où sont les papiers de la voiture ?", "papiers de la voiture"),
]

EN = [
    S("spare keys in the top drawer of the dresser", "spare keys", "top drawer of the dresser"),
    S("christmas lights in box 7 on the back shelf in the basement", "christmas lights", "box 7 on the back shelf in the basement"),
    S("passport and health record in the drawer", ["passport", "health record"], "drawer"),
    S("12 M4 screws in the blue bin in the workshop", "M4 screws", "blue bin in the workshop", 12),
    S("the passport is in the safe", "passport", "safe"),
    S("my passport is in the black backpack", "passport", "black backpack"),
    S("the car papers are in the glove box", "car papers", "glove box"),
    S("I put the batteries in the kitchen drawer", "batteries", "kitchen drawer"),
    S("I left the umbrella in the car", "umbrella", "car"),
    S("um the kindle charger is on the desk", "kindle charger", "desk"),
    S("well the drill is in the garage", "drill", "garage"),
    S("the suitcases are on top of the wardrobe", "suitcases", "on top of the wardrobe"),
    S("the yoga mat is under the bed", "yoga mat", "under the bed"),
    S("the ball is behind the sofa", "ball", "behind the sofa"),
    S("the ladder is against the garage wall", "ladder", "against the garage wall"),
    S("the flashlight is next to the basement door", "flashlight", "next to the basement door"),
    S("the fuse box key is at the back of the cupboard", "fuse box key", "at the back of the cupboard"),
    S("the boots are at the bottom of the stairs", "boots", "at the bottom of the stairs"),
    S("the spare bulbs are in the hallway closet", "spare bulbs", "hallway closet"),
    S("the tent is in the blue bag in the basement", "tent", "blue bag in the basement"),
    S("the camping stove is in the camping box in the garage", "camping stove", "camping box in the garage"),
    S("the external hard drive is in the middle drawer", "external hard drive", "middle drawer"),
    S("the white USB-C cable is in the pen holder", "white USB-C cable", "pen holder"),
    S("the tax folder is in the green binder", "tax folder", "green binder"),
    S("keys @ entrance", "keys", "entrance"),
    S("the sewing machine is in the attic", "sewing machine", "attic"),
    S("my watch is in the bowl by the door", "watch", "bowl by the door"),
    S("the spare keys are at my mother's", "spare keys", "mother's"),
    S("there are batteries in the desk drawer", "batteries", "desk drawer"),
    S("I stored the ski boots in the garage loft", "ski boots", "garage loft"),
    S("3 m of HDMI cable under the sofa", "HDMI cable", "under the sofa", 3, "m"),
    S("two pairs of gloves in the hallway drawer", "gloves", "hallway drawer", 2, "pairs"),
    S("5 kg of rice in the bottom cupboard", "rice", "bottom cupboard", 5, "kg"),
    S("four bottles of olive oil in the pantry", "olive oil", "pantry", 4, "bottles"),
    S("2 boxes of nails in the yellow bin", "nails", "yellow bin", 2, "boxes"),
    S("twenty envelopes and 3 stamps in the top drawer", ["envelopes", "stamps"], "top drawer", 20),
    S("hammer and screwdriver in the toolbox", ["hammer", "screwdriver"], "toolbox"),
    S("passport, ID card and driving licence in the red pouch", ["passport", "ID card", "driving licence"], "red pouch"),
    S("mask and snorkel in the beach bag", ["mask", "snorkel"], "beach bag"),
    S("cordless drill", "cordless drill"),
    T("I'm taking the drill", "drill"),
    T("taking the car keys", "car keys"),
    T("I took the projector", "projector"),
    T("I'm taking the tent and the cooler", ["tent", "cooler"]),
    T("grabbing the camera", "camera"),
    R("put the drill back in the garage", "drill", "garage"),
    R("I put the car keys back in the bowl", "car keys", "bowl"),
    R("put back the ladder in the garage", "ladder", "garage"),
    R("the drill is back in the garage", "drill", "garage"),
    R("I returned the tent to the basement", "tent", "basement"),
    R("putting the camera back in the desk drawer", "camera", "desk drawer"),
    R("I put the projector back", "projector"),
    M("the drill is now in the garage", "drill", "garage"),
    M("the skis are now in the attic", "skis", "attic"),
    M("moved the tent to the garage", "tent", "garage"),
    M("I moved the archives to the hallway closet", "archives", "hallway closet"),
    M("the sewing machine is now on the desk", "sewing machine", "desk"),
    M("uh the coffee maker is now on top of the fridge", "coffee maker", "on top of the fridge"),
    L("lent the tent to Mark", "tent", "Mark"),
    L("I lent the projector to Sophie", "projector", "Sophie"),
    L("I've lent the drill to my brother", "drill", "my brother"),
    L("lent the cooler and the camping chairs to Mark", ["cooler", "camping chairs"], "Mark"),
    L("Mark borrowed the tent", "tent", "Mark"),
    RL("Mark returned the tent", "tent", "Mark"),
    RL("Sophie brought the projector back", "projector", "Sophie"),
    RL("Mark gave back the drill", "drill", "Mark"),
    RL("Paul returned the adjustable wrench", "adjustable wrench", "Paul"),
    Q("where is the passport", "passport"),
    Q("where are the car keys?", "car keys"),
    Q("where's the kindle charger", "kindle charger"),
    Q("where did I put my glasses", "glasses"),
    Q("where did I leave the umbrella", "umbrella"),
    Q("I can't find the car papers", "car papers"),
    Q("where do I keep the spare bulbs", "spare bulbs"),
    Q("um where are the christmas lights", "christmas lights"),
    Q("looking for the yoga mat", "yoga mat"),
    Q("where is the drill?", "drill"),
]

def write():
    out = io.open(os.path.join(HERE, "parser.jsonl"), "w", encoding="utf-8", newline="\n")
    n = 0
    for lang, rows in (("fr", FR), ("en", EN)):
        for input, expect in rows:
            n += 1
            line = {"id": "p%04d" % n, "lang": lang, "origin": "synthetic", "input": input, "expect": expect}
            out.write(json.dumps(line, ensure_ascii=False) + "\n")
    out.close()
    print("fr", len(FR), "en", len(EN), "total", n)

if __name__ == "__main__":
    write()
