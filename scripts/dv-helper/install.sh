#!/usr/bin/env bash
# Build/stage without root. Install only the reviewed files with administrator authorization.
set -euo pipefail
source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_dir=$(cd -- "$source_dir/../.." && pwd)
stage_dir="$repo_dir/build/dv-helper/stage"
binary_dir="$stage_dir/usr/libexec/silage-dv-helper"
policy_dir="$stage_dir/usr/share/polkit-1/actions"
mkdir -p -- "$binary_dir" "$policy_dir"
read -ra drm_flags <<< "$(pkg-config --cflags --libs libdrm)"
cc -std=c11 -D_GNU_SOURCE -O2 -Wall -Wextra -Werror \
  "$source_dir/../dv-lab/kms_snapshot.c" -o "$binary_dir/kms-snapshot" "${drm_flags[@]}"
install -m 0755 -- "$source_dir/helper.py" "$binary_dir/silage-dv-helper"
install -m 0644 -- "$source_dir/dev.silage.dv-helper.policy" "$policy_dir/dev.silage.dv-helper.policy"
if [[ "${1:-}" == "--stage" && $# == 1 ]]; then
  printf 'Staged helper: %s\n' "$stage_dir"
  exit 0
fi
if [[ $# != 0 ]]; then
  printf 'Usage: %s [--stage]\n' "$0" >&2
  exit 2
fi
sudo /usr/bin/install -d -o root -g root -m 0755 /usr/libexec/silage-dv-helper
sudo /usr/bin/install -o root -g root -m 0755 -- \
  "$binary_dir/silage-dv-helper" "$binary_dir/kms-snapshot" /usr/libexec/silage-dv-helper/
sudo /usr/bin/install -o root -g root -m 0644 -- \
  "$policy_dir/dev.silage.dv-helper.policy" /usr/share/polkit-1/actions/dev.silage.dv-helper.policy
printf 'Installed experimental helper. KWin and desktop configuration were not changed.\n'
