#!/usr/bin/env bash
# Silage restarts itself after an update by starting the new launcher from the
# running Java. The update has already replaced the bundled Java, so the running
# JVM must not depend on its own lib/jspawnhelper: a newer helper refuses an older
# JVM ("Failed to exec spawn helper", seen updating 0.5.0 -> 0.5.1).
#
# This builds a throwaway copy of the packaged app image whose spawn helper is
# replaced by one that refuses, keeps the app's real Java options, and checks
# that the app's JVM can still start a program.
#
# Usage: scripts/tests/test-restart-spawn.sh <app image dir, containing bin/silage>
set -euo pipefail
image=${1:?usage: $0 <app image dir>}
[ -x "$image/bin/silage" ] && [ -f "$image/lib/app/silage.cfg" ] || { echo "not an app image: $image" >&2; exit 2; }
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT

mkdir -p "$work/img/bin" "$work/img/lib/app" "$work/src"
cp "$image/bin/silage" "$work/img/bin/"
cp "$image/lib/libapplauncher.so" "$work/img/lib/"
cp -a "$image/lib/runtime" "$work/img/lib/runtime"
printf '#!/bin/sh\necho "Incorrect Java version (simulated update)" >&2\nexit 1\n' > "$work/img/lib/runtime/lib/jspawnhelper"
chmod +x "$work/img/lib/runtime/lib/jspawnhelper"

cat > "$work/src/Spawn.java" <<'JAVA'
public class Spawn {
    public static void main(String[] args) throws Exception {
        try {
            int code = new ProcessBuilder("true").start().waitFor();
            System.out.println(code == 0 ? "SPAWN_OK" : "SPAWN_EXIT " + code);
        } catch (java.io.IOException e) {
            System.out.println("SPAWN_FAILED " + e.getMessage().lines().findFirst().orElse(""));
        }
    }
}
JAVA
javac --release 21 -d "$work/img/lib/app" "$work/src/Spawn.java"
{
    printf '[Application]\napp.classpath=$APPDIR\napp.mainclass=Spawn\n\n[JavaOptions]\n'
    grep '^java-options=' "$image/lib/app/silage.cfg" | grep -v -e 'silage.mpv.runtime.dir' -e 'jna.library.path' || true
} > "$work/img/lib/app/silage.cfg"

result=$("$work/img/bin/silage" 2>/dev/null | tail -1)
if [ "$result" = SPAWN_OK ]; then
    echo "ok: the app's JVM starts programs after its Java is replaced"
else
    echo "FAIL: $result" >&2
    echo "The app's Java options must include -Djdk.lang.Process.launchMechanism=FORK" >&2
    exit 1
fi
