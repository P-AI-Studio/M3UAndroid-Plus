#!/usr/bin/env python3
"""Apply the M3UAndroid 1.15.1 Plus patch to the pinned upstream checkout."""
from __future__ import annotations
import argparse
import pathlib
import re
import shutil
import subprocess
import sys

REVISION = "3c61444813e3b72c2431f892436dc4bd668daf50"


def patch_once(path: pathlib.Path, old: str, new: str, label: str) -> None:
    content = path.read_text(encoding="utf-8")
    count = content.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one patch anchor in {path}, found {count}")
    path.write_text(content.replace(old, new, 1), encoding="utf-8", newline="\n")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, help="Extracted M3UAndroid 1.15.1 source directory")
    args = parser.parse_args()
    root = pathlib.Path(args.source).resolve()

    paths = {
        "build": root / "app/smartphone/build.gradle.kts",
        "manifest": root / "app/smartphone/src/main/AndroidManifest.xml",
        "destination": root / "app/smartphone/src/main/java/com/m3u/smartphone/ui/material/components/Destination.kt",
        "root_graph": root / "app/smartphone/src/main/java/com/m3u/smartphone/ui/common/RootGraph.kt",
        "ui_strings": root / "i18n/src/main/res/values/ui.xml",
        "extension_protocol": root / "extension/transport-android/src/main/java/com/m3u/extension/transport/android/ExtensionProtocol.kt",
        "channel_dao": root / "data/src/main/java/com/m3u/data/database/dao/ChannelDao.kt",
        "channel_repo": root / "data/src/main/java/com/m3u/data/repository/channel/ChannelRepository.kt",
        "channel_repo_impl": root / "data/src/main/java/com/m3u/data/repository/channel/ChannelRepositoryImpl.kt",
    }
    required = [root / "settings.gradle.kts", *paths.values()]
    missing = [str(p) for p in required if not p.is_file()]
    if missing:
        raise SystemExit("Not an extracted M3UAndroid source tree; missing:\n" + "\n".join(missing))

    build_text = paths["build"].read_text(encoding="utf-8")
    if ('versionName = "1.15.1"' not in build_text
            or 'versionCode = 145' not in build_text
            or 'applicationId = "com.m3u.smartphone"' not in build_text):
        raise SystemExit("Source version/package does not match the expected 1.15.1 base; no files changed.")
    if (root / ".git").exists():
        head = subprocess.run(
            ["git", "-C", str(root), "rev-parse", "HEAD"],
            check=True, capture_output=True, text=True
        ).stdout.strip()
        is_ancestor = subprocess.run(
            ["git", "-C", str(root), "merge-base", "--is-ancestor", REVISION, "HEAD"],
            capture_output=True
        ).returncode == 0
        if head != REVISION and not is_ancestor:
            raise SystemExit(f"Git checkout {head} does not contain required base revision {REVISION}; no files changed.")

    app_src = root / "app/smartphone/src/main/java/com/m3u/smartphone/ui/business/advanced"
    if (app_src / "AdvancedToolsScreen.kt").exists():
        raise SystemExit("Advanced screen already exists; refusing to apply the patch twice.")

    backup = root / ".m3u-plus-patch-backup"
    if backup.exists():
        raise SystemExit(f"Backup folder already exists: {backup}. Move it aside before retrying.")
    backup.mkdir()
    for p in paths.values():
        target = backup / p.relative_to(root)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(p, target)

    new_overlay_files = [
        "AdvancedToolsViewModel.kt", "AdvancedToolsScreen.kt", "ParentalPinStore.kt",
        "StreamDiagnostics.kt", "BulkM3uBuilder.kt"
    ]
    try:
        # Side-by-side package identity and monotonic version code.
        build_text = paths["build"].read_text(encoding="utf-8")
        for pattern, replacement, label in [
            (r'applicationId\s*=\s*"com\.m3u\.smartphone"', 'applicationId = "com.m3u.smartphone.enhanced"', "applicationId"),
            (r'versionCode\s*=\s*145', 'versionCode = 146', "versionCode"),
            (r'versionName\s*=\s*"1\.15\.1"', 'versionName = "1.15.1-plus1"', "versionName"),
        ]:
            build_text, count = re.subn(pattern, replacement, build_text, count=1)
            if count != 1:
                raise RuntimeError(f"Could not update {label} exactly once")

        signing_block = '''val plusReleaseStoreFile = System.getenv("M3U_RELEASE_KEYSTORE_PATH")
val plusReleaseStorePassword = System.getenv("M3U_RELEASE_STORE_PASSWORD")
val plusReleaseKeyAlias = System.getenv("M3U_RELEASE_KEY_ALIAS")
val plusReleaseKeyPassword = System.getenv("M3U_RELEASE_KEY_PASSWORD")
val plusReleaseSigningConfigured = listOf(
    plusReleaseStoreFile, plusReleaseStorePassword, plusReleaseKeyAlias, plusReleaseKeyPassword
).all { !it.isNullOrBlank() }

'''
        if "val plusReleaseSigningConfigured" not in build_text:
            if build_text.count("android {\n") != 1:
                raise RuntimeError("Could not find Android Gradle block for signing configuration")
            build_text = build_text.replace("android {\n", signing_block + "android {\n", 1)
        signing_configs = '''    signingConfigs {
        if (plusReleaseSigningConfigured) {
            create("plusRelease") {
                storeFile = file(plusReleaseStoreFile!!)
                storePassword = plusReleaseStorePassword
                keyAlias = plusReleaseKeyAlias
                keyPassword = plusReleaseKeyPassword
            }
        }
    }
'''
        if "create(\"plusRelease\")" not in build_text:
            marker = "    buildTypes {\n"
            if build_text.count(marker) != 1:
                raise RuntimeError("Could not find buildTypes insertion point for release signing")
            build_text = build_text.replace(marker, signing_configs + marker, 1)
        old_release = '''        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
        }'''
        new_release = '''        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Prefer the private Plus release keystore; debug signing is a local-only fallback.
            signingConfig = if (plusReleaseSigningConfigured) {
                signingConfigs.getByName("plusRelease")
            } else {
                signingConfigs.getByName("debug")
            }
        }'''
        if old_release in build_text:
            build_text = build_text.replace(old_release, new_release, 1)
        elif new_release not in build_text:
            raise RuntimeError("Could not find release signing block")

        paging_alias = '    implementation(libs.androidx.paging.compose)'
        if paging_alias not in build_text:
            marker = '    implementation(libs.androidx.navigation.compose)'
            if build_text.count(marker) != 1:
                raise RuntimeError("Could not find dependency insertion point for Paging Compose")
            build_text = build_text.replace(marker, marker + "\n" + paging_alias, 1)

        old_abi_selection = """            val richCodec = gradle
                .startParameter
                .taskNames
                .find { it.contains(\"richCodec\", ignoreCase = true) } != null

            isEnable = !benchmark && !snapshotChannel && richCodec

            reset()
            include(\"x86\", \"x86_64\", \"arm64-v8a\", \"armeabi-v7a\")
            isUniversalApk = true"""
        new_abi_selection = """            val richCodec = providers.gradleProperty(\"m3uArm64\").orNull?.toBoolean()
                ?: gradle.startParameter.taskNames.any { it.contains(\"richCodec\", ignoreCase = true) }

            isEnable = !benchmark && !snapshotChannel && richCodec

            reset()
            if (richCodec) {
                include(\"arm64-v8a\")
                isUniversalApk = false
            } else {
                include(\"x86\", \"x86_64\", \"arm64-v8a\", \"armeabi-v7a\")
                isUniversalApk = true
            }"""
        if old_abi_selection in build_text:
            build_text = build_text.replace(old_abi_selection, new_abi_selection, 1)
        elif new_abi_selection not in build_text:
            raise RuntimeError("Could not configure ARM64-only APK output")

        paths["build"].write_text(build_text, encoding="utf-8", newline="\n")

        patch_once(
            paths["destination"],
            'import androidx.compose.material.icons.outlined.Settings\n',
            'import androidx.compose.material.icons.outlined.Settings\nimport androidx.compose.material.icons.outlined.Build\n',
            "Destination imports"
        )
        patch_once(
            paths["destination"],
            'import androidx.compose.material.icons.rounded.Settings\n',
            'import androidx.compose.material.icons.rounded.Settings\nimport androidx.compose.material.icons.rounded.Build\n',
            "Destination imports"
        )
        patch_once(
            paths["destination"],
            '''    Setting(
        selectedIcon = Icons.Rounded.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        iconTextId = string.ui_destination_setting
    );''',
            '''    Setting(
        selectedIcon = Icons.Rounded.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        iconTextId = string.ui_destination_setting
    ),
    Advanced(
        selectedIcon = Icons.Rounded.Build,
        unselectedIcon = Icons.Outlined.Build,
        iconTextId = string.ui_destination_advanced
    );''',
            "Advanced root destination"
        )

        patch_once(
            paths["root_graph"],
            'import com.m3u.smartphone.ui.business.setting.SettingRoute\n',
            'import com.m3u.smartphone.ui.business.setting.SettingRoute\nimport com.m3u.smartphone.ui.business.advanced.AdvancedToolsRoute\n',
            "RootGraph import"
        )
        patch_once(
            paths["root_graph"],
            '''        SettingRoute(
            contentPadding = contentPadding,
            onDetailVisibilityChanged = onNestedDetailVisibilityChanged,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (showBottomEdgeBlur) {
                        Modifier.blurEdge(
                            edge = Edge.Bottom,
                            color = MaterialTheme.colorScheme.background,
                        )
                    } else {
                        Modifier
                    },
                ),
        )
    }
}''',
            '''        SettingRoute(
            contentPadding = contentPadding,
            onDetailVisibilityChanged = onNestedDetailVisibilityChanged,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (showBottomEdgeBlur) {
                        Modifier.blurEdge(
                            edge = Edge.Bottom,
                            color = MaterialTheme.colorScheme.background,
                        )
                    } else {
                        Modifier
                    },
                ),
        )
    }
    composable(
        route = Destination.Advanced.name,
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() }
    ) {
        AdvancedToolsRoute(
            navigateToChannel = navigateToChannel,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (showBottomEdgeBlur) {
                        Modifier.blurEdge(
                            edge = Edge.Bottom,
                            color = MaterialTheme.colorScheme.background,
                        )
                    } else {
                        Modifier
                    },
                ),
        )
    }
}''',
            "Advanced route registration"
        )

        patch_once(
            paths["manifest"],
            'android:label="@string/app_name"',
            'android:label="M3UAndroid Plus"',
            "Independent app label"
        )
        patch_once(
            paths["manifest"],
            '<permission\n        android:name="com.m3u.permission.BIND_EXTENSION_HOST"',
            '<permission\n        android:name="com.m3u.plus.permission.BIND_EXTENSION_HOST"',
            "Independent extension permission declaration"
        )
        patch_once(
            paths["manifest"],
            '<uses-permission android:name="com.m3u.permission.BIND_EXTENSION_HOST" />',
            '<uses-permission android:name="com.m3u.plus.permission.BIND_EXTENSION_HOST" />',
            "Independent extension permission use"
        )
        patch_once(
            paths["extension_protocol"],
            'const val HOST_BIND_PERMISSION = "com.m3u.permission.BIND_EXTENSION_HOST"',
            'const val HOST_BIND_PERMISSION = "com.m3u.plus.permission.BIND_EXTENSION_HOST"',
            "Extension protocol permission namespace"
        )
        patch_once(
            paths["ui_strings"],
            '    <string name="ui_destination_setting">Settings</string>\n',
            '    <string name="ui_destination_setting">Settings</string>\n    <string name="ui_destination_advanced">Advanced</string>\n',
            "Advanced navigation label"
        )

        patch_once(
            paths["channel_dao"],
            '''    @Query("SELECT * FROM streams WHERE playlist_url = :playlistUrl")
    suspend fun getByPlaylistUrl(playlistUrl: String): List<Channel>
''',
            '''    @Query("SELECT * FROM streams WHERE playlist_url = :playlistUrl")
    suspend fun getByPlaylistUrl(playlistUrl: String): List<Channel>

    @Query("SELECT * FROM streams WHERE playlist_url = :playlistUrl ORDER BY id LIMIT :limit")
    suspend fun getFirstByPlaylistUrl(playlistUrl: String, limit: Int): List<Channel>
''',
            "Bounded channel sample query"
        )
        patch_once(
            paths["channel_repo"],
            '    suspend fun getByPlaylistUrl(playlistUrl: String): List<Channel>\n',
            '    suspend fun getByPlaylistUrl(playlistUrl: String): List<Channel>\n    suspend fun getFirstByPlaylistUrl(playlistUrl: String, limit: Int): List<Channel>\n',
            "Bounded channel repository contract"
        )
        patch_once(
            paths["channel_repo_impl"],
            '''    override suspend fun getByPlaylistUrl(playlistUrl: String): List<Channel> = channelDao.getByPlaylistUrl(playlistUrl)
''',
            '''    override suspend fun getByPlaylistUrl(playlistUrl: String): List<Channel> = channelDao.getByPlaylistUrl(playlistUrl)

    override suspend fun getFirstByPlaylistUrl(playlistUrl: String, limit: Int): List<Channel> =
        channelDao.getFirstByPlaylistUrl(playlistUrl, limit.coerceIn(1, 20))
''',
            "Bounded channel repository implementation"
        )

        source_overlay = pathlib.Path(__file__).parent / "src/main/java/com/m3u/smartphone/ui/business/advanced"
        app_src.mkdir(parents=True, exist_ok=True)
        for filename in new_overlay_files:
            shutil.copy2(source_overlay / filename, app_src / filename)

        workflow_source = pathlib.Path(__file__).parent / ".github/workflows/build-plus.yml"
        if not workflow_source.is_file():
            raise RuntimeError("Missing GitHub Actions workflow file")
        workflow_target = root / ".github/workflows/build-plus.yml"
        workflow_target.parent.mkdir(parents=True, exist_ok=True)
        if workflow_source.resolve() != workflow_target.resolve():
            shutil.copy2(workflow_source, workflow_target)

    except Exception:
        # Restore all original files that were touched before the failure.
        for backup_file in backup.rglob("*"):
            if backup_file.is_file():
                target = root / backup_file.relative_to(backup)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(backup_file, target)
        for filename in new_overlay_files:
            (app_src / filename).unlink(missing_ok=True)
        try:
            app_src.rmdir()
        except OSError:
            pass
        workflow_target = root / ".github/workflows/build-plus.yml"
        if workflow_target.exists():
            workflow_target.unlink()
        raise

    print("Patch applied.")
    print("Separate package: com.m3u.smartphone.enhanced")
    print("Version: 1.15.1-plus1 (versionCode 146)")
    print("Bulk M3U import: up to 100 HTTP/HTTPS stream links, numbered from 1")
    print("Batch test: first 20 channels, configurable 1–3 concurrent 1-byte HTTP probes")
    print("ARM64-only output: enabled with -Pm3uArm64=true")
    print("Release signing: private GitHub Actions keystore secrets supported; preview uses debug signing")
    print(f"Original files backed up under: {backup}")
    print("Next: commit the patch to a writable fork and let .github/workflows/build-plus.yml build the signed ARM64 APK.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"PATCH FAILED: {exc}", file=sys.stderr)
        raise SystemExit(2)
