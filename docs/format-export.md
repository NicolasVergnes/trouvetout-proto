# Format des données — schéma SQL et export (A6, A7, A8, N9)

Version 1 du format, figée au lot 1. Ce document est public (J7) : il doit permettre de lire ses données **sans l'app**.
Règle N9 : un export v1 s'importe dans toutes les versions suivantes ; le format n'évolue qu'en **ajoutant** des champs
ou des types d'événements, jamais en renommant ou en supprimant. Un lecteur ignore les champs qu'il ne connaît pas.

## 1. Principe

Le **journal d'événements** est la source de vérité : chaque action est un événement immuable, daté et signé par
l'appareil. Les tables `item`, `place`, `container`, `location_state`, `photo`, `reminder` sont une **projection**
reconstruite à l'identique en rejouant le journal trié par `(at, device_id, id)`. L'import rejoue les événements dont
l'`id` est inconnu ; l'union de deux journaux converge quel que soit l'ordre d'arrivée.

## 2. Événement

Un événement (`events.jsonl` : un objet JSON par ligne ; table `event` : une ligne) :

| Champ | Type | Sens |
| --- | --- | --- |
| `id` | ULID (26 caractères Crockford) | identifiant unique, trié par temps de création |
| `at` | entier | instant UTC en millisecondes depuis l'époque |
| `deviceId` | ULID | identité de l'installation : nombre aléatoire généré sur l'appareil au premier lancement, **non dérivé** d'un identifiant matériel ou de compte (ni Android ID, ni IDFV), jamais transmis ; présent dans `manifest.json` et sur chaque ligne du journal ; comme tout ULID, ses 10 premiers caractères encodent la date de création à la milliseconde ; régénéré après une suppression totale (H6) |
| `actor` | texte | `"me"` en v1 (réservé au foyer) |
| `type` | texte | nom du type, égal au discriminant `type` du payload |
| `payload` | objet | champs propres au type (voir §3) ; contient aussi `"type"` |
| `source` | `VOICE` \| `TEXT` \| `PHOTO` \| `WIDGET` \| `IMPORT` \| `SYSTEM` | canal d'entrée |
| `rawInput` | texte, optionnel | phrase dictée ou tapée telle quelle |

Exemple :

```json
{"id":"01JAXK3Q9S4M7V2R8T1N6P5ZCD","at":1761312000000,"deviceId":"01JAXK0000AB12CD34EF56GH78","actor":"me","type":"ItemStored","payload":{"type":"ItemStored","itemId":"01JAXK3Q9R…","placeId":"01JAXK2A…"},"source":"VOICE","rawInput":"passeport dans le tiroir du haut de la commode"}
```

## 3. Types d'événements et payloads (v1)

Les identifiants (`itemId`, `placeId`, `containerId`, `photoId`, `reminderId`) sont des ULID. Les champs marqués
« optionnel » peuvent être absents ou `null`.

### Objets
| Type | Champs | Effet sur la projection |
| --- | --- | --- |
| `ItemCreated` | `itemId`, `name`, `description?`, `tags[]`, `quantity?`, `unit?`, `sensitive` | crée la fiche ; état `STORED` sans lieu |
| `ItemUpdated` | `itemId`, `name?`, `description?`, `clearDescription`, `tags[]?`, `quantity?`, `clearQuantity`, `unit?`, `sensitive?`, `pinned?` | met à jour les champs présents (patch) |
| `ItemStored` | `itemId`, `placeId?` ou `containerId?` | état `STORED` au lieu ou dans le contenant donné |
| `ItemMovedTo` | idem `ItemStored` | même effet, libellé « déplacé » dans l'historique |
| `ItemTaken` | `itemId` | état `TAKEN`, le dernier lieu connu est conservé |
| `ItemReturned` | `itemId` | état `STORED` au dernier lieu connu ; fin d'un prêt |
| `ItemLent` | `itemId`, `to` | état `LENT`, personne mémorisée |
| `ItemArchived` | `itemId`, `archived` (défaut `true`) | drapeau `archived` de la fiche ; l'état de lieu (et un prêt en cours) est inchangé |
| `ItemDeleted` | `itemId` | corbeille (`deleted_at`) ; lieu et historique conservés |
| `ItemRestored` | `itemId` | sortie de corbeille |

