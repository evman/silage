#!/usr/bin/env python3
"""Include the DV helper and its first-launch setup in a Linux Silage app image."""
import argparse
from pathlib import Path
import shutil
import subprocess

JAVA_OPTION = 'java-options=-Dsilage.dv.helper.dir=$APPDIR/dv-helper'


def package(image):
    scripts = Path(__file__).resolve().parent
    repo = scripts.parent.parent
    subprocess.run(['bash', str(scripts / 'install.sh'), '--stage'], check=True)
    staged = repo / 'build/dv-helper/stage/usr/libexec/silage-dv-helper'
    app = image / 'lib/app'
    config = app / 'silage.cfg'
    original = config.read_text()
    if '[JavaOptions]' not in original or not (image / 'bin/silage').is_file():
        raise ValueError('Expected a Linux Silage app image')
    destination = app / 'dv-helper'
    destination.mkdir(exist_ok=True)
    for source, name in [(staged / 'silage-dv-helper', 'silage-dv-helper'),
                         (staged / 'kms-snapshot', 'kms-snapshot'),
                         (scripts / 'bootstrap.py', 'setup-helper')]:
        shutil.copyfile(source, destination / name)
        (destination / name).chmod(0o755)
    shutil.copyfile(scripts / 'dev.silage.dv-helper.policy', destination / 'dev.silage.dv-helper.policy')
    lines = [line for line in original.splitlines() if not line.startswith('java-options=-Dsilage.dv.helper.dir=')]
    lines.insert(lines.index('[JavaOptions]') + 1, JAVA_OPTION)
    config.write_text('\n'.join(lines) + '\n')
    print(f'Bundled DV helper: {destination}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', type=Path, required=True)
    package(parser.parse_args().image.resolve(strict=True))
