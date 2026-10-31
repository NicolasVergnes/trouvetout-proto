#!/usr/bin/env python3
"""Construit corpus/photos-cc.jsonl et corpus/PHOTOS-CREDITS.md (jeu d'evaluation photo, B8).

Le depot est PUBLIC : aucune image n'y est commitee. Le jeu liste des URL Open Images V7
(licence CC BY 2.0), leur SHA-256, leur taille, l'attribution ; la CI telecharge les images.

Usage (stdlib seule, a lancer avec `python -I`) :
  python -I corpus/build-photo-corpus.py --meta <dossier metadonnees> --cache <dossier temporaire> [--target 205]

Entrees attendues dans --meta (telechargeables depuis storage.googleapis.com/openimages/, jamais dans le depot) :
  validation-images-with-rotation.csv      (2018_04/validation/)
  test-images-with-rotation.csv            (2018_04/test/)
  validation-annotations-bbox.csv          (v5/)
  test-annotations-bbox.csv                (v5/)
  oidv7-class-descriptions-boxable.csv     (v7/)
Si un fichier manque, --fetch-meta le telecharge. Les etiquettes image-level ne sont pas necessaires :
la selection repose sur les boites englobantes (annotations verifiees par des humains).
Les images sont telechargees dans --cache (hors depot) et reutilisees si deja presentes.

Regles de selection :
  - licence exactement CC BY 2.0 ; images sans rotation EXIF a appliquer ;
  - une boite de la classe de 25 % ou plus de l'image, IsGroupOf == 0, IsDepiction == 0 ;
  - aucune boite d'une autre classe plus grande que cette boite (donc aucune personne plus grande) ;
  - aucune autre classe mappee vers une autre cle (hors `accept`) avec une boite de 25 % ou plus ;
  - au plus 4 images par cle, tirage deterministe (SHA-1 de l'ImageID/de la cle), ~2 images pour chacune de N_KEYS cles non encombrantes ;
  - une image qui ne se telecharge pas est ecartee ; sortie triee par cle puis ImageID.
"""
import argparse
import concurrent.futures
import csv
import hashlib
import json
import os
import sys
import urllib.request

LICENSE_URL = "https://creativecommons.org/licenses/by/2.0/"
MIN_AREA = 0.25
MAX_PER_KEY = 4
S3 = "https://open-images-dataset.s3.amazonaws.com/{subset}/{id}.jpg"
META_BASE = "https://storage.googleapis.com/openimages/"
META_FILES = {
    "validation-images-with-rotation.csv": "2018_04/validation/validation-images-with-rotation.csv",
    "test-images-with-rotation.csv": "2018_04/test/test-images-with-rotation.csv",
    "validation-annotations-bbox.csv": "v5/validation-annotations-bbox.csv",
    "test-annotations-bbox.csv": "v5/test-annotations-bbox.csv",
    "oidv7-class-descriptions-boxable.csv": "v7/oidv7-class-descriptions-boxable.csv",
}

