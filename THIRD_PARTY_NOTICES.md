# Composants tiers — Third-party notices

L'application est sous « tous droits réservés » (`LICENSE`). Elle utilise ou intègre les composants suivants, chacun
sous sa propre licence. Les versions exactes sont épinglées dans `gradle/libs.versions.toml`, `iosApp/project.yml`
et `models/manifest.json`.

## Intégrés dans l'application

| Composant | Rôle | Licence | Source |
| --- | --- | --- | --- |
| EmbeddingGemma 2 Text 270M et Text-Vision 440M (Google) | Modèles de recherche et de nommage, sur le téléphone | Apache 2.0 | `models/manifest.json` (Hugging Face, `litert-community`) |
| LiteRT-LM (Google) | Moteur d'exécution des modèles | Apache 2.0, avec sa `THIRD_PARTY_NOTICE.txt` | <https://github.com/google-ai-edge/LiteRT-LM> |
| SQLCipher (Zetetic LLC) | Chiffrement de la base | Licence de type BSD | <https://www.zetetic.net/sqlcipher/> |
| SQLCipher.swift (Zetetic LLC) | SQLCipher pour iOS | Licence de type BSD | <https://github.com/sqlcipher/SQLCipher.swift> |
| SQLDelight (Cash App) | Accès à la base | Apache 2.0 | <https://github.com/cashapp/sqldelight> |
| Kotlin, kotlinx.coroutines, kotlinx.serialization, kotlinx-datetime (JetBrains) | Langage et bibliothèques du noyau | Apache 2.0 | <https://github.com/JetBrains/kotlin>, <https://github.com/Kotlin> |
| AndroidX (Core, Activity, Lifecycle, Navigation, Compose, Material 3) | Interface Android | Apache 2.0 | <https://developer.android.com/jetpack/androidx> |
| Google Play Billing Library | Achats sur Android, sans sa télémétrie (ADR-025) | Android Software Development Kit License | <https://developer.android.com/google/play/billing> |

StoreKit 2, WidgetKit, App Intents, Speech, SwiftUI (Apple) et les API d'Android (reconnaissance vocale, impression,
notifications) sont des API du système, non intégrées à l'application.

## Outils de construction et de test seulement (non livrés)

Gradle et le plugin Android, detekt, ktlint, SwiftLint, SwiftFormat, XcodeGen, JUnit, AndroidX Test, Accessibility
Test Framework, Turbine. Le corpus de photos d'évaluation (CC BY 2.0, Open Images) n'est jamais versionné : crédits
dans `corpus/PHOTOS-CREDITS.md`.

## Texte des licences

- Apache License 2.0 : <https://www.apache.org/licenses/LICENSE-2.0>
- SQLCipher : <https://www.zetetic.net/sqlcipher/license/>
- Android Software Development Kit License : <https://developer.android.com/studio/terms>

La `THIRD_PARTY_NOTICE.txt` de LiteRT-LM (livrée dans son AAR 0.19.0) est publiée avec le site :
`site/notices/litertlm-0.19.0-THIRD_PARTY_NOTICE.txt`, liée depuis `site/licences.html`. À recopier à chaque
changement de version de LiteRT-LM.
