# M3UAndroid Plus — GitHub build and Google Play signing

Target repository: `P-AI-Studio/M3UAndroid-Plus`, branch `plus-implementation`. The repository remains **Public**; the original `master` branch and original app package are not modified.

## Android app identity

- Application ID: `com.m3u.smartphone.enhanced`
- Version: `1.15.3-plus3`
- Version code: `148`
- Minimum Android: 8.0 (API 26)
- Target Android: 16 (API 36)
- ABI: ARM64

The distinct package ID allows the Plus app to be installed beside the original M3UAndroid app.

## Build modes

- **ReleasePreview:** if upload-key secrets are not present, GitHub Actions builds a Release-optimized ARM64 APK using the local debug signing fallback. It is for device testing only. The workflow intentionally does **not** upload a debug-signed AAB.
- **Release:** when all four secrets below are configured, GitHub Actions builds the optimized APK and a signed Android App Bundle (AAB). The AAB is verified before it is included in the artifact and is the Play Console upload candidate.

## Configure the upload key

In GitHub, open **Settings → Secrets and variables → Actions** and add:

- `M3U_RELEASE_KEYSTORE_BASE64` — Base64-encoded contents of the private `.jks` upload keystore.
- `M3U_RELEASE_STORE_PASSWORD` — keystore password.
- `M3U_RELEASE_KEY_ALIAS` — `m3uplusrelease` for the dedicated Plus key.
- `M3U_RELEASE_KEY_PASSWORD` — key password.

Do not commit the keystore, Base64 data, or passwords to this Public repository. Keep a separate secure backup of the original keystore. Losing the upload key can complicate or prevent future updates.

The key must match the upload certificate registered for this package in Play Console. If this Plus package was already created in Play Console with a different upload certificate, use Google's upload-key reset process rather than uploading an AAB signed with an unrelated key.

## Play Console notes

The workflow checks the independent package ID, API 36 target, ARM64 ABI and APK signature. A Play upload also depends on the upload certificate associated with the app in Play Console, the listing/policy declarations, and the specific Play Console validation message. A successful CI build alone does not mean Google has approved the app.

## Windows companion

The separate Win-x64 companion is published by `.github/workflows/build-windows.yml`. It uses WinForms and LibVLCSharp; it is not a binary-compatible Windows port of Android-only features such as Room, WorkManager, Android plugins or Media3.
