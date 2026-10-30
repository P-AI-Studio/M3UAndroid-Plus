# M3UAndroid Plus — GitHub Actions build and signing

Target repository: `P-AI-Studio/M3UAndroid-Plus`. The owner chose to keep this repository **Public**. Only source, workflow and tests are committed; the private signing key and passwords are not stored in Git.

## Two build modes

- **Preview APK:** if no release-key secrets are configured, GitHub Actions builds an ARM64 debug-signed preview. It is suitable for installing and checking the new app. The hosted runner's debug key is not the stable Plus release key, so a later release-signed APK may require uninstalling this preview before installing the signed release.
- **Release APK:** once the four repository secrets below are configured, GitHub Actions automatically builds and signs the Release APK with the dedicated Plus key. Future release updates must use this same key.

The package ID is `com.m3u.smartphone.enhanced`, version is `1.15.1-plus1`, and versionCode is `146`. The original app ID remains unchanged, so the two apps can be installed side by side.

## Optional dedicated release-key secrets

Create these under **Settings → Secrets and variables → Actions → New repository secret**:

- `M3U_RELEASE_KEYSTORE_BASE64` — Base64 content of `M3UAndroid-Plus-Release.jks`.
- `M3U_RELEASE_STORE_PASSWORD` — the keystore password in the private key bundle.
- `M3U_RELEASE_KEY_ALIAS` — `m3uplusrelease`.
- `M3U_RELEASE_KEY_PASSWORD` — the key password in the private key bundle.

The private key bundle is delivered separately. Do not upload the `.jks` file or its Base64 value to this Public repository. Do not put passwords in commits, workflow YAML, Issues or chat. Keep a secure backup of the original `.jks` file; losing it prevents future signed updates to the release APK.

## Build verification

The workflow applies only the Plus changes authored for this task, runs Python patch tests, builds only ARM64, verifies the application ID and APK signature, and uploads the APK plus SHA-256 checksum as a GitHub Actions artifact for 30 days.
