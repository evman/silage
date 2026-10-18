"""Build locally with the dedicated portable JDK; no global installation."""
import os
from pathlib import Path
import subprocess
import sys
root = Path(__file__).resolve().parent
jdk = next((root / 'toolchains').glob('jdk-*'))
env = os.environ.copy()
env['JAVA_HOME'] = str(jdk)
env['PATH'] = str(jdk / 'bin') + os.pathsep + str(root / 'native') + os.pathsep + env['PATH']
env['JAVA_TOOL_OPTIONS'] = '-Djna.library.path=' + str(root / 'native')
sys.exit(subprocess.call([str(jdk / 'bin/java.exe'), '-classpath', str(root / 'gradle/wrapper/gradle-wrapper.jar'), 'org.gradle.wrapper.GradleWrapperMain', *sys.argv[1:]], cwd=root, env=env))
