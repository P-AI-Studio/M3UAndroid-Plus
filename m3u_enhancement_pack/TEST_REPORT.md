# M3UAndroid Plus — test report

## Implemented validation paths

- Python patch-application tests run in Android CI.
- Android Gradle Release APK compilation runs on GitHub Actions.
- Android verification checks the distinct package ID, target SDK 36, ARM64 native libraries and APK signature.
- Windows CI runs `dotnet publish` for self-contained `win-x64` and uploads a ZIP plus SHA-256 checksum.
- Stream diagnostic logic classifies only explicit HTTP 404/410 responses as Offline; authentication/rate-limit responses are Restricted; transient/server/network/unknown media responses are Inconclusive and retried once.
- Batch channel tests use paged database reads and a bounded concurrency setting; progress and result counters are published per page.

## Not covered by CI

- Install/update on a physical Android phone or tablet.
- Play Console acceptance, upload certificate registration, store listing and policy review.
- Actual video/audio/subtitle decode for every channel. HTTP and HLS media probes do not replace a real player decode.
- Windows VLC playback against the user's private provider credentials and streams.

A successful build is not evidence that an individual IPTV provider allows access or that Google Play has approved the app.