# Classe Open Images -> (cle du catalogue models/labels_fr_en.tsv, autres cles tout aussi justes).
# Une classe sans cle naturelle (Bowl, Bottle, Table, Toy, Lamp generique...) n'est pas mappee.
M = {
    "Accordion": ("accordion", []),
    "Adhesive tape": ("adhesive_tape", ["masking_tape", "packing_tape"]),
    "Alarm clock": ("alarm_clock", ["clock_radio"]),
    "Axe": ("axe", []),
    "Backpack": ("backpack", ["hiking_backpack", "child_s_backpack"]),
    "Balloon": ("balloons", []),
    "Band-aid": ("plasters", ["plaster"]),
    "Barrel": ("barrel", ["cask"]),
    "Bed": ("bed", []),
    "Belt": ("belt", []),
    "Bench": ("bench", []),
    "Bicycle helmet": ("bicycle_helmet", []),
    "Bicycle wheel": ("bike_wheel", ["wheel_rim"]),
    "Binoculars": ("binoculars", ["bird_watching_binoculars"]),
    "Book": ("book", ["novel", "cookbook"]),
    "Bookcase": ("bookcase", []),
    "Boot": ("boots", ["hiking_boots", "rain_boots", "ankle_boots", "riding_boots"]),
    "Bottle opener": ("bottle_opener", ["corkscrew"]),
    "Briefcase": ("briefcase", []),
    "Calculator": ("calculator", ["scientific_calculator"]),
    "Camera": ("camera", ["dslr_camera", "instant_camera", "camcorder"]),
    "Can opener": ("can_opener", []),
    "Candle": ("candle", ["scented_candle", "tealight"]),
    "Canoe": ("canoe", ["kayak"]),
    "Chainsaw": ("chainsaw", []),
    "Chair": ("chair", ["folding_chair", "garden_chair", "office_chair"]),
    "Chest of drawers": ("chest_of_drawers", ["dresser"]),
    "Chisel": ("wood_chisel", ["cold_chisel"]),
    "Chopsticks": ("chopsticks", []),
    "Christmas tree": ("christmas_tree", ["artificial_christmas_tree"]),
    "Cocktail shaker": ("cocktail_shaker", []),
    "Coat": ("coat", ["winter_coat", "trench_coat", "parka"]),
    "Coffee cup": ("coffee_cup", ["mug", "tea_cup"]),
    "Coffee table": ("coffee_table", []),
    "Coffeemaker": ("coffee_machine", ["drip_coffee_maker", "espresso_machine", "pod_coffee_maker", "capsule_coffee_machine"]),
    "Coin": ("coins", []),
    "Computer keyboard": ("keyboard", ["wireless_keyboard"]),
    "Computer monitor": ("computer_monitor", ["television"]),
    "Computer mouse": ("mouse", ["wireless_mouse"]),
    "Corded phone": ("landline_phone", ["cordless_phone"]),
    "Couch": ("sofa", []),
    "Crutch": ("crutches", ["walking_stick"]),
    "Curtain": ("curtain", []),
    "Cutting board": ("cutting_board", []),
    "Desk": ("desk", []),
    "Diaper": ("nappies", []),
    "Dice": ("dice", []),
    "Dishwasher": ("dishwasher", []),
    "Dog bed": ("dog_bed", []),
    "Doll": ("doll", ["rag_doll"]),
    "Door handle": ("door_handle", ["door_knob", "lever_handle", "window_handle"]),
    "Dress": ("dress", ["evening_gown"]),
    "Drill (Tool)": ("power_drill", ["cordless_drill"]),
    "Dumbbell": ("dumbbells", ["kettlebell"]),
    "Earring": ("earrings", []),
    "Envelope": ("envelopes", ["letter"]),
    "Eraser": ("eraser", []),
    "Face powder": ("face_powder", []),
    "Filing cabinet": ("filing_cabinet", []),
    "Flag": ("flag", []),
    "Flashlight": ("torch", ["wind_up_torch", "headlamp"]),
    "Flowerpot": ("flower_pot", ["terracotta_pot", "indoor_plant_pot"]),
    "Flute": ("flute", ["recorder"]),
    "Flying disc": ("frisbee", []),
    "Food processor": ("food_processor", ["kitchen_machine"]),
    "Footwear": ("shoes", ["trainers", "sports_shoes", "running_shoes", "loafers", "boots", "sandals"]),
    "Fork": ("fork", []),
    "Frying pan": ("frying_pan", ["saute_pan", "crepe_pan", "grill_pan"]),
    "Gas stove": ("gas_stove", ["cooker"]),
    "Glasses": ("prescription_glasses", ["reading_glasses", "safety_glasses"]),
    "Glove": ("gloves", ["leather_gloves", "work_gloves", "gardening_gloves", "rubber_gloves"]),
    "Golf ball": ("golf_ball", []),
    "Guitar": ("guitar", ["classical_guitar", "electric_guitar", "bass_guitar"]),
    "Hair dryer": ("hair_dryer", []),
    "Hammer": ("hammer", ["claw_hammer", "rubber_mallet", "mallet"]),
    "Handbag": ("handbag", ["shoulder_bag", "clutch_bag"]),
    "Harmonica": ("harmonica", []),
    "Hat": ("hat", ["sun_hat", "bucket_hat", "beanie", "cap"]),
    "Headphones": ("headphones", ["noise_cancelling_headphones", "wireless_headphones", "earphones"]),
    "Heater": ("electric_heater", ["fan_heater"]),
    "High heels": ("high_heels", []),
    "Houseplant": ("houseplant", ["indoor_plant_pot"]),
    "Humidifier": ("humidifier", ["essential_oil_diffuser"]),
    "Indoor rower": ("rowing_machine", []),
    "Infant bed": ("cot", ["travel_cot", "moses_basket", "cradle"]),
    "Jacket": ("jacket", ["denim_jacket", "leather_jacket", "windbreaker", "puffer_jacket"]),
    "Jeans": ("jeans", []),
    "Kettle": ("electric_kettle", ["stovetop_kettle"]),
    "Kitchen & dining room table": ("dining_table", []),
    "Kitchen knife": ("chef_s_knife", ["bread_knife", "paring_knife", "steak_knife", "table_knife"]),
    "Kite": ("kite", []),
    "Ladder": ("ladder", ["step_ladder", "telescopic_ladder"]),
    "Ladle": ("ladle", []),
    "Lantern": ("lantern", ["camping_lantern"]),
    "Laptop": ("laptop", []),
    "Light bulb": ("led_bulb", ["e27_bulb", "e14_bulb", "halogen_bulb", "screw_bulb", "bayonet_bulb"]),
    "Light switch": ("light_switch", ["dimmer_switch"]),
    "Lipstick": ("lipstick", []),
    "Maraca": ("maracas", []),
    "Measuring cup": ("measuring_cups", ["measuring_jug"]),
    "Mechanical fan": ("fan", []),
    "Microphone": ("microphone", ["vocal_microphone"]),
    "Microwave oven": ("microwave_oven", []),
    "Miniskirt": ("skirt", []),
    "Mirror": ("mirror", ["wall_mirror", "bathroom_mirror"]),
    "Mixer": ("stand_mixer", ["electric_hand_mixer", "kitchen_machine"]),
    "Mixing bowl": ("mixing_bowl", ["salad_bowl"]),
    "Mobile phone": ("mobile_phone", ["smartphone"]),
    "Mug": ("mug", ["coffee_cup", "tea_cup", "beer_mug"]),
    "Musical keyboard": ("electronic_keyboard", ["digital_piano"]),
    "Nail (Construction)": ("nail", []),
    "Necklace": ("necklace", ["chain_necklace", "pearl_necklace", "pendant"]),
    "Nightstand": ("bedside_table", []),
    "Oven": ("oven", ["electric_oven", "toaster_oven"]),
    "Paper towel": ("paper_towel_roll", []),
    "Pen": ("pen", ["ballpoint_pen", "fountain_pen"]),
    "Pencil case": ("school_pencil_case", ["pen_pot"]),
    "Pencil sharpener": ("pencil_sharpener", []),
    "Perfume": ("perfume", ["eau_de_toilette"]),
    "Piano": ("piano", ["digital_piano"]),
    "Picture frame": ("picture_frame", ["frame"]),
    "Pillow": ("pillow", ["cushion", "bolster"]),
    "Pizza cutter": ("pizza_cutter", []),
    "Plastic bag": ("plastic_bag", ["shopping_bag"]),
    "Plate": ("dinner_plate", ["dessert_plate", "soup_plate"]),
    "Poster": ("poster", []),
    "Pressure cooker": ("pressure_cooker", []),
    "Printer": ("printer", []),
    "Punching bag": ("punching_bag", []),
    "Ratchet (Device)": ("ratchet", ["ratchet_wrench", "socket_set"]),
    "Refrigerator": ("fridge", ["freezer", "wine_fridge"]),
    "Remote control": ("remote_control", ["tv_remote"]),
    "Ring binder": ("ring_binder", ["binder", "lever_arch_file"]),
    "Roller skates": ("roller_skates", ["inline_skates"]),
    "Rugby ball": ("rugby_ball", []),
    "Ruler": ("ruler", ["folding_ruler"]),
    "Salt and pepper shakers": ("salt_shaker", ["pepper_shaker"]),
    "Sandal": ("sandals", ["flip_flops", "slides"]),
    "Saucer": ("saucer", []),
    "Saxophone": ("saxophone", []),
    "Scale": ("bathroom_scale", ["kitchen_scale", "smart_scale"]),
    "Scarf": ("scarf", ["neck_scarf"]),
    "Scissors": ("scissors", ["kitchen_scissors", "nail_scissors", "dressmaking_shears"]),
    "Screwdriver": ("screwdriver", ["flathead_screwdriver", "phillips_screwdriver", "screwdriver_set"]),
    "Sculpture": ("sculpture", ["statuette"]),
    "Serving tray": ("serving_tray", []),
    "Sewing machine": ("sewing_machine", []),
    "Shelf": ("shelf", []),
    "Shirt": ("shirt", ["t_shirt", "polo_shirt", "blouse"]),
    "Shorts": ("shorts", ["bermuda_shorts", "swim_shorts"]),
    "Skateboard": ("skateboard", ["longboard"]),
    "Ski": ("skis", []),
    "Skirt": ("skirt", []),
    "Slow cooker": ("slow_cooker", []),
    "Snowboard": ("snowboard", []),
    "Soap dispenser": ("soap_dispenser", ["wall_soap_dispenser", "liquid_soap"]),
    "Sock": ("socks", []),
    "Sofa bed": ("sofa", []),
    "Spatula": ("wooden_spatula", ["silicone_spatula", "crepe_spatula"]),
    "Spice rack": ("spice_rack", []),
    "Spoon": ("spoon", ["tablespoon", "teaspoon", "wooden_spoon"]),
    "Stapler": ("stapler", []),
    "Stationary bicycle": ("exercise_bike", []),
    "Stool": ("stool", ["bar_stool"]),
    "Suit": ("suit", ["blazer"]),
    "Suitcase": ("suitcase", ["soft_suitcase", "hard_suitcase", "cabin_suitcase"]),
    "Sun hat": ("sun_hat", ["straw_hat", "hat"]),
    "Sunglasses": ("sunglasses", []),
    "Surfboard": ("surfboard", ["paddleboard"]),
    "Swim cap": ("swim_cap", []),
    "Table tennis racket": ("table_tennis_bat", []),
    "Tablet computer": ("tablet", []),
    "Tap": ("tap", ["mixer_tap"]),
    "Teapot": ("teapot", []),
    "Teddy bear": ("stuffed_toy", ["comfort_blanket_toy"]),
    "Television": ("television", ["computer_monitor"]),
    "Tennis ball": ("tennis_ball", []),
    "Tennis racket": ("tennis_racket", ["badminton_racket"]),
    "Tent": ("tent", ["camping_tent", "pop_up_tent"]),
    "Tie": ("tie", []),
    "Tin can": ("tin_can", ["tinned_food", "metal_tin"]),
    "Toaster": ("toaster", ["toaster_oven"]),
    "Toilet paper": ("toilet_paper", []),
    "Toothbrush": ("toothbrush", ["electric_toothbrush"]),
    "Torch": ("torch", ["wind_up_torch", "headlamp"]),
    "Towel": ("bath_towel", ["hand_towel", "beach_towel", "tea_towel"]),
    "Training bench": ("weight_bench", []),
    "Treadmill": ("treadmill", []),
    "Tripod": ("tripod", []),
    "Trombone": ("trombone", []),
    "Trousers": ("trousers", ["chinos", "dress_trousers"]),
    "Trumpet": ("trumpet", []),
    "Umbrella": ("umbrella", ["parasol"]),
    "Vase": ("vase", []),
    "Violin": ("violin", ["viola"]),
    "Volleyball (Ball)": ("volleyball", []),
    "Waffle iron": ("waffle_iron", []),
    "Wall clock": ("wall_clock", ["mantel_clock"]),
    "Wardrobe": ("wardrobe", []),
    "Washing machine": ("washing_machine", []),
    "Watch": ("watch", ["smartwatch"]),
    "Whisk": ("whisk", []),
    "Whiteboard": ("whiteboard", []),
    "Window blind": ("blind", ["venetian_blind", "roller_blind"]),
    "Wine glass": ("wine_glass", ["stemmed_glasses"]),
    "Wine rack": ("wine_rack", []),
    "Wok": ("wok", []),
    "Wrench": ("adjustable_spanner", ["open_end_spanner", "socket_spanner", "combination_spanner", "ratchet_wrench"]),
}

