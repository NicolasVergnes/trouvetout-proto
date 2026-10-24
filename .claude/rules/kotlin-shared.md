---
paths:
  - "shared/**/*.kt"
  - "shared/**/*.sq"
  - "shared/**/*.kts"
---
# Règles pour le module `shared` (Kotlin Multiplatform)

- `commonMain` ne dépend d'aucune API de plateforme : ni `java.*`, ni `android.*`, ni `platform.*`, ni `Foundation`. Ce qui en a besoin passe par `expect/actual` dans `shared/platform` ou par une interface injectée.
- `explicitApi()` : chaque déclaration publique est volontaire et documentée en une ligne KDoc si son nom ne suffit pas.
- Toute écriture dans la base passe par un événement (`Event`) puis par la projection ; interdiction d'`UPDATE`/`DELETE` direct sur `item`, `place`, `container`, `location_state` en dehors de `data/projection`.
- Requêtes SQL uniquement dans les fichiers `.sq` SQLDelight, paramétrées ; les requêtes FTS5 échappent l'entrée utilisateur (`"..."` et suppression des opérateurs).
- Migrations : fichiers `.sqm` numérotés, additives uniquement (ajout de colonne/table/index) ; une migration qui supprime ou renomme doit être justifiée par une ADR et accompagnée d'un test d'import d'un export ancien.
- Pas de `GlobalScope`, pas de `runBlocking` hors tests ; dispatchers injectés (`CoroutineDispatchers`).
- Les identifiants sont des ULID (triables par temps) ; les instants sont en UTC (`kotlinx-datetime`).
- Aucune journalisation de `name`, `description`, `rawInput`, `transcript`, chemins de lieux ; logger des identifiants.
- Tout nouveau comportement de capture ou de recherche ajoute des lignes au corpus (`corpus/*.jsonl`) et un test.
- Charger les skills `kmp-core` et `local-first-data` pour les détails.
