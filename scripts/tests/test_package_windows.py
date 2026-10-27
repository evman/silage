"""Exercise packaging with fake build output, without requiring Windows, a JDK or Inno Setup."""
from pathlib import Path
import runpy
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch


class WindowsPackageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        # Resolved like package-windows.py resolves itself: on Windows the temp dir can be an
        # 8.3 short path (C:\Users\RUNNER~1) that resolve() expands to the long one.
        self.root = Path(self.temp.name).resolve()
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
        self.setup = self.root / 'dist/Silage-0.5.0-Setup.exe'
        self.setup.write_bytes(b'previous setup')
        self.latest = self.root / 'dist/Silage-Setup.exe'
        self.latest.write_bytes(b'previous latest setup')
        for name in ['libmpv-2.dll', 'LICENSE-mpv-GPL.txt', 'Copyright-mpv.txt']:
            self.write(self.root / 'native' / name, name)
        for name in ['vulkan-1.dll', 'VulkanRT-License.txt']:
            self.write(self.root / 'native/fallback' / name, name)
        for name in ['LICENSE', 'WINDOWS.md']:
            self.write(self.root / name, name)
        self.installer_inputs = None
        self.iscc_args = None

    @staticmethod
    def write(path, text):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def package(self, check_code=0, iscc_fails=False, version_output='Silage 0.5.0\n', iscc='ISCC.exe'):
        def fake_run(args, **kwargs):
            if args[0] == 'ISCC.exe':
                self.iscc_args = args
                app_dir = Path(next(a for a in args if a.startswith('/DAppDir=')).removeprefix('/DAppDir='))
                self.installer_inputs = sorted(p.relative_to(app_dir).as_posix() for p in app_dir.rglob('*'))
                if iscc_fails:
                    raise subprocess.CalledProcessError(2, args)
                version = next(a for a in args if a.startswith('/DVersion=')).removeprefix('/DVersion=')
                out = Path(next(a for a in args if a.startswith('/O')).removeprefix('/O'))
                (out / f'Silage-{version}-Setup.exe').write_bytes(b'new setup')
            stdout = version_output if '--version' in args else ''
            return subprocess.CompletedProcess(args, check_code if '--check' in args else 0,
                                               stdout=stdout, stderr='')

        with patch('sys.argv', ['package-windows.py']), patch('subprocess.run', side_effect=fake_run), \
                patch('shutil.which', return_value=iscc):
            runpy.run_path(str(self.root / 'package-windows.py'), run_name='__main__')

    def assert_old_data(self):
        self.assertEqual('remembered sign-in', (self.target / 'userdata/Data/session.json').read_text())
        self.assertEqual('my settings', (self.target / 'userdata/Config/ui.json').read_text())

    def assert_previous_setups(self):
        self.assertEqual(b'previous setup', self.setup.read_bytes())
        self.assertEqual(b'previous latest setup', self.latest.read_bytes())

    def test_rebuild_preserves_settings_but_ships_no_personal_data(self):
        self.package()
        self.assert_old_data()
        self.assertEqual('new executable', (self.target / 'silage.exe').read_text())
        self.assertEqual(b'new setup', self.setup.read_bytes())
        self.assertEqual(b'new setup', self.latest.read_bytes())
        self.assertIn('/DVersion=0.5.0', self.iscc_args)
        self.assertEqual(str(self.root / 'packaging/windows/silage.iss'), self.iscc_args[-1])
        self.assertIn('silage.exe', self.installer_inputs)
        self.assertIn('fallback/vulkan-1.dll', self.installer_inputs)
        self.assertIn('fallback/VulkanRT-License.txt', self.installer_inputs)
        self.assertFalse(any('userdata' in Path(name).parts for name in self.installer_inputs))
        backup, = (self.root / 'dist/backups').iterdir()
        self.assertEqual('remembered sign-in', (backup / 'Silage/userdata/Data/session.json').read_text())
        self.assertEqual(b'previous setup', (backup / self.setup.name).read_bytes())
        self.assertEqual(b'previous latest setup', (backup / self.latest.name).read_bytes())

    def test_failed_native_check_restores_previous_app_and_setups(self):
        with self.assertRaises(subprocess.CalledProcessError):
            self.package(check_code=2)
        self.assert_old_data()
        self.assertEqual('old executable', (self.target / 'silage.exe').read_text())
        self.assert_previous_setups()

    def test_unexpected_version_output_restores_previous_artifacts(self):
        with self.assertRaises(RuntimeError):
            self.package(version_output='Error: something\n')
        self.assert_old_data()
        self.assert_previous_setups()
        self.assertIsNone(self.iscc_args)

    def test_failed_installer_build_restores_previous_artifacts(self):
        with self.assertRaises(subprocess.CalledProcessError):
            self.package(iscc_fails=True)
        self.assert_old_data()
        self.assert_previous_setups()

    def test_missing_inno_setup_restores_previous_artifacts(self):
        with patch.dict('os.environ', {'ProgramFiles(x86)': '', 'ProgramFiles': '', 'LOCALAPPDATA': ''}):
            with self.assertRaisesRegex(RuntimeError, 'Inno Setup 6'):
                self.package(iscc=None)
        self.assert_old_data()
        self.assert_previous_setups()

    def test_first_package_has_no_userdata(self):
        shutil.rmtree(self.target)
        self.setup.unlink()
        self.latest.unlink()
        self.package()
        self.assertFalse((self.target / 'userdata').exists())
        self.assertTrue(self.setup.exists())
        self.assertTrue(self.latest.exists())

    def test_failed_userdata_copy_restores_original_including_setups(self):
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
        self.assert_previous_setups()


if __name__ == '__main__':
    unittest.main()
