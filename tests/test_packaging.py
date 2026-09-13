from pathlib import Path
import hashlib
import io
import json
import shutil
import stat
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
from apply_pulse import apply, replace_once, KOTLIN
from prepare_upstream import safe_extract

class PackagingTests(unittest.TestCase):
    def test_overlay_is_idempotent_and_keeps_schema_version(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'android'
            shutil.copytree(ROOT / 'android', target, ignore=shutil.ignore_patterns('.gradle', 'build', '.kotlin'))
            protected = list((target / 'app/schemas').rglob('*.json')) + [target / KOTLIN / 'db/MusicDatabase.kt']
            before = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in protected}
            version = (target / 'app/build.gradle.kts').read_text().split('versionCode =')[1].splitlines()[0]
            files = apply(target)
            first = {p: (target / p).read_bytes() for p in files}
            apply(target)
            self.assertEqual(first, {p: (target / p).read_bytes() for p in files})
            self.assertEqual(before, {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in protected})
            self.assertEqual(version, (target / 'app/build.gradle.kts').read_text().split('versionCode =')[1].splitlines()[0])

    def test_changed_anchor_fails_before_any_write(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'android'
            shutil.copytree(ROOT / 'android', target, ignore=shutil.ignore_patterns('.gradle', 'build', '.kotlin'))
            path = target / KOTLIN / 'ui/screens/NavigationBuilder.kt'
            path.write_text('upstream changed navigation completely')
            before = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in target.rglob('*') if p.is_file()}
            with self.assertRaises(ValueError): apply(target)
            after = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in target.rglob('*') if p.is_file()}
            self.assertEqual(before, after)

    def test_untrusted_archive_cannot_escape_destination(self):
        for name in ['../outside', '/outside', 'source/../../outside', 'source\\..\\outside']:
            data = io.BytesIO()
            with zipfile.ZipFile(data, 'w') as z: z.writestr(name, 'bad')
            with tempfile.TemporaryDirectory() as directory:
                with self.assertRaises(ValueError): safe_extract(data.getvalue(), Path(directory))

    def test_archive_symlink_rejected(self):
        data = io.BytesIO()
        with zipfile.ZipFile(data, 'w') as z:
            link = zipfile.ZipInfo('source/link')
            link.create_system = 3
            link.external_attr = (stat.S_IFLNK | 0o777) << 16
            z.writestr(link, '../../outside')
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError): safe_extract(data.getvalue(), Path(directory))

    def test_resources_and_manifest_are_valid(self):
        resources = ET.parse(ROOT / 'android/app/src/main/res/values/metrolist_strings.xml').getroot()
        names = [r.get('name') for r in resources]
        self.assertEqual(len(names), len(set(names)))
        import re
        used = set()
        for p in (ROOT / 'overlay').glob('*.kt'):
            used.update(re.findall(r'R\.string\.(pulse_\w+)', p.read_text()))
        self.assertTrue(used <= set(names), used - set(names))
        ET.parse(ROOT / 'android/app/src/main/AndroidManifest.xml')

    def test_backup_declares_every_room_entity(self):
        schema = max((ROOT / 'android/app/schemas').rglob('*.json'), key=lambda p: int(p.stem))
        tables = {e['tableName'] for e in json.loads(schema.read_text())['database']['entities']}
        source = (ROOT / 'overlay/PulseLibrarySnapshot.kt').read_text()
        import re
        block = source.split('private val tables = listOf(', 1)[1].split(')', 1)[0]
        self.assertEqual(tables, set(re.findall(r'"([^"]+)"', block)))

if __name__ == '__main__': unittest.main()
