<p align="center">
  <img src="docs/media/splash.gif" alt="Silage startup animation" width="720">
</p>

<h1 align="center">Silage</h1>

<p align="center">
  A fullscreen, couch-friendly desktop app for your <a href="https://siloserver.org">Silo</a> media server.<br>
  For Linux and Windows. Free and open source.
</p>

<p align="center">
  <a href="https://github.com/evman/silage/releases/latest/download/Silage-Setup.exe"><b>Download for Windows</b></a>
  &nbsp;·&nbsp;
  <a href="#linux"><b>Install on Linux</b></a>
  &nbsp;·&nbsp;
  <a href="CHANGELOG.md">What's new</a>
</p>

<p align="center">
  <img src="docs/media/home.png" alt="Silage home screen" width="900">
</p>

## Why Silage

- **Full quality, no transcoding.** Your files play as they are, through mpv with hardware decoding.
- **Real HDR on Linux.** True HDR output on KDE Plasma, plus [experimental Dolby Vision](DOLBY_VISION.md).
- **Made for the couch.** Everything works with a remote or arrow keys, Enter and Esc.
- **Everything you use in Silo.** Libraries, collections, watchlist, requests, notifications, subtitles, Up next and Skip Intro.
- **Updates itself.** New versions are signature-checked and installed with one click.

## Install

### Windows

[Download **Silage-Setup.exe**](https://github.com/evman/silage/releases/latest/download/Silage-Setup.exe) and run it. Silage installs in seconds without administrator access and adds itself to the Start menu and desktop. Windows 10 and 11 (x64).

### Linux

One command adds the Silage repository for your distro and installs it:

```sh
curl -fsSL https://raw.githubusercontent.com/evman/silage/main/install.sh | sh
```

Works on Ubuntu 24.04+, Debian 13, Fedora 40+ and Arch Linux. Updates then arrive with your normal system updates. Prefer to set the repository up yourself, or use another distro? See [LINUX.md](LINUX.md#install).

Keep **mpv 0.37 or newer** installed from your distro. On first launch, enter your Silo server's address and sign in.

> **Upgrading from 0.4 or earlier?** On Windows, install Silage-Setup.exe and sign in again; the old portable folder can then be deleted. On Linux, run the command above once to switch to the repository.

## Updates

When a new version is ready, **Update ready** appears in the sidebar, never during playback. Select it to install and restart. You can turn automatic updates off under **Settings → About**.

## Controls

| Key | Browsing | Playing |
|---|---|---|
| Arrows | Move around | Skip 10 s, or move between controls |
| Enter | Open | Show controls / press the focused control |
| Space | — | Play / pause |
| Esc / Backspace | Back | Close a menu, then leave the player |
| I | — | Playback info |
| Ctrl + / − / 0 | Interface size | — |

Media keys work too.

## Help

- **Something not working?** [Open an issue](https://github.com/evman/silage/issues) and attach the logs from the folder shown in **Settings → About**.
- **Platform details:** [Linux](LINUX.md) · [Windows](WINDOWS.md) · [Dolby Vision](DOLBY_VISION.md)
- **Building or contributing:** [CONTRIBUTING.md](CONTRIBUTING.md)

## License

Silage is free software under the [GNU AGPL v3.0 or later](LICENSE). Its `shared` module comes from Silo's open-source [Android client](https://github.com/Silo-Server), also AGPL-3.0.

Silage is an unofficial client, not affiliated with or endorsed by the Silo project. *Silo* and the Silo logo are trademarks of Silo Media L.L.C.
