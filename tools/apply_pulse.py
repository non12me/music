#!/usr/bin/env python3
"""Apply a small, checked overlay without changing the upstream database or version."""
from pathlib import Path
import argparse
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = Path("app/src/main/kotlin/com/metrolist/music")

def replace_once(text, before, after):
    if after in text:
        return text
    if text.count(before) != 1:
        raise ValueError(f"La base cambió; se necesita revisar esta integración: {before[:100]!r}")
    return text.replace(before, after, 1)

def apply(source: Path):
    pending = {}
    def edit(relative, transform):
        path = source / relative
        old = pending.get(path, path.read_text(encoding="utf-8"))
        pending[path] = transform(old)

    edit(KOTLIN / "App.kt", lambda s: replace_once(s,
        "    private suspend fun initializeSettings() {",
        "    private suspend fun initializeSettings() {\n        com.metrolist.music.pulse.PulseDefaults.initialize(this)"))
    edit(KOTLIN / "ui/screens/NavigationBuilder.kt", lambda s: replace_once(s,
        '    composable("settings") {',
        '    composable("settings/pulse") {\n        com.metrolist.music.pulse.PulseHub(navController)\n    }\n\n    composable("settings") {'))
    edit(KOTLIN / "ui/screens/settings/SettingsScreen.kt", lambda s: replace_once(s,
        "        // User Interface Section",
        '''        Material3SettingsGroup(
            title = stringResource(R.string.pulse_title),
            items = listOf(Material3SettingsItem(
                icon = painterResource(R.drawable.pulse_icon),
                title = { Text(stringResource(R.string.pulse_hub_entry)) },
                onClick = { navController.navigate("settings/pulse") }
            ))
        )
        Spacer(Modifier.height(16.dp))

        // User Interface Section'''))

    def disable_original_updater(s):
        s = s.replace('buildConfigField("Boolean", "UPDATER_AVAILABLE", "true")',
                      'buildConfigField("Boolean", "UPDATER_AVAILABLE", "false")')
        if s.count('buildConfigField("Boolean", "UPDATER_AVAILABLE", "false")') != 3:
            raise ValueError("Cambió la configuración de variantes/actualizador. Revisión necesaria.")
        # Defaults also apply when opened directly in Android Studio.
        s = replace_once(s, 'val baseApplicationId = "com.metrolist.music"',
                         'val baseApplicationId = "com.brandon.pulse.music"')
        s = replace_once(s, 'appNameOverride ?: "Metrolist"', 'appNameOverride ?: "Pulse Music"')
        s = replace_once(s, 'resValue("string", "app_name", "Metrolist Debug")',
                         'resValue("string", "app_name", "Pulse Music Debug")')
        return s
    edit("app/build.gradle.kts", disable_original_updater)

    def updater_guard(s):
        s = replace_once(s,
            '    suspend fun getLatestKmpRelease(): Result<ReleaseInfo?> =\n        withContext(Dispatchers.IO) {',
            '    suspend fun getLatestKmpRelease(): Result<ReleaseInfo?> =\n        withContext(Dispatchers.IO) {\n            if (!BuildConfig.UPDATER_AVAILABLE) return@withContext Result.success(null)')
        s = replace_once(s,
            '    suspend fun checkForUpdate(forceRefresh: Boolean = false): Result<Pair<ReleaseInfo?, Boolean>> =\n        withContext(Dispatchers.IO) {',
            '    suspend fun checkForUpdate(forceRefresh: Boolean = false): Result<Pair<ReleaseInfo?, Boolean>> =\n        withContext(Dispatchers.IO) {\n            if (!BuildConfig.UPDATER_AVAILABLE) return@withContext Result.success(null to false)')
        return s
    edit(KOTLIN / "utils/Updater.kt", updater_guard)

    def manifest(s):
        if 'android:icon="@mipmap/ic_launcher"' not in s and 'android:icon="@drawable/pulse_icon"' not in s:
            raise ValueError("Cambió la configuración del icono de la aplicación.")
        for name in ['ic_launcher', 'ic_launcher_round', 'ic_launcher_static', 'ic_launcher_static_round']:
            s = s.replace(f'"@mipmap/{name}"', '"@drawable/pulse_icon"')
        marker = '        <!-- Pulse daily backup -->'
        if marker not in s:
            s = replace_once(s, '    </application>',
                marker + '\n        <service android:name=".pulse.PulseBackupJobService"\n'
                '            android:permission="android.permission.BIND_JOB_SERVICE" android:exported="false" />\n    </application>')
        ET.fromstring(s)
        return s
    edit("app/src/main/AndroidManifest.xml", manifest)

    strings_path = Path("app/src/main/res/values/metrolist_strings.xml")
    def add_strings(s):
        extras = ET.parse(ROOT / "overlay/pulse_strings.xml").getroot()
        existing = {e.attrib.get("name") for e in ET.fromstring(s)}
        new_entries = []
        for entry in extras:
            name = entry.attrib["name"]
            rendered = ET.tostring(entry, encoding="unicode").strip()
            if name in existing:
                pattern = rf'<string name="{re.escape(name)}">.*?</string>'
                s, count = re.subn(pattern, lambda _: rendered, s, flags=re.DOTALL)
                if count != 1:
                    raise ValueError(f"Recurso Pulse incompatible: {name}")
            else:
                new_entries.append("    " + rendered)
        additions = "\n".join(new_entries)
        return s.replace("</resources>", additions + "\n</resources>") if additions else s
    edit(strings_path, add_strings)
    for file in (ROOT / "overlay").glob("*.kt"):
        pending[source / KOTLIN / "pulse" / file.name] = file.read_text(encoding="utf-8")
    pending[source / "app/src/main/res/drawable/pulse_icon.xml"] = (ROOT / "overlay/pulse_icon.xml").read_text()

    # Do not copy device-bound encrypted sessions through Android's OS backup/transfer.
    for relative, tag in [("app/src/main/res/xml/backup_rules.xml", "full-backup-content"),
                          ("app/src/main/res/xml/data_extraction_rules.xml", "cloud-backup")]:
        path = source / relative
        if not path.exists():
            continue
        s = path.read_text()
        root = ET.fromstring(s)
        sections = [root] if tag == "full-backup-content" else list(root)
        for section in sections:
            existing = {(e.get("domain"), e.get("path")) for e in section.findall("exclude")}
            if ("sharedpref", "pulse_private.xml") not in existing:
                ET.SubElement(section, "exclude", domain="sharedpref", path="pulse_private.xml")
        pending[path] = '<?xml version="1.0" encoding="utf-8"?>\n' + ET.tostring(root, encoding="unicode") + "\n"

    # Every anchor was checked before mutating a source file.
    for path, content in pending.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
    test_root = ROOT / "overlay/tests"
    for test in test_root.glob("*.kt"):
        destination = source / "app/src/test/kotlin/com/metrolist/music/pulse" / test.name
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(test.read_text())
    return sorted(str(p.relative_to(source)) for p in pending)

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=ROOT / "android")
    args = parser.parse_args()
    try:
        changed = apply(args.source.resolve())
        print(f"Pulse integrado: {len(changed)} archivos. Esquema y versión de la base conservados.")
    except (ValueError, OSError, ET.ParseError) as error:
        raise SystemExit(f"No se aplicó la actualización: {error}")
