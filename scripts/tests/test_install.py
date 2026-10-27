"""Exercise Arch helper migration without touching the host package database."""
import os
from pathlib import Path
import shlex
import shutil
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
        # Load only the real installer's helper functions, with filesystem paths
        # staged in our fixture. Never execute its repository or download code.
        self.functions = source.read_text().split('\nmain() {', 1)[0]
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

    def test_repository_install_receives_same_migration(self):
        self.existing(*self.PATHS)
        result, args = self.install(frontend=('pacman', '-Syu', '--needed', 'silage-bin'))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['pacman', '-Syu', '--needed', 'silage-bin'] +
                         self.overwrite_args(*self.PATHS), args)

    def test_failure_propagates_and_leaves_existing_files(self):
        self.existing(*self.PATHS)
        result, _ = self.install(status=42)
        self.assertEqual(42, result.returncode)
        for path in self.PATHS:
            self.assertEqual('previous helper', (self.root / path).read_text())


@unittest.skipIf(os.name == 'nt', 'POSIX installer')
class AptRepositoryTest(unittest.TestCase):
    """A broken third-party apt source must not stop the install (set -e would abort on it)."""
    SILAGE_ONLY = ['update', '-o', 'Dir::Etc::sourcelist=sources.list.d/silage.list',
                   '-o', 'Dir::Etc::sourceparts=-', '-o', 'APT::Get::List-Cleanup=0']

    def run_apt_repository(self, full_update=0, silage_update=0, install=0):
        source = Path(__file__).resolve().parents[2] / 'install.sh'
        functions = source.read_text().split('\nmain() {', 1)[0]
        # Only the real apt_repository; root access and file writes are fakes, and apt-get is a
        # stub program on PATH (dash, Ubuntu's sh, rejects a function named apt-get).
        stubs = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, stubs)
        apt = stubs / 'apt-get'
        apt.write_text(f'''#!/bin/sh
echo "apt-get $*" >&2
case "$*" in
  update) exit {full_update} ;;
  update\\ -o*) exit {silage_update} ;;
  install*) exit {install} ;;
esac
''')
        apt.chmod(0o755)
        script = 'set -eu\n' + functions + '''
sudo_() { "$@"; }
put() { cat >/dev/null; }
silage_keyring() { :; }
apt_repository
echo finished
'''
        env = dict(os.environ, PATH=f'{stubs}{os.pathsep}{os.environ["PATH"]}')
        result = subprocess.run(['sh', '-c', script], capture_output=True, text=True, env=env)
        calls = [line.split(' ', 1)[1].split(' ') for line in result.stderr.splitlines()
                 if line.startswith('apt-get ')]
        return result, calls

    def test_working_sources_update_everything_then_install(self):
        result, calls = self.run_apt_repository()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([['update'], ['install', '-y', 'silage']], calls)

    def test_broken_other_source_falls_back_to_the_silage_source(self):
        result, calls = self.run_apt_repository(full_update=100)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([['update'], self.SILAGE_ONLY, ['install', '-y', 'silage']], calls)
        self.assertIn('finished', result.stdout)

    def test_failed_refresh_only_warns_and_lets_install_decide(self):
        result, calls = self.run_apt_repository(full_update=100, silage_update=100)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['install', '-y', 'silage'], calls[-1])
        self.assertIn('Warning', result.stdout)

    def test_install_failure_still_fails(self):
        result, _ = self.run_apt_repository(full_update=100, install=100)
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn('finished', result.stdout)


@unittest.skipIf(os.name == 'nt', 'POSIX installer')
class EmbeddedKeyTest(unittest.TestCase):
    """install.sh trusts the repository key it embeds, so it must be the committed one."""
    ROOT = Path(__file__).resolve().parents[2]
    KEYS = ROOT / 'packaging' / 'keys'

    def embedded(self, function):
        # Every definition, but not the final `main "$@"` that would install.
        definitions = (self.ROOT / 'install.sh').read_text().rsplit('\nmain "$@"', 1)[0]
        return subprocess.run(['sh', '-c', definitions + '\n' + function],
                              capture_output=True, check=True).stdout

    def test_armored_key_is_the_repository_key(self):
        self.assertEqual((self.KEYS / 'silage-archive-keyring.asc').read_bytes(),
                         self.embedded('silage_key_asc'))

    def test_apt_keyring_is_the_repository_key(self):
        self.assertEqual((self.KEYS / 'silage-archive-keyring.gpg').read_bytes(),
                         self.embedded('silage_keyring'))

    @unittest.skipUnless(shutil.which('gpg'), 'needs gpg')
    def test_pacman_trusts_the_key_by_its_fingerprint(self):
        keys = subprocess.run(['gpg', '--show-keys', '--with-colons',
                               str(self.KEYS / 'silage-archive-keyring.asc')],
                              capture_output=True, text=True, check=True).stdout
        fingerprint = next(line.split(':')[9] for line in keys.splitlines()
                           if line.startswith('fpr:'))
        self.assertEqual(fingerprint.encode() + b'\n',
                         self.embedded('echo "$fingerprint"'))
