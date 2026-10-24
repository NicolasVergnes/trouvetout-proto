---
name: privacy-rgpd
description: Liste de contrôle vie privée et RGPD de TrouveTout — zéro collecte, zéro réseau implicite, permissions minimales, exclusion des sauvegardes système, suppression totale, fiches App Privacy / Data safety, politique de confidentialité. Utiliser avant toute PR, pour toute nouvelle permission, dépendance, flux réseau, log, notification ou widget.
---

# Vie privée et RGPD — contrôle

Référence : `docs/02-securite-rgpd.md`. Ce skill est la procédure de vérification.

## Question préalable à toute fonctionnalité
« Cette fonctionnalité fait-elle sortir une donnée du téléphone, ou la rend-elle visible sans déverrouillage ? »
- Si oui → stop, écrire l'ADR, en discuter. En v1 les seules sorties autorisées : export déclenché par l'utilisateur, sauvegarde A9 chiffrée vers l'espace cloud de l'utilisateur, achats via les magasins, téléchargement d'un pack de modèle.
- Si non → continuer, puis passer la liste ci-dessous.

## Liste de contrôle (à coller dans la PR, cochée)
**Réseau**
- [ ] Aucun nouvel appel réseau ; `grep -rn "HttpClient\|URLSession\|OkHttp\|Ktor\|URLRequest" shared androidApp iosApp` ne montre rien hors `ml/ModelPackDownloader` et billing.
- [ ] Manifeste Android sans `INTERNET` (ou ADR + `docs/adr-permissions.ok`), `usesCleartextTraffic` absent ou `false` ; Info.plist sans `NSAllowsArbitraryLoads`.
- [ ] `tools/check-network.sh` passe.

**Dépendances**
- [ ] `tools/check-deps.sh` passe (aucun motif de `.claude/hooks/forbidden-deps.txt`).
- [ ] Toute nouvelle dépendance : licence compatible, pas d'initialisation réseau implicite (lire son README : « analytics », « telemetry », « crash » = refus).

**Stockage**
- [ ] Nouveaux fichiers dans le conteneur privé non sauvegardé (`noBackupFilesDir` / `isExcludedFromBackup`).
- [ ] Aucune donnée métier dans `SharedPreferences`/`UserDefaults` (préférences d'UI seulement).
- [ ] Photos sans EXIF GPS ; jamais écrites dans la galerie.

**Affichage hors app**
- [ ] Widgets, tuile, notifications, écran verrouillé : aucun objet `sensitive`, pas de lieu dans les notifications.
- [ ] Suggestions (C12) et futurs index système excluent les objets `sensitive`.

**Permissions**
- [ ] Demandée au moment de l'usage, avec explication dans l'UI, fonctionnement dégradé si refusée.
- [ ] Pas de localisation, contacts, calendrier, médias en v1.

**Logs et diagnostics**
- [ ] Aucun `Log`/`print`/`NSLog` contenant nom d'objet, lieu, phrase, requête, photo ; identifiants seulement.
- [ ] Aucun crash reporter tiers ; les rapports de plantage passent par les magasins (opt-in OS).

**Droits de l'utilisateur**
- [ ] Export complet (A7) couvre les nouvelles données ; import (A8) les restaure.
- [ ] Suppression totale (H6) efface les nouveaux fichiers/clés/notifications.

**Magasins**
- [ ] App Privacy (Apple) : « Data Not Collected » reste vrai. Si un pack est téléchargé depuis notre domaine : aucune donnée liée à l'identité ; l'adresse IP transitoire n'est pas conservée (le dire dans la politique).
- [ ] Data safety (Google) : « Aucune donnée collectée ni partagée » ; chiffrement en transit N/A ; suppression des données : via l'app (H6).
- [ ] Politique de confidentialité (J7) mise à jour si un flux change.

## Textes utilisateur à réutiliser
- Micro : « Le micro sert uniquement à transcrire votre phrase, sur votre téléphone. Rien n'est enregistré ni envoyé. »
- Caméra : « Les photos restent dans l'application, sur votre téléphone. Elles ne vont ni dans votre galerie ni sur internet. »
- Notifications : « Pour les rappels que vous programmez. Aucune notification promotionnelle. »
- Pack de modèle : « Téléchargement unique de N Mo depuis nos serveurs. Aucune information sur vous n'est envoyée. »

## Si un jour le périmètre change
Relais chiffré (F9), télémétrie opt-in, sauvegarde gérée par l'éditeur : refaire l'analyse RGPD complète (base légale, registre, DPA hébergeur, information), **avant** d'écrire du code.
