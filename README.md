# FW Chat

Application de chat Android 100 % locale pour l'API [Fireworks AI](https://fireworks.ai)
(Kotlin + Jetpack Compose + Material 3). Les conversations restent sur le téléphone
(Room) ; la seule permission demandée est `INTERNET`. Cible : Android 15 (minSdk 34).

## Builder

Prérequis : JDK 21 et Android SDK (plateforme 37.0, build-tools 36.0.0).

```bash
export ANDROID_HOME=/chemin/vers/android-sdk   # ou sdk.dir dans local.properties
./gradlew testDebugUnitTest assembleRelease
```

L'APK est produit dans `app/build/outputs/apk/release/app-release.apk`. Il est signé avec
le keystore de debug versionné (`app/debug.keystore`) : aucun secret n'est nécessaire et
chaque build s'installe par-dessus le précédent.

## Télécharger l'APK

La CI (GitHub Actions) construit chaque push. Sur la branche de travail et sur `main`,
l'APK `fw-chat.apk` est publié dans la pre-release
[`latest`](../../releases/tag/latest) : ouvrez la page depuis le téléphone et téléchargez-le.

## Documentation

Voir le dossier `docs/`.
