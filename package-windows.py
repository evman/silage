"""Create a portable Windows EXE, folder and ZIP; verify bundled mpv."""
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
archive = root / 'dist/Silage-Windows-x64.zip'
portable = root / 'dist/Silage-Portable.exe'
backup = root / 'dist/backups' / datetime.now().strftime('before-package-%Y%m%d-%H%M%S-%f')
backup.mkdir(parents=True)
backed_up = []
try:
    for item in [target, archive, portable]:
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
    # Archive the clean app BEFORE restoring the developer's personal settings.
    shutil.make_archive(str(archive.with_suffix('')), 'zip', target.parent, target.name)
    subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
                    '-File', str(root / 'scripts/package-single-exe.ps1'),
                    '-Archive', str(archive), '-Output', str(portable)], check=True)
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
    archive.unlink(missing_ok=True)
    portable.unlink(missing_ok=True)
    for item in backed_up:
        (backup / item.name).rename(item)
    raise
print('Executable:', portable)
print('Previous copy saved in:', backup)
