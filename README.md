# FW Chat

Application de chat Android 100 % locale pour l'API [Fireworks AI](https://fireworks.ai)
(Kotlin + Jetpack Compose + Material 3). Les conversations restent sur le téléphone
(Room, aucun cloud hors les appels d'inférence Fireworks). Permissions : `INTERNET`, plus un service
de premier plan + notifications pour que les réponses longues continuent en arrière-plan.
Cible : Android 15 (minSdk 34).

## Fonctionnalités (v1)

- Chats sauvegardés, renommables, recherchables par nom ; fork depuis n'importe quel message.
- Branches de conversation (édition d'un message → `‹ 2/3 ›`), régénération, édition en place.
- Modèles Fireworks utilisables directement (serverless), liste rechargée au démarrage et sur erreur ;
  modèle par défaut.
- Prompts système illimités, un par défaut, choisis au début d'un chat puis figés (irréversible).
- Thinking en streaming dans un bloc repliable, markdown transparent rapide, auto-scroll intelligent.
- Paramètres de génération (température, tokens max, stop, top-p/k, pénalités, raisonnement…) depuis le chat.
- Sauvegarde / restauration JSON des chats et prompts (Réglages).

## Premier lancement

Installer l'APK, coller sa clé API Fireworks (stockée chiffrée via Android Keystore), valider.
Guide de rédaction des prompts système par famille de modèles : `docs/GUIDE_PROMPTS_SYSTEME.md`.

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
[`latest`](../../releases/tag/latest) : ouvre la page depuis le téléphone et télécharge-le.

## Documentation

Voir le dossier `docs/`.
