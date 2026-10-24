# Cahier des charges — TrouveTout (nom de prototype)

Version figée le 24 octobre 2026 après revue. Source de vérité pour le développement de la v1.
Le document complet (avec P2/P3 et les décisions de revue) est le doc Claude « Cahier des charges TrouveTout » ; ce fichier en est l'extrait opérationnel.

## 1. Objectif

TrouveTout répond à une question : **« où ai-je rangé ça ? »**. L'utilisateur déclare en deux secondes (voix, photo ou texte) où il pose un objet, et le retrouve des mois plus tard en posant la question avec ses propres mots. Tout fonctionne **sans compte ni serveur**, sur iOS et Android.

La v1 sert **une personne sur un téléphone**. Le partage au sein du foyer est reporté (P3), mais le modèle de données est conçu dès la v1 pour l'accueillir sans migration.

## 2. Périmètre

- Objets physiques du foyer : clés, papiers, outils, vêtements, cartons, pièces détachées, dans un ou plusieurs lieux (maison, cave, garage, voiture, résidence secondaire, garde-meuble).
- Hors périmètre v1 : fichiers numériques, inventaire d'entreprise, matériel de localisation, estimation de valeur / dossiers d'assurance, partage multi-utilisateurs, version web, capture passive par caméra ou lunettes.

## 3. Principes directeurs (non négociables)

1. **Local d'abord.** La base vit sur le téléphone, l'app fonctionne entièrement hors ligne, aucune donnée ne quitte l'appareil sans action explicite de l'utilisateur (export). Pas de compte, pas de télémétrie, pas de SDK tiers de tracking.
2. **Deux secondes.** Du geste à la fiche enregistrée, sans formulaire. L'app remplit, l'utilisateur corrige si besoin.
3. **Retrouver avec ses mots.** Synonymes, fautes, approximations tolérés ; la réponse est un lieu, pas une liste.
4. **Le foyer est l'unité** (cible v2). La v1 est mono-utilisateur, mais chaque donnée est un événement daté et signé par l'appareil.
5. **Mémoire longue.** Rien n'est écrasé ; les données s'exportent dans un format lisible sans l'app.
6. **Valeur dès le premier objet.** Pas de configuration préalable imposée, pas de plafond d'objets.

## 4. Décisions structurantes

| Sujet | Décision |
| --- | --- |
| Noyau | Kotlin Multiplatform pour le moteur (base, journal, recherche, modèles) ; interfaces natives : Jetpack Compose (Android), SwiftUI (iOS) |
| Versions minimales | iOS 16 (iPhone 8/X et suivants), Android 10 / API 29 — toucher les téléphones de plus de 4 ans |
| Modèles IA | **EmbeddingGemma 2** embarqué dans l'app, variante choisie à l'installation selon la RAM : omnimodale (740 M, ≈ 570 Mo RAM) si ≥ 6 Go, texte seul (270 M, ≈ 200 Mo) sinon. Les APIs système (Foundation Models, Gemini Nano, ML Kit) ne sont jamais requises : bonus uniquement |
| Extraction objet/lieu | Règles + similarité avec les lieux connus (pas de LLM en v1 ; B3 reporté en P3) |
| Transcription vocale | Reconnaisseur système en mode local (SFSpeechRecognizer `requiresOnDeviceRecognition`, Android `SpeechRecognizer` on-device) ; repli Whisper embarqué si indisponible |
| Taille de l'app | Pas de plafond strict ; viser léger ; modèles livrés en pack à la demande (On-Demand Resources / Play Asset Delivery) |
| Monétisation | 6 mois d'essai gratuit complet sans compte, puis **blocage des nouveaux ajouts seulement** ; tout l'existant reste consultable, modifiable, exportable. Tarif à préciser : 2 €/an ou 10 € achat unique. Achats via StoreKit 2 / Play Billing, restauration incluse |
| Enquête utilisateurs | Aucune. Le corpus de test (300 questions) est construit par le développeur |
| Nom | « TrouveTout » = nom du prototype ; nom définitif à choisir (ne pas figer de bundle id / package name marketing avant) |

## 5. Cas d'usage de référence (v1)

