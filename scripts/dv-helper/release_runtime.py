#!/usr/bin/env python3
"""Export a verified private renderer, or include that export in a Linux app image."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import tarfile
import tempfile
import package_runtime


def export(runtime, destination):
    destination.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as temporary:
        image = Path(temporary)
        (image / 'bin').mkdir()
        (image / 'bin/silage').touch()
        (image / 'lib/app').mkdir(parents=True)
        (image / 'lib/app/silage.cfg').write_text('[JavaOptions]\n')
        info = package_runtime.manifest(runtime)
        package_runtime.package(runtime, image, info)
        shutil.copytree(image / 'lib/app/dv-runtime', destination / 'dv-runtime', dirs_exist_ok=True)
    # Include the complete corresponding sources, including vendored submodules,
    # the applied patch, licenses, build script and exact runtime manifest.
    with tarfile.open(destination / 'silage-playback-runtime-sources.tar.gz', 'w:gz') as archive:
        def source_only(member):
            if any(part in ('.git', '__pycache__') for part in Path(member.name).parts):
                return None
            return member
        for project in ('mpv', 'libplacebo'):
            archive.add(runtime.parent / 'src' / project, arcname=f'silage-playback-runtime-sources/{project}', filter=source_only)
        for name in ('build_runtime.sh', 'package_runtime.py', 'mpv-dovi-residual-status.patch', 'README.md'):
            archive.add(Path(__file__).with_name(name), arcname=f'silage-playback-runtime-sources/build-scripts/{name}')
        archive.add(Path(__file__).with_name('BUILDING-RUNTIME.md'), arcname='silage-playback-runtime-sources/BUILDING.md')
        archive.add(destination / 'dv-runtime/runtime.json', arcname='silage-playback-runtime-sources/runtime.json')


def install(bundle, image):
    source = bundle / 'dv-runtime'
    info = json.loads((source / 'runtime.json').read_text())
    if info['mpv_commit'] != package_runtime.MPV or info['libplacebo_commit'] != package_runtime.PLACEBO:
        raise ValueError('Runtime revisions differ from the pinned build')
    for name, expected in info['packaged_library_sha256'].items():
        if name not in ('libmpv.so.2', 'libplacebo.so.377'):
            raise ValueError('Unexpected runtime library')
        if hashlib.sha256((source / name).read_bytes()).hexdigest() != expected:
            raise ValueError('Runtime library checksum mismatch')
    if set(info['packaged_library_sha256']) != {'libmpv.so.2', 'libplacebo.so.377'}:
        raise ValueError('Incomplete private renderer')
    app = image / 'lib/app'
    shutil.copytree(source, app / 'dv-runtime', dirs_exist_ok=True)
    config = app / 'silage.cfg'
    lines = [line for line in config.read_text().splitlines() if not line.startswith('java-options=-Dsilage.mpv.runtime.dir=')]
    lines.insert(lines.index('[JavaOptions]') + 1, package_runtime.JAVA_OPTION)
    config.write_text('\n'.join(lines) + '\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    choice = parser.add_mutually_exclusive_group(required=True)
    choice.add_argument('--export', type=Path)
    choice.add_argument('--bundle', type=Path)
    parser.add_argument('--runtime', type=Path)
    parser.add_argument('--image', type=Path)
    args = parser.parse_args()
    if args.export:
        if not args.runtime:
            parser.error('--export requires --runtime')
        export(args.runtime.resolve(strict=True), args.export)
    else:
        if not args.image:
            parser.error('--bundle requires --image')
        install(args.bundle.resolve(strict=True), args.image.resolve(strict=True))
