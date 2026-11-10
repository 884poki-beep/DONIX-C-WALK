DONIX C-WALK 📼

レトロなカセットウォークマンを再現したAndroid音楽プレイヤー。

📥 Download

""Download APK" (https://img.shields.io/badge/Download-Android_APK-brightgreen?style=for-the-badge&logo=android)" (https://github.com/884poki-beep/DONIX-C-WALK/releases/latest)

インストール方法

1. 「Download Android APK」ボタンを押します。
2. Releasesページの「Assets」を開きます。
3. APKファイルをダウンロードします。
4. Androidスマートフォンにインストールします。

Version: 1.0.12



# DONIX C-WALK (Android) v1.0.0

**dongurikobo** / Android cassette music player.

This project wraps `app/src/main/assets/index.html` (DONIX C-WALK HTML v15.3) in an Android WebView.

## Build on GitHub (no Android Studio required)
1. Create a new GitHub repository, e.g. `DONIX-C-WALK`.
2. Upload the **contents** of this folder, preserving `.github/workflows/build-apk.yml` and all directories. (The GitHub website may not preserve empty directories; this project has no empty required directories.)
3. Go to **Actions → Build Android APK → Run workflow**. A push to `main` or `master` also triggers a build.
4. Open the completed workflow → **Artifacts** → download `DONIX-C-WALK-v1.0.0-APK` ZIP → extract `DONIX-C-WALK-v1.0.0.apk`.
5. Test installation and MP3 playback on Android. Then create a GitHub **Release** with tag `v1.0.0` and attach the APK.

### Important
- The generated APK is a **debug-signed test/distribution build**, not a properly release-signed production build. GitHub runners generate different debug keys on subsequent runs; an APK from a later build may **not update in place**. Plan a stable, securely stored release signing key before ongoing releases. Never publish your private release keystore or password in the repository.
- Android WebView does not guarantee lockscreen/media notifications or reliable background playback. These require separate native implementation and device testing.
- Test local MP3 file selection, audio playback, image art, and Apple Music/Spotify external links on real Android hardware.
- `INTERNET` permission is used only for opening online links. The player HTML and images are bundled locally; music files are selected on the user's device.
- Some mobile browsers' local file restrictions differ from Android WebView.
