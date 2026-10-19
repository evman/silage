"""Exercise Arch helper migration without touching the host package database."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest


@unittest.skipIf(os.name == 'nt', 'POSIX installer')
class ArchInstallTest(unittest.TestCase):
    PATHS = (
        'usr/libexec/silage-dv-helper/kms-snapshot',
        'usr/libexec/silage-dv-helper/silage-dv-helper',
        'usr/share/polkit-1/actions/dev.silage.dv-helper.policy',
    )

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        source = Path(__file__).resolve().parents[2] / 'install.sh'
        # Load only the real installer's functions, with filesystem paths staged
        # in our fixture. Never execute its download or distro-selection code.
        self.functions = source.read_text().split('\nid="";', 1)[0]
        self.functions = self.functions.replace('/usr/', str(self.root / 'usr') + '/')
        self.record = self.root / 'arguments'

    def existing(self, *paths):
        for path in paths:
            file = self.root / path
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text('previous helper')

    def install(self, owned=(), frontend=('pacman', '-U', 'release.pkg.tar.zst'), status=0):
        owners = '\n'.join(
            f'{shlex.quote(str(self.root / path))}) return 0 ;;' for path in owned
        )
        script = self.functions + f'''
ask() {{ "$@"; }}
pacman() {{
  [ "$1" = -Qoq ] || return 99
  case "$2" in
    {owners}
  esac
  return 1
}}
capture() {{
  printf '%s\\n' "$@" > {shlex.quote(str(self.record))}
  return {status}
}}
arch_install capture {shlex.join(frontend)}
'''
        result = subprocess.run(['sh', '-c', script], capture_output=True, text=True)
        arguments = self.record.read_text().splitlines() if self.record.exists() else []
        return result, arguments

    def overwrite_args(self, *paths):
        args = []
        for path in paths:
            args += ['--overwrite', str(self.root / path).lstrip('/')]
        return args

    def test_clean_install_needs_no_override(self):
        result, args = self.install()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['pacman', '-U', 'release.pkg.tar.zst'], args)

    def test_unowned_helper_files_are_migrated_exactly(self):
        self.existing(*self.PATHS, 'usr/libexec/silage-dv-helper/unrelated')
        result, args = self.install()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['pacman', '-U', 'release.pkg.tar.zst'] +
                         self.overwrite_args(*self.PATHS), args)
        # Preflight must not delete or modify the old installation.
        for path in self.PATHS:
            self.assertEqual('previous helper', (self.root / path).read_text())

    def test_package_owned_files_are_never_overridden(self):
        self.existing(*self.PATHS)
        result, args = self.install(owned=self.PATHS)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn('--overwrite', args)

    def test_mixed_ownership_only_migrates_unowned_files(self):
        self.existing(*self.PATHS)
        result, args = self.install(owned=self.PATHS[:2])
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['pacman', '-U', 'release.pkg.tar.zst'] +
                         self.overwrite_args(self.PATHS[2]), args)

    def test_symlinks_and_directories_are_not_migrated(self):
        self.existing(self.PATHS[0])
        target = self.root / self.PATHS[0]
        link = self.root / self.PATHS[1]
        link.symlink_to(target)
        (self.root / self.PATHS[2]).mkdir(parents=True)
        result, args = self.install()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['pacman', '-U', 'release.pkg.tar.zst'] +
                         self.overwrite_args(self.PATHS[0]), args)
        self.assertTrue(link.is_symlink())

    def test_aur_frontend_receives_same_migration(self):
        self.existing(*self.PATHS)
        result, args = self.install(frontend=('paru', '-S', '--needed', 'silage-bin'))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['paru', '-S', '--needed', 'silage-bin'] +
                         self.overwrite_args(*self.PATHS), args)

    def test_failure_propagates_and_leaves_existing_files(self):
        self.existing(*self.PATHS)
        result, _ = self.install(status=42)
        self.assertEqual(42, result.returncode)
        for path in self.PATHS:
            self.assertEqual('previous helper', (self.root / path).read_text())
