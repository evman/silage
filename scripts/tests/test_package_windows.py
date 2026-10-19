"""Exercise packaging with fake build output, without requiring Windows or a JDK."""
from pathlib import Path
import runpy
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile


class WindowsPackageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        script = Path(__file__).resolve().parents[2] / 'package-windows.py'
        shutil.copy2(script, self.root)
        self.source = self.root / 'app/build/compose/binaries/main/app/silage'
        self.source.mkdir(parents=True)
        (self.source / 'silage.exe').write_text('new executable')
        # A developer may have tested both the build output and the previous dist.
        self.write(self.source / 'userdata/Data/session.json', 'build output secret')
        self.target = self.root / 'dist/Silage'
        self.write(self.target / 'silage.exe', 'old executable')
        self.write(self.target / 'userdata/Data/session.json', 'remembered sign-in')
        self.write(self.target / 'userdata/Config/ui.json', 'my settings')
        self.archive = self.root / 'dist/Silage-Windows-x64.zip'
        self.archive.write_bytes(b'previous zip')
        self.portable = self.root / 'dist/Silage-Portable.exe'
        self.portable.write_bytes(b'previous portable exe')
        for name in ['libmpv-2.dll', 'LICENSE-mpv-GPL.txt', 'Copyright-mpv.txt']:
            self.write(self.root / 'native' / name, name)
        for name in ['LICENSE', 'WINDOWS.md']:
            self.write(self.root / name, name)

    @staticmethod
    def write(path, text):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def package(self, check_code=0, launcher_fails=False):
        def fake_run(args, **kwargs):
            if '-Output' in args:
                if launcher_fails:
                    raise subprocess.CalledProcessError(1, args)
                Path(args[args.index('-Output') + 1]).write_bytes(b'new portable exe')
            return subprocess.CompletedProcess(args, check_code if '--check' in args else 0,
                                               stdout='', stderr='')

        with patch('sys.argv', ['package-windows.py']), patch('subprocess.run', side_effect=fake_run):
            runpy.run_path(str(self.root / 'package-windows.py'), run_name='__main__')

    def assert_old_data(self):
        self.assertEqual('remembered sign-in', (self.target / 'userdata/Data/session.json').read_text())
        self.assertEqual('my settings', (self.target / 'userdata/Config/ui.json').read_text())

    def test_rebuild_preserves_settings_but_ships_no_personal_data(self):
        self.package()
        self.assert_old_data()
        self.assertEqual('new executable', (self.target / 'silage.exe').read_text())
        self.assertEqual(b'new portable exe', self.portable.read_bytes())
        with zipfile.ZipFile(self.archive) as archive:
            self.assertIn('Silage/silage.exe', archive.namelist())
            self.assertFalse(any('userdata' in Path(name).parts for name in archive.namelist()))
        backup, = (self.root / 'dist/backups').iterdir()
        self.assertEqual('remembered sign-in', (backup / 'Silage/userdata/Data/session.json').read_text())
        self.assertEqual(b'previous zip', (backup / self.archive.name).read_bytes())
        self.assertEqual(b'previous portable exe', (backup / self.portable.name).read_bytes())

    def test_failed_native_check_restores_previous_app_and_zip(self):
        with self.assertRaises(subprocess.CalledProcessError):
            self.package(check_code=2)
        self.assert_old_data()
        self.assertEqual('old executable', (self.target / 'silage.exe').read_text())
        self.assertEqual(b'previous zip', self.archive.read_bytes())
        self.assertEqual(b'previous portable exe', self.portable.read_bytes())

    def test_failed_zip_restores_previous_app_and_zip(self):
        with patch('shutil.make_archive', side_effect=OSError('disk full')):
            with self.assertRaises(OSError):
                self.package()
        self.assert_old_data()
        self.assertEqual(b'previous zip', self.archive.read_bytes())

    def test_failed_launcher_build_restores_previous_artifacts(self):
        with self.assertRaises(subprocess.CalledProcessError):
            self.package(launcher_fails=True)
        self.assert_old_data()
        self.assertEqual(b'previous zip', self.archive.read_bytes())
        self.assertEqual(b'previous portable exe', self.portable.read_bytes())

    def test_first_package_has_no_userdata(self):
        shutil.rmtree(self.target)
        self.archive.unlink()
        self.portable.unlink()
        self.package()
        self.assertFalse((self.target / 'userdata').exists())
        self.assertTrue(self.archive.exists())

    def test_failed_userdata_copy_restores_original_including_zip(self):
        copytree = shutil.copytree

        def fail_userdata_copy(source, destination, *args, **kwargs):
            if Path(source).name == 'userdata':
                raise OSError('cannot copy settings')
            return copytree(source, destination, *args, **kwargs)

        with patch('shutil.copytree', side_effect=fail_userdata_copy):
            with self.assertRaises(OSError):
                self.package()
        self.assert_old_data()
        self.assertEqual('old executable', (self.target / 'silage.exe').read_text())
        self.assertEqual(b'previous zip', self.archive.read_bytes())


if __name__ == '__main__':
    unittest.main()