### Lieux et contenants
| Type | Champs | Effet |
| --- | --- | --- |
| `PlaceCreated` | `placeId`, `name`, `parentId?`, `kind` (`ROOT`/`ROOM`/`FURNITURE`/`SPOT`), `aliases[]` | crée le lieu |
| `PlaceRenamed` | `placeId`, `name`, `aliases[]` | nouveau nom et liste complète d'alias |
| `PlaceMerged` | `placeId`, `intoPlaceId` | enfants, contenants et objets passent dans la cible ; la source est retirée (`merged_into`) et ses noms deviennent des alias de la cible |
| `PlaceDeleted` | `placeId` | retire un lieu vide |
| `ContainerCreated` | `containerId`, `label`, `placeId`, `number?` | crée le contenant |
| `ContainerMoved` | `containerId`, `placeId` | déplace le contenant **et tout son contenu** (un seul événement) |
| `ContainerDeleted` | `containerId` | retire un contenant vide |

### Photos et rappels
| Type | Champs | Effet |
| --- | --- | --- |
| `PhotoAttached` | `photoId`, `ownerType` (`ITEM`/`PLACE`/`CONTAINER`), `ownerId`, `fileName`, `thumbName`, `width`, `height`, `sha256` | attache une photo stockée dans `photos/` |
| `PhotoDetached` | `photoId` | détache (le fichier est purgé après 30 jours, lot 2) |
| `ReminderSet` | `reminderId`, `itemId`, `fireAtEpochMillis`, `kind` (`DATE`/`CHECK`) | programme un rappel |
| `ReminderDone` | `reminderId` | clôt le rappel |

