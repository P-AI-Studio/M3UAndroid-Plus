import pathlib
import subprocess
import sys
import tempfile
import unittest

PATCH = pathlib.Path(__file__).resolve().parents[1] / "apply.py"


def write(root: pathlib.Path, relative_path: str, content: str) -> None:
    path = root / relative_path
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


class PatchApplicationTests(unittest.TestCase):
    def make_fixture(self, root: pathlib.Path) -> None:
        write(root, "settings.gradle.kts", 'rootProject.name = "M3U"\n')
        write(root, "app/smartphone/build.gradle.kts", '''android {
    defaultConfig {
        applicationId = "com.m3u.smartphone"
        versionCode = 145
        versionName = "1.15.1"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    splits {
        abi {
            val benchmark = false
            val snapshotChannel = false
            val richCodec = gradle
                .startParameter
                .taskNames
                .find { it.contains("richCodec", ignoreCase = true) } != null

            isEnable = !benchmark && !snapshotChannel && richCodec

            reset()
            include("x86", "x86_64", "arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }
}
dependencies {
    implementation(libs.androidx.navigation.compose)
}
''')
        write(root, "app/smartphone/src/main/AndroidManifest.xml", '''<manifest>
    <permission
        android:name="com.m3u.permission.BIND_EXTENSION_HOST"
        android:protectionLevel="signature" />
    <uses-permission android:name="com.m3u.permission.BIND_EXTENSION_HOST" />
    <application android:label="@string/app_name" />
</manifest>
''')
        write(root, "extension/transport-android/src/main/java/com/m3u/extension/transport/android/ExtensionProtocol.kt", '''object ExtensionProtocol {
    const val HOST_BIND_PERMISSION = "com.m3u.permission.BIND_EXTENSION_HOST"
}
''')
        write(root, "app/smartphone/src/main/java/com/m3u/smartphone/ui/material/components/Destination.kt", '''package com.m3u.smartphone.ui.material.components
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Settings
import sealed class Destination {
    Setting(
        selectedIcon = Icons.Rounded.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        iconTextId = string.ui_destination_setting
    );
}
''')
        write(root, "app/smartphone/src/main/java/com/m3u/smartphone/ui/common/RootGraph.kt", '''package com.m3u.smartphone.ui.common
import com.m3u.smartphone.ui.business.setting.SettingRoute
fun rootGraph() {
    composable(
        route = Destination.Setting.name,
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() }
    ) {
        SettingRoute(
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
}
''')
        write(root, "i18n/src/main/res/values/ui.xml", '''<resources>
    <string name="ui_destination_setting">Settings</string>
</resources>
''')
        write(root, "data/src/main/java/com/m3u/data/database/dao/ChannelDao.kt", '''interface ChannelDao {
    @Query("SELECT * FROM streams WHERE playlist_url = :playlistUrl")
    suspend fun getByPlaylistUrl(playlistUrl: String): List<Channel>
}
''')
        write(root, "data/src/main/java/com/m3u/data/repository/channel/ChannelRepository.kt", '''interface ChannelRepository {
    suspend fun getByPlaylistUrl(playlistUrl: String): List<Channel>
}
''')
        write(root, "data/src/main/java/com/m3u/data/repository/channel/ChannelRepositoryImpl.kt", '''internal class ChannelRepositoryImpl : ChannelRepository {
    override suspend fun getByPlaylistUrl(playlistUrl: String): List<Channel> = channelDao.getByPlaylistUrl(playlistUrl)
}
''')

    def test_patch_applies_and_keeps_package_separate(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            self.make_fixture(root)
            result = subprocess.run([sys.executable, str(PATCH), "--source", str(root)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            build = (root / "app/smartphone/build.gradle.kts").read_text()
            self.assertIn('applicationId = "com.m3u.smartphone.enhanced"', build)
            self.assertIn('versionName = "1.15.1-plus1"', build)
            self.assertIn('versionCode = 146', build)
            manifest = (root / "app/smartphone/src/main/AndroidManifest.xml").read_text()
            self.assertIn("com.m3u.plus.permission.BIND_EXTENSION_HOST", manifest)
            protocol = (root / "extension/transport-android/src/main/java/com/m3u/extension/transport/android/ExtensionProtocol.kt").read_text()
            self.assertIn("com.m3u.plus.permission.BIND_EXTENSION_HOST", protocol)
            self.assertIn('create("plusRelease")', build)
            self.assertIn('plusReleaseSigningConfigured', build)
            self.assertIn('providers.gradleProperty("m3uArm64")', build)
            self.assertIn('include("arm64-v8a")', build)
            graph = (root / "app/smartphone/src/main/java/com/m3u/smartphone/ui/common/RootGraph.kt").read_text()
            self.assertIn("Destination.Advanced.name", graph)
            advanced = root / "app/smartphone/src/main/java/com/m3u/smartphone/ui/business/advanced"
            self.assertTrue((advanced / "AdvancedToolsScreen.kt").is_file())
            self.assertTrue((advanced / "BulkM3uBuilder.kt").is_file())
            self.assertTrue((root / ".github/workflows/build-plus.yml").is_file())
            dao = (root / "data/src/main/java/com/m3u/data/database/dao/ChannelDao.kt").read_text()
            self.assertIn("getFirstByPlaylistUrl", dao)
            self.assertTrue((root / ".m3u-plus-patch-backup").is_dir())

    def test_unknown_version_is_rejected_before_any_mutation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            self.make_fixture(root)
            build_path = root / "app/smartphone/build.gradle.kts"
            build_path.write_text(build_path.read_text().replace('1.15.1', '1.15.2'), encoding="utf-8")
            before = build_path.read_text()
            result = subprocess.run([sys.executable, str(PATCH), "--source", str(root)], capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(before, build_path.read_text())
            self.assertFalse((root / ".m3u-plus-patch-backup").exists())


if __name__ == "__main__":
    unittest.main()
