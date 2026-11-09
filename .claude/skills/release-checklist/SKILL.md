---
name: release-checklist
description: Liste de contrôle de publication de TrouveTout — fiches App Store / Play Store « aucune donnée collectée », politique de confidentialité, essai gratuit et achats, packs de modèles, signature, versions minimales, captures, vérifications finales. Utiliser pour le lot 12 et pour chaque mise à jour publiée.
disable-model-invocation: true
---

# Publication — liste de contrôle

## Avant de construire la release
- [ ] `docs/03-plan-de-lots.md` : tous les lots P0/P1 ☑, critères d'acceptation `00-cahier-des-charges.md §9` vérifiés sur les 4 appareils de référence (iPhone 8/iOS 16, iPhone 15 Pro, Android 10 4 Go, Pixel 9).
- [ ] `tools/check-deps.sh`, `tools/check-network.sh`, `eval-parser`, `eval-search` verts ; rapport d'accessibilité sans erreur bloquante.
- [ ] Skill `privacy-rgpd` cochée sur la release ; `/security-review` et agent `security-reviewer` passés.
- [ ] Version : `versionName`/`CFBundleShortVersionString` identiques ; `versionCode`/`CFBundleVersion` incrémentés ; `formatVersion` d'export inchangé ou ADR.
- [ ] Nom : le nom affiché peut rester « TrouveTout » pour la bêta ; vérifier qu'aucun identifiant technique (bundle id, package, schéma d'URL, domaine des packs) ne devra changer au renommage (ADR-010).

## Essai et achats
- [ ] Produits `unlock_lifetime` (10 €) et `subscription_yearly` (2 €/an, plan de base `yearly`) créés dans App Store Connect et Play Console (ADR-014, ADR-025) ; prix par pays revus. Le nom de package est définitif dans la console : le choisir avant (ADR-010).
- [ ] `-Ptrouvetout.playLicenseKey` fourni au build (`bundleRelease` refuse sans lui) ; achat réel de test **sur le build de release (R8)** : Play Billing fonctionne sans sa télémétrie, acquittement compris (ADR-025). Sinon, revenir à la décision de l'utilisateur.
- [ ] Test sandbox : achat, restauration sur second appareil, remboursement ; essai : date forcée à +6 mois → seuls les ajouts sont bloqués, écran d'échéance clair, export toujours possible.
- [ ] Textes de l'écran d'échéance FR/EN sans pression commerciale (« Vos données restent à vous »).

## Packs de modèles
- [ ] `models/manifest.json` : sommes SHA-256 des fichiers publiés ; licences Apache 2.0 d'EmbeddingGemma 2 et de LiteRT-LM affichées dans les réglages et sur `site/licences.html` (avec la `THIRD_PARTY_NOTICE.txt` de LiteRT-LM recopiée dans `site/notices/` à chaque version) ; tailles annoncées dans l'UI exactes.
- [ ] Google Play : modèles dans le pack d'assets *install-time* (`bundleRelease -Ptrouvetout.modelDelivery=pack`, `tools/check-bundle.sh` vert, ADR-027) ; iOS : modèles dans le bundle de l'app (aucun téléchargement).
- [ ] Appareil 4 Go : variante texte seul choisie ; appareil ≥ 6 Go : omnimodal proposé, pas imposé.

## Fiches des magasins
- [ ] Apple « App Privacy » : Data Not Collected. Google « Data safety » : aucune donnée collectée/partagée ; « Les utilisateurs peuvent demander la suppression » → via l'app.
- [ ] Politique de confidentialité (J7) en ligne FR/EN (`site/`, hébergée par l'éditeur, ADR-027), URL à la place de `{{PRIVACY_URL}}` dans `store/` ; page de présentation avec le format d'export.
- [ ] Permissions déclarées = Android : `RECORD_AUDIO`, `POST_NOTIFICATIONS`, `USE_BIOMETRIC`, `RECEIVE_BOOT_COMPLETED`, `BILLING` (jamais `INTERNET`) ; iOS : micro, reconnaissance vocale, caméra, Face ID, notifications ; justifications dans `store/play/data-safety.md` et `store/appstore/review-notes.md`.
- [ ] Captures d'écran FR/EN sur les tailles requises ; aucune donnée réelle dans les captures.
- [ ] Classification d'âge, catégorie « Productivité »/« Utilitaires », mots-clés.

## Signature et build
- [ ] Android : App Bundle signé avec la clé de release (hors dépôt), Play App Signing activé, R8 activé, `debuggable=false`, `allowBackup=false`.
- [ ] iOS : archive Release, Hardened, ATS strict, pas de symboles de debug, `ITSAppUsesNonExemptEncryption` renseigné (crypto standard : `false` ou déclaration d'exemption).
- [ ] Tags git `vX.Y.Z` ; notes de version FR/EN ; `CHANGELOG.md` mis à jour.

## Après publication
- [ ] Installer depuis les magasins sur un appareil vierge : prise en main, U1, U3, U8 en conditions réelles, mode avion.
- [ ] Surveiller les rapports de plantage des magasins (seule source de diagnostics) ; `SECURITY.md` et l'adresse glopglopdev@gmail.com actifs.
