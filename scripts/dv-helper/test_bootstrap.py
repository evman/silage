import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('silage_dv_bootstrap', Path(__file__).with_name('bootstrap.py'))
bootstrap = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bootstrap)


class BootstrapTests(unittest.TestCase):
    def test_policy_matches_packaged_policy_and_requires_administrator(self):
        self.assertEqual(bootstrap.POLICY, Path(__file__).with_name('dev.silage.dv-helper.policy').read_bytes())
        self.assertIn(b'<allow_active>auth_admin</allow_active>', bootstrap.POLICY)

    def test_install_replaces_only_fixed_payloads_with_protected_modes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / 'source'
            source.mkdir()
            (source / 'silage-dv-helper').write_bytes(b'#!/usr/bin/python3 -I\n# helper')
            (source / 'kms-snapshot').write_bytes(b'\x7fELFtest executable')
            installed = root / 'installed'
            installed.mkdir()
            policy = root / 'policy'
            with patch.object(bootstrap, 'INSTALL', installed), patch.object(bootstrap, 'POLICY_PATH', policy), \
                    patch.object(bootstrap, 'protected_directory'):
                bootstrap.install(source)
            self.assertEqual((installed / 'silage-dv-helper').read_bytes(), (source / 'silage-dv-helper').read_bytes())
            self.assertEqual((installed / 'kms-snapshot').stat().st_mode & 0o777, 0o755)
            self.assertEqual(policy.stat().st_mode & 0o777, 0o644)
            self.assertEqual(policy.read_bytes(), bootstrap.POLICY)

    def test_invalid_bundle_makes_no_installation_changes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'silage-dv-helper').write_bytes(b'invalid')
            (root / 'kms-snapshot').write_bytes(b'not ELF')
            with patch.object(bootstrap, 'replace_file') as write:
                with self.assertRaises(ValueError):
                    bootstrap.install(root)
                write.assert_not_called()

    def test_writable_ancestors_and_symlink_destinations_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            writable = root / 'writable'
            writable.mkdir()
            writable.chmod(0o777)
            with self.assertRaises(ValueError):
                bootstrap.protected_directory(writable)
            link = root / 'link'
            target = root / 'target'
            target.write_bytes(b'original')
            link.symlink_to(target)
            with patch.object(bootstrap, 'protected_directory'):
                with self.assertRaises(ValueError):
                    bootstrap.replace_file(link, b'changed', 0o755)
            self.assertEqual(target.read_bytes(), b'original')

    def test_bootstrap_accepts_no_paths_or_programs_from_caller(self):
        with patch.object(bootstrap.sys, 'argv', ['setup-helper', '/tmp/arbitrary']), patch.object(bootstrap, 'install') as install:
            with self.assertRaises(ValueError):
                bootstrap.main()
            install.assert_not_called()


if __name__ == '__main__':
    unittest.main()
