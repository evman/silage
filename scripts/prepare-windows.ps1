param([string]$JdkHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$root = Split-Path $PSScriptRoot -Parent

# Reuse an installed JDK without copying its hundreds of megabytes into git.
if ($JdkHome) {
    if (-not (Test-Path (Join-Path $JdkHome 'bin\jpackage.exe'))) { throw 'A full JDK 21 is required' }
    New-Item -ItemType Directory -Force (Join-Path $root 'toolchains') | Out-Null
    $link = Join-Path $root 'toolchains\jdk-ci'
    if (-not (Test-Path $link)) { New-Item -ItemType Junction -Path $link -Target $JdkHome | Out-Null }
}
if (-not (Get-ChildItem (Join-Path $root 'toolchains\jdk-*') -Directory -ErrorAction SilentlyContinue)) {
    throw 'Set JAVA_HOME to JDK 21 or unpack it under toolchains\jdk-*'
}

# Inno Setup 6 compiles the installer (packaging\windows\silage.iss).
$iscc = (Get-Command ISCC.exe -ErrorAction SilentlyContinue).Source
if (-not $iscc) {
    $iscc = @(${env:ProgramFiles(x86)}, $env:ProgramFiles, (Join-Path $env:LOCALAPPDATA 'Programs')) |
        Where-Object { $_ } | ForEach-Object { Join-Path $_ 'Inno Setup 6\ISCC.exe' } |
        Where-Object { Test-Path $_ } | Select-Object -First 1
}
if ($iscc) {
    Write-Output "Inno Setup found: $iscc"
} else {
    if (-not (Get-Command choco -ErrorAction SilentlyContinue)) { throw 'Install Inno Setup 6 (https://jrsoftware.org/isinfo.php)' }
    choco install innosetup --version 6.7.1 -y --no-progress
    if ($LASTEXITCODE -ne 0) { throw 'Installing Inno Setup failed' }
}

$native = Join-Path $root 'native'
New-Item -ItemType Directory -Force $native | Out-Null
$expected = @{
    'libmpv-2.dll' = '0fc3d4a64453fc65b801a687cede8196899788a81a4cd082a9ea477fc418b10f'
    'LICENSE-mpv-GPL.txt' = 'edaef632cbb643e4e7a221717a6c441a4c1a7c918e6e4d56debc3d8739b233f6'
    'Copyright-mpv.txt' = 'bfe9ee4cceabcb8ecbfadf208d04156f73d801e6a57369a5606bb8341e204a23'
    'fallback\vulkan-1.dll' = '98bc6e33b384f83e8cc36174ce1ba81357611d0cbe03462e1ca58d0663034039'
    'fallback\VulkanRT-License.txt' = '404deb98dabf3e54bf9070d59676b31d69a3da2f9f6e56bdf09ad315c04c718c'
}
function Assert-Hash([string]$Path, [string]$ExpectedHash) {
    if ((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $ExpectedHash) {
        throw "SHA256 mismatch: $Path"
    }
}
$ready = $true
foreach ($name in $expected.Keys) {
    $file = Join-Path $native $name
    if (-not (Test-Path $file) -or (Get-FileHash $file -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected[$name]) { $ready = $false }
}
if ($ready) { Write-Output 'Windows playback dependencies already verified'; return }

$sevenZip = (Get-Command 7z.exe -ErrorAction SilentlyContinue).Source
if (-not $sevenZip) { $sevenZip = Join-Path $env:ProgramFiles '7-Zip\7z.exe' }
if (-not (Test-Path $sevenZip)) { throw 'Install 7-Zip to extract the mpv development archive' }
$temp = Join-Path ([System.IO.Path]::GetTempPath()) ('silage-mpv-' + [Guid]::NewGuid())
New-Item -ItemType Directory $temp | Out-Null
try {
    $archive = Join-Path $temp 'mpv.7z'
    Invoke-WebRequest 'https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/20261016/mpv-dev-x86_64-20261016-git-a2cd83c56d.7z' -OutFile $archive
    Assert-Hash $archive '0142058a8d20930f1c086d0c16a593f9e27a8b3fe9d1f4bb3b2ee4a2d5ec994b'
    & $sevenZip e $archive "-o$temp" -y 'libmpv-2.dll' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Extracting libmpv failed' }
    Invoke-WebRequest 'https://raw.githubusercontent.com/mpv-player/mpv/a2cd83c56d/LICENSE.GPL' -OutFile (Join-Path $temp 'LICENSE-mpv-GPL.txt')
    Invoke-WebRequest 'https://raw.githubusercontent.com/mpv-player/mpv/a2cd83c56d/Copyright' -OutFile (Join-Path $temp 'Copyright-mpv.txt')
    # The mpv DLL imports Vulkan even for D3D11 playback. Keep a fallback loader
    # so machines without a Vulkan driver/runtime still run the app.
    $vulkan = Join-Path $temp 'vulkan.zip'
    Invoke-WebRequest 'https://sdk.lunarg.com/sdk/download/1.4.341.0/windows/VulkanRT-X64-1.4.341.0-Components.zip' -OutFile $vulkan
    Assert-Hash $vulkan '20571dcc8292b80a47d68bbbfa4094d87044041f50f4e2e1f326a01e3732b226'
    Expand-Archive -LiteralPath $vulkan -DestinationPath (Join-Path $temp 'vulkan')
    $components = Join-Path $temp 'vulkan\VulkanRT-X64-1.4.341.0-Components'
    New-Item -ItemType Directory (Join-Path $temp 'fallback') | Out-Null
    Copy-Item (Join-Path $components 'x64\vulkan-1.dll') (Join-Path $temp 'fallback\vulkan-1.dll')
    Copy-Item (Join-Path $components 'VulkanRT-License.txt') (Join-Path $temp 'fallback\VulkanRT-License.txt')
    foreach ($name in $expected.Keys) { Assert-Hash (Join-Path $temp $name) $expected[$name] }
    foreach ($name in $expected.Keys) {
        $destination = Join-Path $native $name
        New-Item -ItemType Directory -Force (Split-Path $destination -Parent) | Out-Null
        Copy-Item (Join-Path $temp $name) $destination -Force
    }
} finally { Remove-Item $temp -Recurse -Force }
Write-Output 'Windows playback dependencies downloaded and verified'