# Gros mobilier, electromenager encastre ou fixe : peu pertinents pour "photographier un objet que l'on range".
# Ils ne sont retenus qu'en dernier recours (jamais tant que d'autres cles suffisent).
LARGE_KEYS = {
    "bed", "bench", "wardrobe", "flag", "poster", "sculpture", "canoe", "barrel", "christmas_tree", "curtain",
    "shelf", "bookcase", "desk", "dining_table", "coffee_table", "sofa", "chest_of_drawers", "filing_cabinet",
    "dishwasher", "washing_machine", "fridge", "oven", "gas_stove", "treadmill", "rowing_machine", "exercise_bike",
    "bedside_table", "blind", "wall_clock", "houseplant", "punching_bag", "weight_bench", "cot", "tap",
    "door_handle", "light_switch", "wine_rack", "spice_rack", "kite", "surfboard", "snowboard", "skis", "tent",
    "chair", "stool", "television", "computer_monitor", "mirror", "picture_frame", "whiteboard", "bike_wheel",
}
N_KEYS = 103  # nombre de cles retenues : environ 2 images par cle pour une cible de ~205

PERSON_PREFIXES = ("Person", "Man", "Woman", "Boy", "Girl", "Human ")


def sha1(s):
    return hashlib.sha1(s.encode()).hexdigest()


