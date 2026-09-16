# Android Template

<p align="center">
  <strong>A repository template to create new Android Kotlin apps!</strong>
</p>

<p align="center">
  <a href="https://github.com/duhow/android-template/actions/workflows/build.yml">
    <img src="https://github.com/duhow/android-template/actions/workflows/build.yml/badge.svg" alt="Build">
  </a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-brightgreen?logo=android" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/Kotlin-2.x-7F52FF?logo=kotlin" alt="Kotlin">
  <!-- <a href="https://sladge.net"><img src="https://sladge.net/badge.svg" alt="AI Slop Inside"></a> -->
  <!-- <img src="https://img.shields.io/badge/License-MIT-orange" alt="License"> -->
</p>

<p align="center">
<a href="https://apps.obtainium.imranr.dev/redirect.html?r=obtainium://add/https://github.com/duhow/android-template">
  <img src="https://github.com/ImranR98/Obtainium/blob/main/assets/graphics/badge_obtainium.png" 
       alt="Get it on Obtainium" align="center" height="54" />
</a>

<a href="https://github.com/duhow/android-template/releases/latest">
  <img src="https://raw.githubusercontent.com/Kunzisoft/Github-badge/4711835e032fe2735dc80c1329beb4685899aa91/get-it-on-github.png"
       alt="Download APK" align="center" height="81" />
</a>
</p>

---

Click the button. [![Use this template](https://img.shields.io/badge/Use%20this%20template-green)](https://github.com/new?template_name=android-template&template_owner=duhow)

Setup. Code or ask to code. Do whatever you want.

### Keystore

To sign your APK, create a keystore. **Keep it safe**.

```sh
keytool -genkeypair -v -keystore release.jks -alias ${APP_NAME} -keyalg EC -groupname secp256r1 -sigalg SHA256withECDSA -validity 10000
```

You can upload it to GitHub Actions as Secret `ANDROID_KEYSTORE_BASE64` to generate Release APKs.

```sh
base64 -w 0 release.jks ; echo
```

Then define `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_PASSWORD` (default is the same), and `ANDROID_KEY_ALIAS` as configured.

### Google Play Publishing

To automatically publish releases to Google Play, configure a service account JSON key.

1. In [Google Play Console](https://play.google.com/console), go to **Setup → API access** and link a Google Cloud project.
2. Create a service account with the **Release manager** (or **Releases** submitter) role.
3. Download the JSON key file for that service account.
4. Encode the contents and add them as a GitHub Actions secret named `GOOGLE_PLAY_JSON_KEY`:

```sh
cat google-play-key.json | base64 -w 0 ; echo
```

Requires also to update the `release.yml` Workflow and enable `BUILD_AAB` (disabled by default). The release workflow will automatically publish the AAB to the **internal** track after a successful build. The track can be changed in `fastlane/Fastfile` (`publish` lane, `track` option).
