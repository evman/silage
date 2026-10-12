## Install

**Arch Linux** (AUR):
```sh
yay -S silage-bin
```

**Ubuntu 24.04+ / Debian 13**:
```sh
sudo apt install ./silage_@VERSION@_amd64.deb
```

**Fedora 40+**:
```sh
sudo dnf install ./silage-@VERSION@-1.x86_64.rpm
```
Fedora's stock FFmpeg leaves out H.264/HEVC; for full codec support, install FFmpeg from [RPM Fusion](https://rpmfusion.org/Howto/Multimedia).

**Any distro** (needs mpv 0.37+ with libmpv):
```sh
tar xzf silage-@VERSION@-linux-x86_64.tar.gz
./silage-@VERSION@/install.sh
```

Run `silage --check` to confirm Silage can find mpv.
