"""Assemble dist/Silage, verify bundled mpv, and build the per-user installer with Inno Setup."""
from pathlib import Path
from datetime import datetime
import os
import shutil
import stat
import subprocess
import sys
root = Path(__file__).resolve().parent
subprocess.run([sys.executable, str(root / 'build-windows.py'), ':app:createDistributable', '--console=plain', *sys.argv[1:]], check=True)
source = root / 'app/build/compose/binaries/main/app/silage'
target = root / 'dist/Silage'
setup_latest = root / 'dist/Silage-Setup.exe'


def find_iscc():
    """Inno Setup 6's compiler: on PATH, or where its installer (and choco) puts it."""
    found = shutil.which('ISCC.exe') or shutil.which('ISCC')
    if found:
        return found
    for base in [os.environ.get('ProgramFiles(x86)'), os.environ.get('ProgramFiles'),
                 os.environ.get('LOCALAPPDATA') and os.path.join(os.environ['LOCALAPPDATA'], 'Programs')]:
        if base and Path(base, 'Inno Setup 6', 'ISCC.exe').exists():
            return str(Path(base, 'Inno Setup 6', 'ISCC.exe'))
    raise RuntimeError('Inno Setup 6 is missing: run scripts/prepare-windows.ps1')


backup = root / 'dist/backups' / datetime.now().strftime('before-package-%Y%m%d-%H%M%S-%f')
backup.mkdir(parents=True)
backed_up = []
setup = None
try:
    for item in [target, setup_latest, *root.glob('dist/Silage-*-Setup.exe')]:
        if item.exists():
            item.rename(backup / item.name)
            backed_up.append(item)
except Exception:
    for item in reversed(backed_up):
        (backup / item.name).rename(item)
    raise

try:
    # A developer may have launched the build output. Never distribute its userdata.
    shutil.copytree(source, target, ignore=lambda folder, names:
        ['userdata'] if Path(folder) == source else [])
    shutil.copy2(root / 'native/libmpv-2.dll', target)
    shutil.copy2(root / 'LICENSE', target / 'LICENSE-Silage.txt')
    for name in ['LICENSE-mpv-GPL.txt', 'Copyright-mpv.txt']:
        shutil.copy2(root / 'native' / name, target / name)
    shutil.copytree(root / 'native/fallback', target / 'fallback')
    shutil.copy2(root / 'WINDOWS.md', target / 'WINDOWS.md')
    subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
                    '-File', str(root / 'scripts/fetch-trailer-tools.ps1'), '-Out', str(target / 'tools')], check=True)
    # Verification must not rely on developer-only JNA paths or Java.
    env = os.environ.copy()
    env.pop('JAVA_TOOL_OPTIONS', None)
    env.pop('JAVA_HOME', None)
    result = subprocess.run([str(target / 'silage.exe'), '--check'], cwd=root, env=env, capture_output=True, text=True, timeout=30)
    print(result.stdout, result.stderr)
    result.check_returncode()
    result = subprocess.run([str(target / 'silage.exe'), '--version'], cwd=root, env=env, capture_output=True, text=True, timeout=30)
    result.check_returncode()
    reported = result.stdout.strip()
    if not reported.startswith('Silage ') or ' ' in reported.removeprefix('Silage '):
        raise RuntimeError(f'Unexpected --version output: {reported!r}')
    version = reported.removeprefix('Silage ')
    setup = root / f'dist/Silage-{version}-Setup.exe'
    # Build the installer from the clean app BEFORE restoring the developer's personal settings.
    subprocess.run([find_iscc(), f'/DVersion={version}', f'/DAppDir={target}', f'/O{target.parent}',
                    str(root / 'packaging/windows/silage.iss')], check=True)
    # Version-free copy, so releases/latest/download/Silage-Setup.exe always gets the newest.
    shutil.copy2(setup, setup_latest)
    previous_data = backup / 'Silage/userdata'
    if previous_data.exists():
        shutil.copytree(previous_data, target / 'userdata')
except Exception:
    if target.exists():
        # Bundled JDK files can be read-only on Windows; remove only the failed
        # new copy so the untouched backup can be restored.
        def remove_readonly(function, path, error):
            if not isinstance(error[1], PermissionError):
                raise error[1]
            os.chmod(path, stat.S_IWRITE)
            function(path)
        shutil.rmtree(target, onerror=remove_readonly)
    if setup:
        setup.unlink(missing_ok=True)
    setup_latest.unlink(missing_ok=True)
    for item in backed_up:
        (backup / item.name).rename(item)
    raise
print('Installer:', setup)
print('Previous copy saved in:', backup)
