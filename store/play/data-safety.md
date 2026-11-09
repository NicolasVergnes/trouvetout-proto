# Google Play — fiche « Sécurité des données » (Data safety)

Réponses prêtes à saisir dans la Play Console (Contenu de l'application → Sécurité des données). Source :
`docs/02-securite-rgpd.md`, ADR-021, ADR-023, ADR-025. À relire au moment de la saisie (suite (e) du lot 10) : le
formulaire évolue, et toute réponse « oui » doit être justifiée ici avant d'être donnée.

## Collecte et partage

| Question | Réponse | Pourquoi |
| --- | --- | --- |
| L'application collecte-t-elle ou partage-t-elle des types de données utilisateur obligatoires ? | **Non** | Aucune donnée ne quitte le téléphone vers l'éditeur ou un tiers : pas de serveur, pas de compte, pas de SDK d'analyse, de publicité ni de rapport de plantage ; pas de permission `INTERNET` (vérifié par `tools/check-deps.sh` sur le manifeste fusionné de la release et par `NoNetworkTest`). |
| Données traitées seulement sur l'appareil | Non déclarées (ce n'est pas une collecte) | Objets, lieux, photos, phrases dictées, vecteurs du modèle, compteur d'ouvertures : tout reste dans l'espace privé de l'application. |
| Sauvegarde chiffrée A9 | Non déclarée | Écrite à la demande de l'utilisateur, dans un fichier qu'il choisit avec le sélecteur du système ; chiffrée avant écriture avec une clé que lui seul détient ; l'éditeur n'y a jamais accès. C'est l'application de stockage choisie (Google Drive…) qui transfère le fichier, selon ses propres règles. |
| Export, impression | Non déclarés | Fichiers remis par la feuille de partage ou le service d'impression du système, sur action de l'utilisateur. |
| Achats (Play Billing) | Non déclarés par l'application | Le paiement et l'historique d'achat sont traités par Google Play ; l'application ne reçoit qu'une transaction signée, vérifiée sur le téléphone. La bibliothèque est livrée sans sa télémétrie (`datatransport` exclu, ADR-025). **À confirmer dans le formulaire** : si la console demande de déclarer la collecte propre à Play Billing, la décrire comme « Historique des achats — traité par Google Play, non transmis à l'éditeur ». |

## Pratiques de sécurité

| Question | Réponse |
| --- | --- |
| Les données sont-elles chiffrées en transit ? | Sans objet : aucune donnée n'est transmise. |
| Les utilisateurs peuvent-ils demander la suppression de leurs données ? | **Oui** — dans l'application : Réglages → Tout effacer (H6), sans compte ni demande à l'éditeur. |
| Engagement envers les règles relatives aux familles | Non (public : adultes). |
| Examen de sécurité indépendant | Non. |

## Permissions déclarées (manifeste fusionné de la release)

| Permission | Usage | Moment |
| --- | --- | --- |
| `RECORD_AUDIO` | Dicter une phrase, reconnue sur le téléphone (`createOnDeviceSpeechRecognizer`, Android 12+) | Premier appui sur le micro, après une explication |
| `POST_NOTIFICATIONS` | Rappels (E2, E6) | Après le premier rappel |
| `USE_BIOMETRIC` | Verrouillage optionnel (H3) | Quand l'utilisateur l'active |
| `RECEIVE_BOOT_COMPLETED` | Remettre les rappels en place après un redémarrage | Automatique, aucun envoi |
| `com.android.vending.BILLING` | Achat et restauration via Google Play | Écran d'achat |

S'y ajoute `app.trouvetout.proto.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, permission propre à l'app déclarée par
`androidx.core` (signature, sans accès à quoi que ce soit hors de l'app ; tolérée par `tools/check-deps.sh`).

Aucune permission de localisation, de contacts, de stockage partagé, d'appareil photo (la prise de vue passe par
l'application Appareil photo du téléphone) ni de réseau. Aucune alarme exacte.

## Autres déclarations de la console

- **Publicité** : l'application ne contient pas de publicité.
- **Identifiant publicitaire** : non utilisé (`AD_ID` absent du manifeste fusionné).
- **Accès à l'application** : tout est accessible sans identifiant ; pas de compte de test nécessaire.
- **Classification du contenu** : utilitaire, aucune violence, aucun contenu généré partagé, aucun achat de biens
  numériques autres que le déblocage de l'application → PEGI 3 / Tout public attendu.
- **Public cible** : 18 ans et plus (pas d'attrait particulier pour les enfants).
- **Catégorie** : Productivité. **Coordonnées** : glopglopdev@gmail.com ; site : `{{SITE_URL}}`.
- **Politique de confidentialité** : `{{PRIVACY_URL}}`.
- **Applications financières, santé, VPN, etc.** : non concernée.