| # | Scénario | Résultat attendu |
| --- | --- | --- |
| U1 | Appuie sur le widget, dit « passeport dans le tiroir du haut de la commode » | Fiche créée sans ouvrir l'app, objet et lieu reconnus, confirmation visuelle |
| U2 | Photographie le contenu d'un carton ouvert | Photo attachée au contenant ; sur appareils ≥ 6 Go, nom suggéré pour un objet photographié seul |
| U3 | Demande « le chargeur du Kindle » alors que la fiche dit « câble USB-C blanc » | Réponse en une ligne avec lieu, photo et date, < 1 s, hors ligne |
| U5 | Dit « je prends la perceuse » puis « perceuse remise au garage » | Historique des déplacements, pas de doublon |
| U6 | Dit « j'ai prêté la tente à Marc » | Objet marqué prêté, personne et date |
| U7 | Pose un rappel « rappelle-moi où est le passeport la veille du 12 mars » | Notification locale à la date |
| U8 | Exporte, réinstalle, importe | Tout restauré, photos comprises ; le ZIP se lit sans l'app |
| U11 | Fin des 6 mois d'essai | Écran clair, non bloquant ; ajouts bloqués, tout le reste fonctionne ; achat ou restauration |

(U4, U9, U10 — foyer et partage — sont hors v1.)

## 6. Fonctionnalités P0 (indispensables)

### A. Socle de données
- **A1** Fiche objet : nom, description libre, photos (plusieurs), tags, date et appareil de création.
- **A2** Lieux hiérarchiques : lieu > pièce > meuble > contenant, profondeur libre, création à la volée depuis la saisie.
- **A3** Contenants déplaçables : un carton/bac/valise a un numéro, un contenu, et change de lieu avec tout son contenu.
- **A4** Journal d'événements : chaque action (rangé, pris, déplacé, prêté, rendu, supprimé) est un événement daté et signé par l'appareil, jamais écrasé ; l'état courant en est dérivé. (L'affichage de l'historique à l'utilisateur est optionnel — réglage.)
- **A6** Base SQLite locale, schéma documenté, identique sur iOS et Android.
- **A7** Export complet : ZIP = JSON lisible + photos, déclenché par l'utilisateur, via la feuille de partage système.
- **A8** Import / restauration : reconstruit la base depuis un export, fusionne si la base n'est pas vide.
- **A10** Photos dans le conteneur de l'app (pas la photothèque), compressées, miniatures générées.

### B. Capture
- **B1** Saisie vocale en une phrase → objet, lieu, contenant extraits.
- **B2** Transcription sur l'appareil, jamais de serveur.
- **B4** Saisie texte rapide « objet @ lieu » avec autocomplétion des lieux ; même extraction que la voix.
- **B5** Point d'entrée en un geste : widget écran d'accueil et verrouillé, bouton Action (iPhone), tuile de réglages rapides (Android).
- **B6** Carte de confirmation 3 s, modifiable d'un tap, annulable, disparition automatique.
- **B7** Désambiguïsation des lieux : lieu inconnu → créer ou rattacher ; alias mémorisés.

### C. Recherche
- **C1** Mot-clé tolérant : accents, pluriels, fautes, préfixes ; sur nom, description, tags, lieu, transcription.
- **C2** Recherche sémantique locale (EmbeddingGemma 2, variante selon RAM).
- **C3** Fusion et classement hybride : mot-clé + synonymes + sémantique, récence et fréquence comme signaux.
- **C4** Réponse en une ligne : « Cave, étagère du fond, carton 7, rangé le 8 janvier », avec photo.
- **C6** Fil d'Ariane cliquable du lieu.
- **C7** Navigation par lieu (arborescence, contenu des contenants).

### D. Lieux
- **D1** Plusieurs lieux racines.
- **D4** Déplacer un contenant déplace tout son contenu (événement unique).

### H. Vie privée
- **H1** Aucun compte, aucune télémétrie, aucun SDK analytique/publicitaire ; étiquette « Data Not Collected ».
- **H6** Suppression totale des données locales en une action.
- **H9** Permissions minimales, demandées au premier usage avec explication ; l'app fonctionne sans.

### I. Plateformes
- **I1** iOS 16+. **I2** Android 10+. **I3** Noyau KMP + UI natives. **I4** Français et anglais validés (interface, extraction, recherche). **I10** Prise en main sans tutoriel bloquant.

### J. Monétisation
- **J1 / J4** Essai gratuit 6 mois sans compte, puis blocage des ajouts seulement ; écran d'échéance non bloquant ; restauration d'achat.

### N. Exigences non fonctionnelles
- **N1** Capture < 3 s sur iPhone 15 Pro / Pixel 9 ; < 5 s avec repli texte ailleurs.
- **N2** Recherche < 1 s pour 5 000 objets, hors ligne, modèle chargé.
- **N3** 80 % de bonnes réponses en première position, 95 % dans les trois premières, sur le corpus français de 300 questions.
- **N4** 100 % des fonctions sans réseau.
- **N9** Un export v1 s'importe dans toutes les versions suivantes.

