# Journal des décisions (ADR)

Format : date, décision, raison, conséquences. Ajouter une entrée à chaque choix structurant ; ne jamais modifier une entrée passée, en ajouter une nouvelle qui la remplace.

## ADR-001 — 2026-10-23 — Local d'abord, sans compte ni serveur
**Décision** : toutes les données vivent sur le téléphone ; aucun backend ; aucune télémétrie.
**Raison** : positionnement défendable face aux plateformes (Google Find Hub est cloud), RGPD par construction, coût d'exploitation nul permettant un achat unique.
**Conséquences** : partage multi-appareils plus complexe (reporté, voir ADR-005) ; essai gratuit ancré sur les magasins ou en local (ADR-007).

## ADR-002 — 2026-10-24 — Kotlin Multiplatform + interfaces natives
**Décision** : moteur (base, journal, capture, recherche, modèles, licence) en KMP ; UI Jetpack Compose sur Android, SwiftUI sur iOS.
**Raison** : une seule implémentation de la logique et du format de données ; rendu et intégrations système natifs (widgets, App Intents, parole, achats).
**Conséquences** : build iOS via framework KMP ; `expect/actual` pour fichiers, RAM, crypto, parole ; pas de Flutter/React Native.

## ADR-003 — 2026-10-24 — EmbeddingGemma 2 embarqué, variante selon la RAM
**Décision** : un seul modèle d'embeddings dans l'app ; omnimodal (740 M) si RAM ≥ 6 Go, texte seul (270 M) sinon ; APIs système en bonus, jamais requises.
**Raison** : couvrir ~75 % du parc (dont les téléphones de plus de 4 ans) au lieu des 15–20 % équipés d'IA système ; même espace vectoriel texte/image sans second modèle.
**Conséquences** : packs téléchargés à la demande ; `model_id` sur chaque vecteur ; réindexation depuis le texte source si la variante change ; validation FR à faire sur le corpus.

## ADR-004 — 2026-10-24 — Pas de LLM pour l'extraction en v1
**Décision** : le parseur de phrases est à base de règles + similarité avec les lieux connus ; l'extraction par LLM (B3) est en P3 ; la réponse générative (C13) est un pack optionnel en dernier lot.
**Raison** : déterminisme, latence, couverture des appareils anciens, testabilité par corpus.
**Conséquences** : corpus `parser.jsonl` obligatoire ; formulations exotiques traitées par la désambiguïsation (B7) plutôt que par la génération.

## ADR-005 — 2026-10-24 — Foyer et partage reportés en P3
**Décision** : la v1 est mono-utilisateur, mono-appareil ; la section F entière est reportée.
**Raison** : livrer vite une v1 utile ; le partage sans serveur demande une architecture dédiée.
**Conséquences** : le journal d'événements (deviceId, actor, horodatage) est néanmoins construit dès la v1 pour permettre la fusion plus tard sans migration ; architecture retenue pour la v2 : synchronisation de proximité d'abord, relais chiffré aveugle ensuite.

## ADR-006 — 2026-10-24 — Versions minimales iOS 16 / Android 10
**Décision** : iOS 16 (iPhone 8/X, 2017) et Android 10 (API 29, 2019).
**Raison** : toucher un large public qui garde son téléphone plus de 4 ans ; ~90 % du parc Android, ~95 % des iPhones actifs.
**Conséquences** : repli non-IA (mot-clé + règles) obligatoire partout ; budgets mémoire serrés sur 2–4 Go ; APIs récentes (Foundation Models, Gemini Nano) encapsulées derrière des interfaces optionnelles.

## ADR-007 — 2026-10-24 — Essai gratuit 6 mois puis blocage des seuls ajouts
**Décision** : 6 mois d'usage complet sans compte ; ensuite, les nouvelles fiches sont bloquées, tout le reste reste libre (recherche, modification, export, suppression). Tarif à trancher : 10 € unique ou 2 €/an.
**Raison** : ne jamais prendre les données de l'utilisateur en otage ; modèle compatible avec l'absence de serveur ; ancrage fiable côté Apple (`AppTransaction.originalPurchaseDate`), approximatif côté Android (accepté).
**Conséquences** : StoreKit 2 / Play Billing dès la v1 (J5 remonté en P1) ; écran d'échéance non bloquant ; pas de validation serveur des reçus.

## ADR-008 — 2026-10-24 — Pas de plafond strict de taille d'app
**Décision** : viser léger, mais ne pas sacrifier de fonction pour rester sous 60 Mo ; les modèles restent hors binaire, en packs à la demande.
**Raison** : la contrainte était arbitraire ; les mécanismes de livraison (Play Asset Delivery, On-Demand Resources, téléchargement HTTPS vérifié) existent.

## ADR-009 — 2026-10-24 — Pas d'enquête utilisateurs préalable
**Décision** : le corpus de test (300 questions, 150+ phrases de capture) est construit par le développeur à partir de phrases synthétiques et de son usage.
**Conséquences** : marquer dans le corpus l'origine des phrases pour pouvoir, plus tard, remplacer les synthétiques par des réelles.

## ADR-010 — 2026-10-24 — « TrouveTout » est un nom de prototype
**Décision** : ne pas figer de nom de marque dans les identifiants techniques exposés (bundle id, package name, nom de schéma d'URL, domaine) avant le choix définitif ; utiliser un identifiant neutre interne (ex. `app.trouvetout.proto` ou un nom de code) et une constante unique `AppName`.
