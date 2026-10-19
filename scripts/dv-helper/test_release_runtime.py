import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import release_runtime
import package_runtime


class ReleaseRuntimeTests(unittest.TestCase):
    def test_checksums_are_verified_before_install_and_helper_configuration_is_preserved(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bundle = root / 'bundle'
            source = bundle / 'dv-runtime'
            source.mkdir(parents=True)
            hashes = {}
            for name in ('libmpv.so.2', 'libplacebo.so.377'):
                (source / name).write_bytes(b'\x7fELF' + name.encode())
                hashes[name] = hashlib.sha256((source / name).read_bytes()).hexdigest()
            (source / 'runtime.json').write_text(json.dumps({'mpv_commit': package_runtime.MPV,
                'libplacebo_commit': package_runtime.PLACEBO, 'packaged_library_sha256': hashes}))
            image = root / 'image'
            app = image / 'lib/app'
            app.mkdir(parents=True)
            config = app / 'silage.cfg'
            config.write_text('[JavaOptions]\njava-options=-Dsilage.dv.helper.dir=$APPDIR/dv-helper\n')
            (source / 'libmpv.so.2').write_bytes(b'tampered')
            with self.assertRaises(ValueError):
                release_runtime.install(bundle, image)
            self.assertFalse((app / 'dv-runtime').exists())
            (source / 'libmpv.so.2').write_bytes(b'\x7fELFlibmpv.so.2')
            release_runtime.install(bundle, image)
            release_runtime.install(bundle, image)
            self.assertEqual(config.read_text().count(package_runtime.JAVA_OPTION), 1)
            self.assertIn('silage.dv.helper.dir=', config.read_text())
            self.assertNotIn('jna.library.path=', config.read_text(), 'System libmpv fallback must remain possible')


if __name__ == '__main__':
    unittest.main()
