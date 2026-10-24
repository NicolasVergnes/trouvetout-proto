---
name: local-first-data
description: Modèle de données local-first de TrouveTout — journal d'événements, projection SQLite (SQLDelight), export/import ZIP, migrations additives. Utiliser pour tout travail dans shared/data, shared/domain, les fichiers .sq/.sqm, l'export, l'import, la corbeille ou la suppression totale.
---

# Données local-first

Référence complète : `docs/01-architecture.md` §3 (entités, journal, schéma, export). Ce skill résume les règles d'implémentation.

## Principes
1. **Le journal `event` est la source de vérité.** Toute action utilisateur produit un ou plusieurs `Event` immuables (ULID, `at` UTC, `deviceId`, `actor`, `type`, `payload` JSON, `source`, `rawInput?`).
2. **L'état courant est une projection** (`item`, `place`, `container`, `location_state`, `photo`, `reminder`). Une seule classe, `Projector`, applique un événement à l'état. `replay(events)` depuis zéro doit redonner exactement l'état : c'est un test obligatoire.
3. **Rien n'est écrasé.** Supprimer = `ItemDeleted` (+ `deleted_at`), restaurer = `ItemRestored`. La purge physique ne concerne que les fichiers photo après 30 jours (corbeille) ou la suppression totale.
4. **Multi-appareils prêt sans être livré** : l'union de deux journaux triée par `(at, deviceId, id)` doit converger (test de propriété au lot 1 même si la sync est en P3).

## Écrire une fonctionnalité qui modifie des données
```
UseCase (shared/domain/usecase)      → construit les Event(s)
EventStore.append(events)            → transaction : INSERT event + Projector.apply
Repositories (lecture seule)          → Flow<...> sur les tables projetées
```
- Un use case ne touche jamais directement aux tables projetées.
- Un événement composite (ex. `ContainerMoved`) est **un seul** événement ; la projection met à jour tous les objets du contenant (D4).
- Les champs dérivés (chemin complet du lieu, compteurs) sont calculés en lecture ou mis en cache dans la projection, jamais stockés dans le payload.

## SQLDelight
- Un fichier `.sq` par table ; requêtes nommées, paramétrées ; `SELECT *` interdit dans les requêtes de liste (projeter les colonnes).
- FTS5 : table `item_fts` externe (content=''), alimentée par la projection ; requête utilisateur transformée en `"tok1"* "tok2"*` après suppression de `" * ( ) : ^ -` ; `remove_diacritics 2`.
- Migrations `.sqm` additives ; `schema_version` dans `meta` ; test `MigrationTest` qui ouvre une base de chaque version passée.
- Transactions pour toute écriture multi-tables ; WAL activé ; `PRAGMA foreign_keys=ON`.
- Drivers : `AndroidSqliteDriver` / `NativeSqliteDriver` ; au lot 8, SQLCipher (clé Keystore/Keychain) via les drivers chiffrés, avec migration en place de la base claire.

## Photos (A10)
- Décodage → redimensionnement (côté long ≤ 2 048 px) → JPEG q≈85 ; miniature 256 px ; nom = ULID ; `sha256` stocké.
- Répertoire `photos/` dans le conteneur privé non sauvegardé ; jamais la galerie ; EXIF supprimé (pas de GPS).

## Export / import (A7, A8, N9)
- Export = ZIP en flux : `manifest.json`, `events.jsonl` (tout le journal), `snapshot.json` (projection lisible), `photos/`.
- Import = validation (`formatVersion` ≤ supporté, tailles, chemins sans `..`, nombre d'entrées, décompression bornée) → rejeu des événements inconnus par `id` → copie des photos référencées → reprojection.
- Tolérer les champs inconnus (lecture permissive) ; refuser un événement de `type` inconnu seulement si `formatVersion` est supérieur au nôtre, sinon l'ignorer avec un compteur dans le rapport d'import.
- Test obligatoire : export → base vide → import → état identique (égalité structurelle), sur les deux plateformes.

## Suppression totale (H6)
Ordre : fermer la base → supprimer `db/`, `photos/`, caches → supprimer les clés (Keystore/Keychain) → effacer `meta`/préférences → annuler les notifications → réinitialiser `deviceId`. Vérifier par test que le répertoire est vide.

## Corbeille et annulation (A13)
- Corbeille = vue des `ItemDeleted` de moins de 30 jours ; restauration = `ItemRestored`.
- Annulation de la dernière action = événement inverse (`ItemStored` ← `ItemMovedTo` précédent, etc.), jamais une suppression d'événement.

## Checklist avant PR
- [ ] `replay(events) == state` passe avec les nouveaux événements.
- [ ] Aucune écriture directe hors `Projector`.
- [ ] Migration additive + test.
- [ ] Export/import mis à jour si le payload change, `formatVersion` inchangé si compatible.
- [ ] Aucune donnée métier dans les logs.
