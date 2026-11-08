# App Store Connect — « Confidentialité de l'app » (App Privacy)

Réponse : **Données non collectées** (*Data Not Collected*).

« Collecter » signifie, pour Apple, transmettre des données hors de l'appareil d'une manière qui permet à l'éditeur ou
à un tiers d'y accéder au-delà du temps nécessaire pour traiter la demande en temps réel. TrouveTout :

- n'a ni serveur, ni compte, ni SDK d'analyse, de publicité ou de rapport de plantage ;
- fait tourner la recherche et son modèle (EmbeddingGemma 2, LiteRT-LM) sur l'iPhone ; l'absence de toute socket réseau
  de l'app pendant les tests est vérifiée en CI (`tools/watch-ios-network.sh`, ADR-027) ;
- reconnaît la parole sur l'iPhone uniquement (`requiresOnDeviceRecognition = true`), sans enregistrer le son ;
- n'envoie rien à l'éditeur : la sauvegarde chiffrée A9 est écrite dans un dossier que l'utilisateur choisit
  (iCloud Drive ou autre), chiffrée avec une clé que lui seul détient ; l'export passe par la feuille de partage ;
- achète par StoreKit 2 : la transaction est traitée par Apple et vérifiée sur l'iPhone.

## Manifeste de confidentialité (`iosApp/iosApp/PrivacyInfo.xcprivacy`)

- `NSPrivacyTracking` : non ; aucun domaine de suivi ; aucun type de données collecté.
- API à raison déclarée : `UserDefaults` (CA92.1, préférences de l'app), heure de démarrage du système (35F9.1,
  délai du verrouillage mesuré par une horloge monotone, H3).
- Vérifié au lot 12 : `kotlinx-datetime` lit le fuseau horaire et l'heure, qui ne sont pas des API à raison déclarée ;
  l'extension widget ne lie ni le noyau ni aucune bibliothèque et n'appelle aucune de ces API (ADR-027).

## Chiffrement (export)

`ITSAppUsesNonExemptEncryption = YES` (ADR-023) : SQLCipher (AES-256) chiffre la base, et la sauvegarde A9 utilise
AES-256-GCM et HKDF-SHA-256 en dehors des API d'Apple. À faire dans App Store Connect avec le compte : répondre au
questionnaire d'export (chiffrement standard, usage : protection des données de l'utilisateur sur l'appareil et dans sa
sauvegarde) et déposer la **déclaration de fourniture de moyen de cryptologie** auprès de l'ANSSI pour la distribution
en France (formulaire de déclaration simplifiée), puis joindre le récépissé.

## Autres champs

- **Catégorie** : Productivité (secondaire : Utilitaires).
- **Âge** : 4+ (aucun contenu sensible).
- **URL de confidentialité** : `{{PRIVACY_URL}}` ; **URL d'assistance** : `{{SITE_URL}}` ; contact :
  glopglopdev@gmail.com.
- **Achats intégrés** : `unlock_lifetime` (non consommable, 10 €) et `subscription_yearly` (abonnement annuel, 2 €)
  (ADR-014) ; l'écran d'achat porte les liens vers les conditions d'utilisation d'Apple (EULA standard) et la politique
  de confidentialité (suite (b) du lot 10).