def fetch_meta(meta):
    os.makedirs(meta, exist_ok=True)
    for name, rel in META_FILES.items():
        dest = os.path.join(meta, name)
        if not os.path.exists(dest) or os.path.getsize(dest) == 0:
            print("download", rel, file=sys.stderr)
            urllib.request.urlretrieve(META_BASE + rel, dest)


def load_catalog(path):
    keys = set()
    with open(path, encoding="utf-8") as f:
        next(f)
        for line in f:
            if line.strip():
                keys.add(line.split("\t")[0])
    return keys


def check_mapping(keys, class_names):
    problems = []
    for cls, (key, accept) in M.items():
        if cls not in class_names:
            problems.append("classe inconnue: " + cls)
        for k in [key] + list(accept):
            if k not in keys:
                problems.append("cle absente du catalogue: %s (%s)" % (k, cls))
    if problems:
        raise SystemExit("\n".join(problems))


def select(meta, keys):
    with open(os.path.join(meta, "oidv7-class-descriptions-boxable.csv"), encoding="utf-8", newline="") as f:
        rows = list(csv.reader(f))
    mid2name = {r[0]: r[1] for r in rows if r and r[0].startswith("/")}
    check_mapping(keys, set(mid2name.values()))
    persons = {m for m, n in mid2name.items() if n.startswith(PERSON_PREFIXES)}
    candidates = {}  # key -> list of dict
    for subset in ("validation", "test"):
        images = {}
        with open(os.path.join(meta, subset + "-images-with-rotation.csv"), encoding="utf-8", newline="") as f:
            for r in csv.DictReader(f):
                if r["License"] != LICENSE_URL:
                    continue
                if r.get("Rotation") not in ("", "0", "0.0", None):
                    continue
                images[r["ImageID"]] = r
        boxes = {}
        with open(os.path.join(meta, subset + "-annotations-bbox.csv"), encoding="utf-8", newline="") as f:
            for r in csv.DictReader(f):
                iid = r["ImageID"]
                if iid not in images:
                    continue
                area = (float(r["XMax"]) - float(r["XMin"])) * (float(r["YMax"]) - float(r["YMin"]))
                boxes.setdefault(iid, []).append((r["LabelName"], area, r["IsGroupOf"] == "1", r["IsDepiction"] == "1"))
        for iid in sorted(boxes):
            bl = boxes[iid]
            best = None
            for lab, area, grp, dep in bl:
                name = mid2name.get(lab)
                if name in M and area >= MIN_AREA and not grp and not dep and (best is None or area > best[1]):
                    best = (lab, area, name)
            if best is None:
                continue
            lab, area, name = best
            key, accept = M[name]
            ok = True
            for lab2, area2, grp2, dep2 in bl:
                if lab2 == lab:
                    continue
                if area2 > area:  # une autre classe (dont une personne) couvre davantage l'image
                    ok = False
                    break
                n2 = mid2name.get(lab2)
                if n2 in M and area2 >= MIN_AREA and M[n2][0] != key and M[n2][0] not in accept and lab2 not in persons:
                    ok = False  # deuxieme sujet principal ambigu
                    break
            if not ok:
                continue
            r = images[iid]
            candidates.setdefault(key, []).append({
                "id": "oi-" + iid, "iid": iid, "subset": subset, "expect": key, "accept": [a for a in accept if a != key],
                "class": name, "author": r["Author"].strip(), "authorUrl": r["AuthorProfileURL"].strip(),
                "source": r["OriginalLandingURL"].strip(), "title": r["Title"].strip(), "area": area,
            })
    for k in candidates:
        candidates[k].sort(key=lambda c: sha1(c["iid"]))
    return candidates


