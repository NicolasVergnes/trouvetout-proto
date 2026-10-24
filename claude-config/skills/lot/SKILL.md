---
name: lot
description: Démarrer ou reprendre un lot du plan de développement de TrouveTout (/lot NN). Lit docs/03-plan-de-lots.md, le cahier des charges et l'architecture pour le lot demandé, crée la branche, planifie les tâches, écrit les tests d'abord, met à jour le statut du lot.
argument-hint: "[numéro de lot, ex. 1]"
disable-model-invocation: true
allowed-tools: Read Grep Glob Edit Write Bash(git *) Bash(./gradlew *) Bash(xcodebuild *) Bash(tools/*)
---

# Démarrer le lot $0

1. Lire `docs/03-plan-de-lots.md` et repérer le lot **$0** : périmètre, critères, dépendances. Vérifier que les lots dont il dépend sont ☑ ; sinon le dire et s'arrêter.
2. Lire les sections correspondantes de `docs/00-cahier-des-charges.md` (fonctionnalités citées) et `docs/01-architecture.md`. Charger les skills utiles au lot (`local-first-data`, `kmp-core`, `capture-parser`, `on-device-ml`, `android-ui`, `ios-ui`, `secure-mobile`, `privacy-rgpd`, `testing-corpus`).
3. Créer la branche `lot/$0-<nom-court>` depuis `main` si elle n'existe pas ; sinon la reprendre.
4. Marquer le lot ◐ dans `docs/03-plan-de-lots.md` (commit `docs: start lot $0`).
5. Établir la liste de tâches (TaskCreate) : pour chaque fonctionnalité du lot, dans l'ordre « tests → shared → Android → iOS → docs ». Les critères d'acceptation du lot deviennent des tâches de vérification.
6. Travailler tâche par tâche. Après chaque tâche : tests du module touché + lint ; commit Conventional Commits.
7. Avant de déclarer le lot terminé :
   - tous les critères du lot vérifiés et cités dans la PR ;
   - skill `privacy-rgpd` cochée ; `/code-review` puis agents `security-reviewer`, `privacy-auditor`, et `android-reviewer` / `ios-reviewer` selon les fichiers ; objections bloquantes traitées ;
   - ADR ajoutée pour toute décision structurante ;
   - statut ☑ dans `docs/03-plan-de-lots.md`.
8. Ouvrir la PR (ne pas pousser sans accord explicite de l'utilisateur) avec : résumé, critères cochés, rapports `eval-*` si concernés, liste de contrôle vie privée.

Ne jamais élargir le périmètre du lot ; noter les idées dans `docs/04-decisions.md` sous « Questions ouvertes ».
