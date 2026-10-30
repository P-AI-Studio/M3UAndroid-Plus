# M3UAndroid Plus — Windows companion

This is a separate Windows desktop companion, not a direct port of the Android application. The Android code depends on Android-only APIs (Room, WorkManager, Media3, Hilt and Android activities); the Windows version uses WinForms and LibVLCSharp.

## Capabilities

- Add M3U playlist URLs as independent numbered groups.
- Paste up to 100 playlist URLs (one URL per line) and import each as its own group.
- Search channels in the selected group.
- Test all channels in the selected group with bounded parallel requests and HLS first-segment validation.
- Refresh, remove, reorder and alphabetically sort groups.
- Play the selected stream with LibVLC.
- Store playlists locally under `%LOCALAPPDATA%\M3UAndroidPlusWindows`.

## Build

Open `M3UAndroidPlus.Windows.csproj` in Visual Studio 2026 or run:

```powershell
dotnet publish .\M3UAndroidPlus.Windows.csproj -c Release -r win-x64 --self-contained true
```

The GitHub Actions workflow publishes a self-contained Windows x64 folder as a ZIP artifact.

## Limits

The first Windows release reads M3U playlist URLs (including common Xtream get.php M3U exports). It does not yet implement the Android app's plugin system, full Xtream API browsing, DRM, EPG synchronization or provider account workflows. HTTP availability is not proof that a video decoder can play a stream. VLC playback is the final validation.
