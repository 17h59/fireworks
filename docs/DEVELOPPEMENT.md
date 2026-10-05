# Développement

## Prérequis

- JDK 21
- Android SDK : plateforme **37.0**, build-tools **36.0.0** (`sdk.dir` dans `local.properties` ou `ANDROID_HOME`)
- Aucune autre installation : Gradle est fourni par le wrapper (9.x).

## Commandes

```bash
export ANDROID_HOME=/chemin/vers/android-sdk
./gradlew testDebugUnitTest          # tests JVM + Robolectric
./gradlew assembleRelease            # APK minifié (R8) : app/build/outputs/apk/release/app-release.apk
```

Le build de release est signé avec `app/debug.keystore` (clé de debug publique, mot de passe `android`) : aucun
secret requis, et chaque build s'installe par-dessus le précédent.

## Tests

~400 tests, sans émulateur : logique pure (JVM), dépôt Room et UI Compose via Robolectric (rendu natif pour les
tests de dessin), API et moteur via MockWebServer. Les tests **live** contre l'API Fireworks sont ignorés sans clé :

```bash
FW_API_KEY=fw_xxx ./gradlew testDebugUnitTest --tests '*LiveFireworksTest'
```

Ne mets **jamais** de clé dans le dépôt, les tests ou les logs.

Les tests Robolectric téléchargent les jars `android-all` au premier lancement (réseau requis). Les noms de tests
ne doivent contenir ni accents ni apostrophes (la compilation des tests échoue sinon dans certains environnements).

## CI / publication

`.github/workflows/build.yml` : à chaque push, job `build` (tests + `assembleRelease`, artefacts APK et
`mapping.txt` R8) ; sur `main` (et la branche de travail), job `publish` qui met à jour la pre-release
**`latest`** (tag déplacé sur le commit, asset `fw-chat.apk` remplacé).

## Structure

Voir [ARCHITECTURE.md](ARCHITECTURE.md). En bref :

```
app/src/main/java/app/fwchat/
  domain/    modèles et interfaces (contrat, sans dépendance Android)
  data/db    Room (chats, messages en arbre, prompts)
  data/repo  ChatRepositoryImpl, SystemPromptRepositoryImpl, ThreadLogic, sauvegarde JSON
  data/net   client Fireworks (SSE), moteur de génération, modèles, facturation
  data/prefs réglages (DataStore) + clé API chiffrée (Keystore)
  service/   service de premier plan pendant les générations
  ui/        chat, common (feuilles, éditeur de paramètres), markdown, prompts, settings, shell, theme
```

## Conventions

- Interface en français, tutoiement.
- Logique testable séparée du Compose (ViewModels, fonctions pures).
- Le schéma Room est versionné dans `app/schemas` : toute évolution exige un bump de version et une migration.
- Pas de dépendance injectée par framework : câblage manuel dans `AppContainer`.
- Commits en français, un sujet par commit.

## Mettre à jour les prix des modèles

Les prix sont dans `domain/ModelPricing.kt` (USD par million de tokens, entrée / sortie / cache, avec une date de
vérification). Source : https://docs.fireworks.ai/serverless/pricing.md et les pages `fireworks.ai/models/…`.
Le palier `$` se calcule sur le coût mixte `(entrée + 3 × sortie) / 4` ; les seuils sont des constantes testées.
