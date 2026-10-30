# M3UAndroid Plus — test report

## Host-side checks completed

- `python -m py_compile apply.py`: passed.
- `python -m unittest discover -s tests -v`: passed against a minimal source fixture with version/package guards.
- `BulkM3uBuilder.kt` plus its smoke test: compiled and passed.
- `StreamDiagnostics.kt` plus its smoke test: compiled and passed against a local HTTP range server. The test covers HTTP 206/one-byte response, non-HTTP scheme rejection and sensitive URL redaction.

## Compatibility updates for upstream 1.15.1

- EPG paging uses the current `ProgrammeRepository.pagingProgrammes()` Flow<PagingData<Programme>> contract directly.
- The batch test uses a new Room query limited to at most 20 rows, then exposes it through the current ChannelRepository interface.
- Playlist services consume the current `observeAllCounts(): Flow<Map<Playlist, Int>>` contract.
- ARM64-only packaging is enabled with the Gradle property `-Pm3uArm64=true` rather than relying on a nonexistent build variant name.

## Not yet verified

- Full Android/Compose/Room Gradle build.
- GitHub Actions CI run.
- APK install/update on a physical Android device.
- Live IPTV decoder, audio, subtitles and EPG synchronization behavior.

The Parental PIN is explicitly limited to Advanced global-search category filtering; it does not intercept all player entry paths.
