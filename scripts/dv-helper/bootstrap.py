#!/usr/bin/python3 -I
"""Install the bundled, fixed DV helper files, then run only that helper as root.

Used by portable app images after explicit pkexec administrator authorization.
No arguments, shell commands, destinations or media are accepted from the app.
Package installations already contain the root-owned helper and skip this step.
"""
import os
from pathlib import Path
import stat
import sys
import tempfile

INSTALL = Path("/usr/libexec/silage-dv-helper")
POLICY_PATH = Path("/usr/share/polkit-1/actions/dev.silage.dv-helper.policy")
POLICY = '''<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE policyconfig PUBLIC "-//freedesktop//DTD polkit Policy Configuration 1.0//EN"
 "http://www.freedesktop.org/software/polkit/policyconfig-1.dtd">
<policyconfig>
  <vendor>Silage</vendor>
  <action id="dev.silage.dv-helper.signal">
    <description>Enable experimental Dolby Vision display signaling</description>
    <message>Administrator authorization is required for Silage's experimental Dolby Vision display helper.</message>
    <defaults>
      <allow_any>no</allow_any>
      <allow_inactive>no</allow_inactive>
      <allow_active>auth_admin</allow_active>
    </defaults>
    <annotate key="org.freedesktop.policykit.exec.path">/usr/libexec/silage-dv-helper/silage-dv-helper</annotate>
  </action>
</policyconfig>
'''.encode()


def protected_directory(path):
    """Reject symlinks and writable/non-root ancestors before making child dirs."""
    if path.is_symlink():
        raise ValueError("Helper installation directory is a symlink")
    if not path.exists():
        protected_directory(path.parent)
        path.mkdir(mode=0o755)
    info = path.stat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
        raise ValueError("Helper installation directory is not protected")
    if path != path.parent:
        protected_directory(path.parent)


def replace_file(destination, content, mode):
    protected_directory(destination.parent)
    if destination.is_symlink():
        raise ValueError("Helper installation file is a symlink")
    fd, temporary = tempfile.mkstemp(prefix='.silage-', dir=destination.parent)
    try:
        with os.fdopen(fd, 'wb') as output:
            output.write(content)
            os.fchmod(output.fileno(), mode)
            os.fsync(output.fileno())
        os.replace(temporary, destination)
    finally:
        Path(temporary).unlink(missing_ok=True)


def install(source):
    # Read both payloads before changing the installation. Source is the fixed
    # directory beside this bootstrap; callers cannot select arbitrary files.
    script = (source / 'silage-dv-helper').read_bytes()
    snapshot = (source / 'kms-snapshot').read_bytes()
    if not script.startswith(b'#!/usr/bin/python3 -I\n') or snapshot[:4] != b'\x7fELF':
        raise ValueError("Invalid bundled helper payload")
    replace_file(INSTALL / 'kms-snapshot', snapshot, 0o755)
    replace_file(INSTALL / 'silage-dv-helper', script, 0o755)
    replace_file(POLICY_PATH, POLICY, 0o644)


def main():
    if sys.argv[1:] or os.geteuid() != 0:
        raise ValueError("Helper setup requires administrator authorization and accepts no arguments")
    install(Path(__file__).resolve().parent)
    executable = str(INSTALL / 'silage-dv-helper')
    os.chdir('/')
    os.execve(executable, [executable], {'PATH': '/usr/bin:/bin', 'LC_ALL': 'C', 'HOME': '/root'})


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError) as error:
        print(f'Silage DV helper setup: {error}', file=sys.stderr)
        sys.exit(1)