Un lecteur qui rencontre un type inconnu (ou un payload qu'il ne sait pas décoder) **le conserve tel quel** dans son
journal, l'ignore dans la projection et le réécrit à l'identique dans ses exports : rien n'est perdu en passant par une
version plus ancienne. Une `source` inconnue est lue comme `SYSTEM`. L'import refuse un export dont `formatVersion`
est supérieur à ce qu'il sait lire.

## 4. Schéma SQLite (version 1)

Instants en millisecondes UTC (`INTEGER`), booléens en `INTEGER` 0/1, listes en JSON (`TEXT`).
Les fichiers de référence sont `shared/src/commonMain/sqldelight/app/trouvetout/core/data/db/*.sq`.

```sql
CREATE TABLE event (
  id TEXT NOT NULL PRIMARY KEY, at INTEGER NOT NULL, device_id TEXT NOT NULL,
  actor TEXT NOT NULL DEFAULT 'me', type TEXT NOT NULL, payload TEXT NOT NULL,
  source TEXT NOT NULL, raw_input TEXT);
CREATE INDEX event_order ON event(at, device_id, id);

CREATE TABLE event_subject (            -- index des fiches touchées par un événement (historique A5)
  event_id TEXT NOT NULL, subject_type TEXT NOT NULL, subject_id TEXT NOT NULL,
  PRIMARY KEY (event_id, subject_type, subject_id));

CREATE TABLE place (
  id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, parent_id TEXT, kind TEXT NOT NULL,
  aliases TEXT NOT NULL DEFAULT '[]', created_at INTEGER NOT NULL, deleted_at INTEGER, merged_into TEXT);

CREATE TABLE container (
  id TEXT NOT NULL PRIMARY KEY, label TEXT NOT NULL, number INTEGER, place_id TEXT NOT NULL,
  created_at INTEGER NOT NULL, deleted_at INTEGER);

CREATE TABLE item (
  id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, description TEXT, tags TEXT NOT NULL DEFAULT '[]',
  quantity REAL, unit TEXT, sensitive INTEGER NOT NULL DEFAULT 0, pinned INTEGER NOT NULL DEFAULT 0,
  archived INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, deleted_at INTEGER);

CREATE TABLE location_state (           -- exactement une ligne par objet
  item_id TEXT NOT NULL PRIMARY KEY, place_id TEXT, container_id TEXT,
  state TEXT NOT NULL,                  -- STORED | TAKEN | LENT (l'archivage est le drapeau item.archived)
  lent_to TEXT, since INTEGER NOT NULL);

CREATE TABLE photo (
  id TEXT NOT NULL PRIMARY KEY, owner_type TEXT NOT NULL, owner_id TEXT NOT NULL,
  file_name TEXT NOT NULL, thumb_name TEXT NOT NULL, width INTEGER NOT NULL, height INTEGER NOT NULL,
  sha256 TEXT NOT NULL, created_at INTEGER NOT NULL, detached_at INTEGER);

CREATE TABLE reminder (
  id TEXT NOT NULL PRIMARY KEY, item_id TEXT NOT NULL, fire_at INTEGER NOT NULL,
  kind TEXT NOT NULL, done INTEGER NOT NULL DEFAULT 0);

CREATE TABLE meta (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL);
-- clés : schema_version, device_id (puis model_variant, trial_started_at…)
```

Un objet rangé dans un contenant a `container_id` renseigné et `place_id` à `NULL` : son lieu est celui du contenant.
La table de recherche plein texte (`item_fts`) arrive au lot 6 par migration additive.

## 5. Export ZIP (lot 2)

`trouvetout-export-AAAAMMJJ-HHMMSS.zip` (heure UTC) :

```
manifest.json     { "formatVersion": 1, "appVersion": "...", "schemaVersion": 1, "exportedAt": <ms UTC>,
                    "deviceId": "...", "counts": { "events": n, "items": n, "places": n, "containers": n, "photos": n } }
events.jsonl      journal complet, un événement par ligne (§2), trié par (at, deviceId, id)
snapshot.json     projection lisible : { "items", "places", "containers", "locations", "photos" } — jamais importé
photos/<id>.jpg   originaux recompressés (côté long ≤ 2048 px, JPEG ~85, sans les métadonnées d'origine) ;
                  les miniatures ne sont pas exportées, l'app les régénère à l'import
```

Lire ses données sans l'app : `events.jsonl` est du JSON Lines ; `snapshot.json` donne l'état courant en un coup d'œil.

### Import (A8) — ce que l'app accepte

- Seuls `manifest.json`, `events.jsonl` et `photos/*.jpg` sont lus ; toute autre entrée est ignorée (un lecteur v1
  tolère les ajouts des versions suivantes). `snapshot.json` n'est jamais importé.
- `manifest.json` doit être présent et valide ; un `formatVersion` supérieur à celui de l'app est refusé avec un message
  clair (mettre l'app à jour). Les champs inconnus du manifeste et des événements sont ignorés.
- Les événements sont rejoués dans l'ordre `(at, deviceId, id)` ; un événement dont l'`id` est déjà dans le journal est
  ignoré (fusion naturelle) ; un `type` inconnu est conservé tel quel ; une ligne illisible est comptée et ignorée.
- Les photos sont ré-encodées par l'app (jamais copiées telles quelles) et les miniatures régénérées ; un nom hors du
  motif `<ULID>.jpg` / `<ULID>_t.jpg` (dans l'archive comme dans un événement `PhotoAttached`) ou une image
  indécodable est ignoré.
- Limites : archive ≤ 1 Gio, 200 000 entrées, manifeste ≤ 1 Mio, `events.jsonl` ≤ 64 Mio et ≤ 1 000 000 lignes de
  ≤ 64 Kio, photo ≤ 48 Mio. Au-delà, l'archive est refusée entière (`EVENTS_TOO_LARGE`, `TOO_MANY_EVENTS`) ; une
  entrée `events.jsonl` abîmée (somme de contrôle) aussi (`EVENTS_CORRUPT`).
- Le rapport affiché compte : événements ajoutés, déjà connus, ignorés ; photos ajoutées, ignorées.

## 6. Photos (A10)

Chaque photo est ré-encodée à l'enregistrement : les métadonnées d'origine (position GPS, appareil, date de prise de
vue, vignette intégrée) disparaissent ; seul un en-tête technique minimal (dimensions, orientation normale, profil
couleur) peut subsister selon la plateforme. Le fichier est nommé par un ULID, stocké dans `<répertoire privé>/photos/`
hors des sauvegardes système, avec sa miniature et son empreinte SHA-256.

## 7. Quelles données personnelles contient un export

Tout ce qui s'y trouve a été saisi par l'utilisateur ou dérivé de sa saisie ; rien ne provient d'un capteur, d'un compte,
d'un contact ou d'un identifiant système :

- le texte libre tapé ou dicté : `name`, `description`, `tags`, noms et alias de lieux (y compris les alias mémorisés
  lors d'une désambiguïsation, B7), libellés de contenants, et la
  phrase complète `rawInput` **telle quelle** (elle peut contenir tout ce que l'utilisateur a dit) ;
- les prénoms ou noms de tiers saisis lors d'un prêt (`ItemLent.to`, `location_state.lent_to`) ;
- les horodatages d'activité (`at`, `since`, `created_at`, `fire_at`, en millisecondes UTC) ;
- les photos ré-encodées (§6) ;
- le `deviceId` (§2).

Le droit d'accès et la portabilité sont satisfaits par l'export lui-même ; l'effacement par la suppression totale (H6).
