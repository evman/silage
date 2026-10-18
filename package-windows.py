"""Create a self-contained Windows folder and ZIP; verify bundled mpv."""
from pathlib import Path
import os
import shutil
import subprocess
import sys
root = Path(__file__).resolve().parent
subprocess.run([sys.executable, str(root / 'build-windows.py'), ':app:createDistributable', '--console=plain'], check=True)
source = root / 'app/build/compose/binaries/main/app/silage'
target = root / 'dist/Silage'
if target.exists():
    for item in target.rglob('*'):
        if item.is_file():
            item.chmod(0o666)
    shutil.rmtree(target)
shutil.copytree(source, target)
shutil.copy2(root / 'native/libmpv-2.dll', target)
shutil.copy2(root / 'LICENSE', target / 'LICENSE-Silage.txt')
for name in ['LICENSE-mpv-GPL.txt', 'Copyright-mpv.txt']:
    shutil.copy2(root / 'native' / name, target / name)
shutil.copy2(root / 'WINDOWS.md', target / 'WINDOWS.md')
# Verification must not rely on developer-only JNA paths or Java.
env = os.environ.copy()
env.pop('JAVA_TOOL_OPTIONS', None)
env.pop('JAVA_HOME', None)
result = subprocess.run([str(target / 'silage.exe'), '--check'], cwd=root, env=env, capture_output=True, text=True, timeout=30)
print(result.stdout, result.stderr)
result.check_returncode()
shutil.make_archive(str(root / 'dist/Silage-Windows-x64'), 'zip', target.parent, target.name)
print('Executable:', target / 'silage.exe')
