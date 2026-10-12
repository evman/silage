## Install the latest

**Any distro:**
```sh
curl -fsSL https://raw.githubusercontent.com/evman/silage/main/install.sh | sh
```

**Arch:** `paru -S silage-bin` or `yay -S silage-bin`

**Ubuntu 24.04+ / Debian 13:**
```sh
curl -fLO https://github.com/evman/silage/releases/latest/download/silage_amd64.deb && sudo apt install ./silage_amd64.deb
```

**Fedora 40+:**
```sh
sudo dnf install https://github.com/evman/silage/releases/latest/download/silage.x86_64.rpm
```
For full H.264/HEVC support on Fedora, install FFmpeg from [RPM Fusion](https://rpmfusion.org/Howto/Multimedia).

Needs mpv 0.37+ (libmpv). Run `silage --check` to confirm Silage can find it.

The versioned files below (`@VERSION@`) are the same builds.
