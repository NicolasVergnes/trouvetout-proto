---
name: secure-mobile
description: Sécurité applicative mobile pour TrouveTout, alignée OWASP MASVS — stockage et clés (Keystore/Keychain, SQLCipher), validation des entrées (import ZIP, FTS), protection d'écran, achats sans serveur, chaîne de build et secrets, revue de sécurité. Utiliser pour le lot 8, pour toute PR touchant stockage, crypto, import, achats, manifeste/plist, et avant /security-review.
---

# Sécurité mobile (MASVS-L1 + exigences locales)

## Modèle de menaces
Voir `docs/02-securite-rgpd.md` §2 : téléphone volé (verrouillé / déverrouillé), curieux du foyer, app malveillante, export partagé par erreur, dépendance compromise, pack de modèle altéré.

## Stockage (MASVS-STORAGE)
- Base SQLite : conteneur privé non sauvegardé ; lot 8 : SQLCipher, clé 256 bits dans Keystore (StrongBox si dispo) / Keychain (`AfterFirstUnlockThisDeviceOnly`, non synchronisé). Migration claire→chiffrée atomique (copie, vérification, remplacement, suppression sécurisée de l'ancienne).
- Jamais de données métier dans les logs, le presse-papiers (sauf action « copier » explicite), les captures d'écran automatiques (H3 → `FLAG_SECURE` / overlay), les URL de deep link.
- Caches d'images : dans le conteneur privé, purgés à la suppression totale.

## Cryptographie (MASVS-CRYPTO)
- Primitives système uniquement : AES-256-GCM (Android `Cipher`/Keystore ; iOS `CryptoKit`), SHA-256, `SecRandomCopyBytes`/`SecureRandom`. Pas de crypto maison, pas de clé dérivée d'un identifiant d'appareil.
- Sauvegarde A9 : clé de sauvegarde distincte de la clé de base, dérivée d'une phrase choisie par l'utilisateur (PBKDF2/Argon2id si dispo, sel aléatoire) **ou** clé aléatoire affichée une fois ; sans cette clé, la sauvegarde est illisible, et on le dit.
- Vérification des packs : SHA-256 épinglé dans `models/manifest.json` versionné.

## Authentification locale (MASVS-AUTH)
- Verrouillage H3 optionnel : biométrie avec repli code appareil ; délai configurable ; pas de « mot de passe » applicatif propre (pas de récupération possible sans serveur → éviter).

## Réseau (MASVS-NETWORK)
- Par défaut aucun. Pack de modèles : HTTPS, TLS système, pas d'épinglage de certificat (fragile) mais somme SHA-256 des fichiers ; domaine unique en dur ; pas de redirection suivie hors domaine.
- ATS strict ; `network_security_config` sans clair.

## Plateforme (MASVS-PLATFORM)
- Composants non exportés ; deep links (`trouvetout-proto://capture`) sans paramètre sensible et validés ; pas de WebView.
- Widgets/notifications : contenu minimal (voir privacy-rgpd).
- Presse-papiers : lecture jamais automatique.

## Code et build (MASVS-CODE)
- Entrées : import ZIP (chemins, tailles, bombes), JSON strict, FTS échappé, noms de fichiers générés (ULID) jamais issus de l'utilisateur.
- Dépendances épinglées, `./gradlew dependencyUpdates` revu, Swift `Package.resolved` versionné ; pas de script de build qui télécharge.
- Release : R8/ProGuard activé (règles minimales, pas d'obfuscation agressive des classes sérialisées), `debuggable=false`, symboles conservés hors APK ; iOS : `-O`, pas de `DEBUG` flags, `Hardened`.
- Secrets : clé de signature, mots de passe de keystore, `.p8` App Store Connect hors dépôt ; CI via secrets chiffrés ; `.gitignore` et règles `Read deny` les couvrent.

## Résilience (MASVS-RESILIENCE) — proportionnée
- Pas d'anti-root/jailbreak (hostile et inutile ici). Vérification de signature des achats pour limiter la triche triviale ; accepter le reste.

## Procédure de revue d'une PR
1. `/security-review` de Claude Code (diff vs `main`).
2. Agent `security-reviewer` du projet avec la liste ci-dessus.
3. Si manifeste/plist/gradle/Package.swift touchés : relecture humaine des permissions et dépendances.
4. Pour le lot 8 : test de migration chiffrée sur base de 10 000 objets ; test de suppression totale ; test d'import malveillant (zip bomb, `../`, JSON géant).

## Réponse aux incidents (à préparer au lot 12)
- Canal de signalement (adresse e-mail dédiée dans la fiche magasin et `SECURITY.md`), délai de réponse visé 72 h, mise à jour corrective via les magasins.
