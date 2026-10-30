# M3UAndroid Plus — Windows companion

This is a separate Windows desktop companion, not a direct binary port of the Android application. The Android code depends on Android-only APIs (Room, WorkManager, Media3, Hilt and Android activities); the Windows version uses WinForms and LibVLCSharp.

## Current capabilities

- Add M3U playlist URLs as independent groups.
- Paste up to 100 complete playlist URLs (one per line) and import each URL as its own numbered group.
- Search channels within the selected group.
- Test every channel in a selected group with a progress bar, a percentage and Online / Offline / Restricted / Check manually counts.
- Use bounded parallel requests (three at a time), a low-rate retry for inconclusive results, redirect handling, playlist User-Agent support, and HLS first-media-segment validation.
- Refresh, remove, move and alphabetically sort groups.
- Play a selected stream with LibVLC.
- Store groups locally under `%LOCALAPPDATA%\M3UAndroidPlusWindows`.

## Build and download

GitHub Actions publishes a self-contained Windows x64 ZIP. Extract the ZIP and run `M3UAndroidPlus.Windows.exe`. No separate .NET installation should be required.

For a local build:

```powershell
dotnet publish .\M3UAndroidPlus.Windows.csproj -c Release -r win-x64 --self-contained true
```

## Compatibility boundary

The Windows version supports M3U playlist URLs, including common Xtream `get.php` M3U exports. It does not yet implement the Android app's extension/plugin system, full Xtream API browsing, DRM, EPG synchronization or provider account workflows. Stream tests are protocol/media-signature checks, not an actual VLC decode. VLC playback is the final validation.
