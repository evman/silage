#!/usr/bin/env python3
"""Bundle a pinned, tested renderer in a Linux app image, leaving system libraries alone."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

MPV = "6a262d11f63733d37e3ee6d5fafcb1be1ef83952"
PLACEBO = "ccb74c6c13b1676e3af368cbe9281bfe649c17c6"
JAVA_OPTION = "java-options=-Dsilage.mpv.runtime.dir=$APPDIR/dv-runtime"


def manifest(prefix):
    sources = prefix.parent / "src"
    patch = Path(__file__).with_name("mpv-dovi-residual-status.patch")
    for project, commit in (("mpv", MPV), ("libplacebo", PLACEBO)):
        actual = subprocess.check_output(["git", "-C", str(sources / project), "rev-parse", "HEAD"], text=True).strip()
        if actual != commit:
            raise ValueError(f"Unexpected {project} revision")
        actual_diff = subprocess.check_output(["git", "-C", str(sources / project), "diff", "--no-ext-diff", "--no-color", "--binary"])
        if actual_diff != (patch.read_bytes() if project == "mpv" else b""):
            raise ValueError(f"Unexpected {project} source modifications")
        subprocess.run(["git", "-C", str(sources / project), "diff", "--cached", "--exit-code", "--quiet"], check=True)
    clean_env = {key: value for key, value in os.environ.items() if key != "LD_PRELOAD"}
    clean_env["LD_LIBRARY_PATH"] = str(prefix / "lib")
    version = subprocess.check_output([str(prefix / "bin/mpv"), "--version"], env=clean_env, text=True)
    hashes = {name: hashlib.sha256((prefix / "lib" / name).read_bytes()).hexdigest()
              for name in ("libmpv.so.2", "libplacebo.so.377")}
    return {"experimental": True, "mpv_commit": MPV, "libplacebo_commit": PLACEBO,
            "mpv_source": f"https://github.com/mpv-player/mpv/tree/{MPV}",
            "libplacebo_source": f"https://github.com/haasn/libplacebo/tree/{PLACEBO}",
            "runtime_version": version, "original_library_sha256": hashes,
            "packaged_rpath": "$ORIGIN", "source_patches": [{"file": patch.name,
                "sha256": hashlib.sha256(patch.read_bytes()).hexdigest()}]}


def package(prefix, image, info):
    app = image / "lib/app"
    config = app / "silage.cfg"
    original = config.read_text()
    if "[JavaOptions]" not in original or not (image / "bin/silage").is_file():
        raise ValueError("Expected a Linux Silage app image")
    destination = app / "dv-runtime"
    destination.mkdir(exist_ok=True)
    for name in ("libmpv.so.2", "libplacebo.so.377"):
        source = prefix / "lib" / name
        if source.read_bytes()[:4] != b"\x7fELF":
            raise ValueError("Expected a Linux ELF library")
        target = destination / name
        # Replace the inode, preserving any already-running process's mapped library.
        temporary = destination / (name + ".new")
        shutil.copyfile(source, temporary)
        temporary.chmod(0o755)
        subprocess.run(["/usr/bin/patchelf", "--set-rpath", "$ORIGIN", str(temporary)], check=True)
        temporary.replace(target)
    sources = prefix.parent / "src"
    licenses = destination / "licenses"
    licenses.mkdir(exist_ok=True)
    for name in ("LICENSE.GPL", "LICENSE.LGPL", "Copyright"):
        shutil.copyfile(sources / "mpv" / name, licenses / ("mpv-" + name))
    shutil.copyfile(sources / "libplacebo/LICENSE", licenses / "libplacebo-LICENSE")
    shutil.copyfile(Path(__file__).with_name("mpv-dovi-residual-status.patch"), destination / "mpv-dovi-residual-status.patch")
    info["packaged_library_sha256"] = {
        name: hashlib.sha256((destination / name).read_bytes()).hexdigest()
        for name in ("libmpv.so.2", "libplacebo.so.377")}
    (destination / "runtime.json").write_text(json.dumps(info, indent=2) + "\n")
    lines = [line for line in original.splitlines() if not line.startswith(("java-options=-Djna.library.path=", "java-options=-Dsilage.mpv.runtime.dir="))]
    index = lines.index("[JavaOptions]") + 1
    lines.insert(index, JAVA_OPTION)
    config.write_text("\n".join(lines) + "\n")
    print(f"Bundled private renderer: {destination}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", type=Path, required=True)
    parser.add_argument("--image", type=Path)
    parser.add_argument("--manifest-only", action="store_true")
    args = parser.parse_args()
    if args.manifest_only == (args.image is not None):
        parser.error("Choose --image or --manifest-only")
    prefix = args.runtime.resolve(strict=True)
    info = manifest(prefix)
    (prefix / "silage-runtime.json").write_text(json.dumps(info, indent=2) + "\n")
    if args.image is not None:
        package(prefix, args.image.resolve(strict=True), info)


if __name__ == "__main__":
    main()
