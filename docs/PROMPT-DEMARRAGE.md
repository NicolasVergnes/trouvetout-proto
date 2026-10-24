# Prompt de démarrage pour Claude Code

À coller tel quel dans une session Claude Code ouverte à la racine du dépôt (`claude` dans `TrouveTout/`). Il lance la construction de la v1 (P0 + P1) lot par lot, en respectant les règles du projet.

---

Tu vas développer la v1 de TrouveTout (nom de prototype), une application mobile « où ai-je rangé ça ? », 100 % locale, sans compte ni serveur, iOS 16+ et Android 10+, en Kotlin Multiplatform (moteur) avec Jetpack Compose et SwiftUI (interfaces).

Commence par lire, dans l'ordre : `CLAUDE.md`, `docs/00-cahier-des-charges.md`, `docs/01-architecture.md`, `docs/02-securite-rgpd.md`, `docs/03-plan-de-lots.md`, `docs/04-decisions.md`, `docs/05-conventions.md`. Résume-moi en dix lignes ce que tu as compris du périmètre v1 et des règles absolues, puis attends ma confirmation.

Ensuite, travaille **lot par lot** avec `/lot 0`, `/lot 1`, … jusqu'au lot 12, dans l'ordre de `docs/03-plan-de-lots.md` (les lots 3 et 4, puis 9 et 10, peuvent être menés en parallèle sur des branches distinctes si tu utilises des sous-agents). Pour chaque lot :
- tests d'abord (domaine, corpus), puis `shared`, puis Android, puis iOS ;
- critères d'acceptation du lot vérifiés et cités ;
- liste de contrôle `privacy-rgpd` cochée, revue par `/code-review` puis par les agents `security-reviewer`, `privacy-auditor` et `android-reviewer` / `ios-reviewer` ;
- statut mis à jour dans `docs/03-plan-de-lots.md`, ADR ajoutée si une décision structurante a été prise ;
- commits Conventional Commits en anglais ; ne pousse pas et n'ouvre pas de PR sans me le demander.

Règles à ne jamais enfreindre (elles sont aussi appliquées par des hooks) : aucun réseau en usage normal, aucune télémétrie ni SDK tiers de suivi, aucune donnée métier dans les logs, logique métier uniquement dans `shared`, journal d'événements comme source de vérité, modèles IA embarqués (EmbeddingGemma 2, variante selon la RAM) avec repli non-IA partout, aucun secret ni poids de modèle dans le dépôt.

Si une information manque dans la documentation pour avancer, pose-moi la question plutôt que d'inventer, et note-la dans `docs/04-decisions.md`. Si tu dois choisir une bibliothèque ou une API non listée dans `docs/01-architecture.md`, propose-la avec une ADR avant de l'utiliser.

Points déjà tranchés que tu ne dois pas rouvrir : noyau KMP + UI natives ; iOS 16 / Android 10 ; EmbeddingGemma 2 embarqué, omnimodal si RAM ≥ 6 Go sinon texte seul ; pas de LLM pour l'extraction ; foyer et partage en P3 ; essai gratuit 6 mois puis blocage des seuls ajouts, achats via StoreKit 2 / Play Billing ; pas de plafond strict de taille d'app ; pas d'enquête utilisateurs ; « TrouveTout » est un nom de prototype (identifiants techniques neutres).

Commence maintenant par la lecture et le résumé.