def round_robin(candidates, target):
    eligible = [k for k in candidates if k not in LARGE_KEYS and len(candidates[k]) >= 2]
    order = sorted(eligible, key=sha1)[:N_KEYS]
    picked, rank = [], 0
    while len(picked) < target and rank < MAX_PER_KEY:
        progress = False
        for k in order:
            if rank < len(candidates[k]) and len(picked) < target:
                picked.append(candidates[k][rank])
                progress = True
        if not progress:
            break
        rank += 1
    return picked


def download(c, cache):
    url = S3.format(subset=c["subset"], id=c["iid"])
    path = os.path.join(cache, c["iid"] + ".jpg")
    try:
        if not os.path.exists(path) or os.path.getsize(path) == 0:
            req = urllib.request.Request(url, headers={"User-Agent": "trouvetout-corpus-builder"})
            with urllib.request.urlopen(req, timeout=60) as resp:
                data = resp.read()
            if len(data) < 1000 or data[:2] != b"\xff\xd8":
                return None
            with open(path, "wb") as f:
                f.write(data)
        else:
            with open(path, "rb") as f:
                data = f.read()
        return url, hashlib.sha256(data).hexdigest(), len(data)
    except Exception as e:  # noqa: BLE001
        print("drop", c["iid"], type(e).__name__, file=sys.stderr)
        return None