## 7. Fonctionnalités P1 (importantes, livrées avec la v1)

- **A5** Historique par objet (affichage optionnel). **A9** Sauvegarde chiffrée automatique dans le dossier app iCloud Drive / Google Drive, clé locale, optionnelle. **A11** Tags libres avec suggestions. **A12** Quantités (nombre + unité, décrémentable). **A13** Corbeille 30 jours + annulation. **A14** Détection de doublons à la saisie.
- **B8** Photo d'un objet → nom suggéré (comparaison de la photo à une liste de libellés dans l'espace EmbeddingGemma 2 omnimodal, appareils ≥ 6 Go ; sinon photo seule). **B10** Mode « je range » par lot. **B11** Verbes de mouvement (« je prends X », « je remets X », « X est maintenant dans Y »). **B12** Prêt et retour. **B14** Plusieurs objets par phrase.
- **C5** Recherche vocale (question → recherche, affirmation → capture). **C10** Recherche par photo (option utilisateur, appareils ≥ 6 Go). **C11** Filtres (lieu, contenant, tag, état, date). **C12** Suggestions de requête. **C13** Réponse générative sur appareils compatibles (voir note). **C16** Vue par contenant imprimable (PDF).
- **D2** Modèles de lieux proposés, jamais imposés. **D5** Photo du lieu avec repère. **D6** Renommer / fusionner des lieux sans perdre l'historique. **D7** Objets épinglés (option). **D11** Archivage (option).
- **E2** Rappel à une date (voix ou fiche), notification locale. **E6** Rappel de vérification des objets critiques (option).
- **H2** Chiffrement au repos (clé Keychain / Keystore). **H3** Verrouillage biométrique optionnel + masquage dans le sélecteur d'apps. **H4** Objets sensibles exclus des widgets, de l'index système et des notifications.
- **I6** Accessibilité (VoiceOver / TalkBack, Dynamic Type, contrastes, cibles larges). **I7** Mode simplifié deux boutons. **I8** Mode sombre / thème système.
- **J5** Achats via StoreKit 2 / Play Billing, restauration ; nécessaire au modèle d'essai. **J7** Page web statique de présentation + politique de confidentialité + format de données.
- **N5** Taille : pas de plafond strict, packs de modèles à la demande. **N6** Batterie : pas de traitement en arrière-plan, inférence au premier plan. **N7** 10 000 objets / 20 000 photos / 100 000 événements sans dégradation. **N10** Tests : corpus automatisé, tests d'import/export, tests d'accessibilité.

> Note C13 : la réponse générative implique un petit LLM embarqué (pack optionnel, ex. Qwen3-0.6B ou Gemma 3 1B via LiteRT-LM) alors que l'extraction LLM (B3) est en P3. À traiter en dernier lot P1 ; si le pack s'avère trop lourd, C13 bascule en P2 sans impact sur le reste.

## 8. Reporté (P2 / P3) et supprimé

- **P2** : B9 photo d'un contenant → liste d'objets ; C8 récents et fréquents.
- **P3** : B3 extraction par LLM, B13 scan QR, B15 raccourcis d'assistant, B16 code-barres, B17 partage vers l'app, B18 montre ; C14 index système, C15 « pas vu depuis » ; D3 étiquettes QR, D8 collections, D9 plan, D10 NFC ; E1, E3, E4, E5, E7, E8 ; **toute la section F (foyer)** ; G1–G12 ; H5, H7, H8 ; I5, I9 ; J2, J3, J6, J8 ; A15, A16 ; N8.
- **Supprimé** : C9 (historique dans la fiche en tant qu'écran dédié — couvert par A5 optionnel).

## 9. Critères d'acceptation de la v1

- Les cas U1, U3, U5, U6, U7, U8, U11 passent sur un iPhone 8 (iOS 16), un iPhone 15 Pro, un Android 10 à 4 Go de RAM et un Pixel 9.
- Les seuils N1–N4 et N9 sont mesurés par des tests automatisés qui tournent en CI.
- Aucune requête réseau sortante en usage normal (vérifié par un test d'intégration avec proxy bloquant) ; la seule exception est le téléchargement explicite d'un pack de modèle et les achats via les magasins.
- Les fiches « App Privacy » (Apple) et « Data safety » (Google) peuvent être remplies « aucune donnée collectée » en toute honnêteté.
