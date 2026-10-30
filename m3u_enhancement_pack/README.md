# M3UAndroid Plus 1.15.1

This is an additive Plus enhancement patch for the official M3UAndroid 1.15.1 source tree. It is applied in the public repository `P-AI-Studio/M3UAndroid-Plus` on branch `plus-implementation`.

**Scope rule:** this branch receives only the Plus code and build integration authored for this task. No commits or custom modifications from `poyahentai-web/M3UAndroid` are cherry-picked or copied.

## Independent app identity

- Application ID: `com.m3u.smartphone.enhanced`
- Source namespace: `com.m3u.smartphone`
- Version: `1.15.1-plus1` (versionCode `146`)
- The original `com.m3u.smartphone` app can remain installed alongside Plus.
- The Plus extension host permission is namespaced separately to avoid a duplicate Android signature permission with the original app.

## Added functionality

1. Global channel search backed by the existing channel repository.
2. EPG browser keyed by EPG source and TVG/relation ID.
3. Services dashboard with playlist counts, refresh and remove actions.
4. Parental PIN foundation using salted PBKDF2-HMAC-SHA256 and failed-attempt backoff. Its current scope is category filtering in Advanced global search only; it is not a complete player-wide lock.
5. Bulk M3U import: paste up to 100 unique HTTP(S) stream URLs, deduplicate, skip invalid lines and auto-number accepted entries from 1.
6. Low-impact stream diagnostics: check up to 20 channels from one playlist, concurrency 1–3 (default 2), 2.5-second timeouts, a Range request and at most one response byte. It does not start media decoders. HTTP reachability is not proof of a decoded picture; use the normal player to verify playback.

## GitHub build

The workflow `build-plus.yml` builds an ARM64 APK and uploads it with a SHA-256 checksum. If dedicated release-key secrets are configured, it builds a release-signed APK. Without them, it builds a debug-signed preview APK so that the source and feature path can still be smoke-tested before configuring permanent signing.

See `GITHUB_BUILD_AND_SIGNING.md` for the signing-key process. Never commit the keystore or its passwords to this Public repository.

## Validation boundary

The patch application tests and standalone Kotlin smoke tests run separately from the full Android build. The full Android/Compose Gradle compilation, GitHub Actions result, device installation and actual media playback are not considered verified until the CI run completes and the APK is inspected.