def md_escape(s):
    for ch in "\\`*_[]<>|":
        s = s.replace(ch, "\\" + ch)
    return " ".join(s.split())


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--meta", required=True)
    ap.add_argument("--cache", required=True)
    ap.add_argument("--labels", default=os.path.join(here, "..", "models", "labels_fr_en.tsv"))
    ap.add_argument("--out", default=here)
    ap.add_argument("--target", type=int, default=205)
    ap.add_argument("--fetch-meta", action="store_true")
    a = ap.parse_args()
    if a.fetch_meta:
        fetch_meta(a.meta)
    os.makedirs(a.cache, exist_ok=True)
    cands = select(a.meta, load_catalog(a.labels))
    picked = round_robin(cands, a.target)
    print("candidats: %d cles, %d images ; retenus avant telechargement: %d" % (len(cands), sum(map(len, cands.values())), len(picked)), file=sys.stderr)
    with concurrent.futures.ThreadPoolExecutor(8) as ex:
        results = list(ex.map(lambda c: download(c, a.cache), picked))
    final = []
    for c, res in zip(picked, results):
        if res:
            c["url"], c["sha256"], c["bytes"] = res
            final.append(c)
    final.sort(key=lambda c: (c["expect"], c["iid"]))
    with open(os.path.join(a.out, "photos-cc.jsonl"), "w", encoding="utf-8", newline="\n") as f:
        for c in final:
            f.write(json.dumps({
                "id": c["id"], "url": c["url"], "sha256": c["sha256"], "bytes": c["bytes"], "expect": c["expect"],
                "accept": c["accept"], "author": c["author"], "authorUrl": c["authorUrl"], "source": c["source"],
                "license": "CC BY 2.0", "class": c["class"],
            }, ensure_ascii=False) + "\n")
    lines = [
        "# Crédits du jeu de photos d'évaluation (B8)\n",
        "Ces photos ne sont **pas** dans le dépôt. Elles sont téléchargées uniquement pour l'évaluation automatique "
        "du nommage d'un objet par photo (comparaison à `models/labels_fr_en.tsv`) ; aucune n'est redistribuée ni "
        "embarquée dans l'application. Elles proviennent d'Open Images V7 (sous-ensembles validation et test) et sont "
        "placées par leurs auteurs sous licence [CC BY 2.0](https://creativecommons.org/licenses/by/2.0/). "
        "La liste exacte (URL, SHA-256, taille, clé attendue) est dans `corpus/photos-cc.jsonl`, générée par "
        "`corpus/build-photo-corpus.py`.\n",
        "Chaque entrée indique l'identifiant, l'auteur, le titre avec le lien vers la page d'origine (Flickr), "
        "la clé attendue du catalogue et la licence.\n",
        "## Photos (%d)\n" % len(final),
    ]
    for c in final:
        title = md_escape(c["title"]) or "(sans titre)"
        author = md_escape(c["author"]) or "(auteur inconnu)"
        lines.append("- `%s` : %s, [%s](%s), %s, CC BY 2.0" % (
            c["id"], "[%s](%s)" % (author, c["authorUrl"]) if c["authorUrl"] else author, title, c["source"], "`%s`" % c["expect"]))
    with open(os.path.join(a.out, "PHOTOS-CREDITS.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    print("ecrit: %d images, %d cles" % (len(final), len({c["expect"] for c in final})), file=sys.stderr)


if __name__ == "__main__":
    main()
