param([Parameter(Mandatory = $true)][string]$Out)
# Puts yt-dlp and deno.exe (what YouTube trailers need) into $Out, so the packaged app carries them.
# yt-dlp is its folder build (yt-dlp\yt-dlp.exe with its _internal folder): unlike the single-file
# yt-dlp.exe it doesn't unpack itself on every run, so trailers start sooner.
# Each download is checked against the SHA-256 its official GitHub release publishes. The verified
# copies are cached in native\tools; when GitHub can't be reached the cached ones are used.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$root = Split-Path $PSScriptRoot -Parent
$cache = Join-Path $root 'native\tools'
New-Item -ItemType Directory -Force $cache, $Out | Out-Null

function Get-Sum([string]$Url, [string]$Name) {
    # Saved to a file and read as text: Windows PowerShell returns octet-stream downloads as bytes.
    $tmp = [System.IO.Path]::GetTempFileName()
    try {
        Invoke-WebRequest $Url -OutFile $tmp -UseBasicParsing
        # Any line holding a SHA-256 (64 hex digits) and, when a name is given, that file's name.
        foreach ($line in (Get-Content -LiteralPath $tmp)) {
            if ($line -match '(?<![0-9a-fA-F])([0-9a-fA-F]{64})(?![0-9a-fA-F])' -and (-not $Name -or $line.Contains($Name))) { return $Matches[1].ToLowerInvariant() }
        }
    } finally { Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue }
    throw "No checksum for '$Name' found at $Url"
}
function Assert-Hash([string]$Path, [string]$Want) {
    if ((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Want) { throw "SHA256 mismatch: $Path" }
}

try {
    $temp = Join-Path ([System.IO.Path]::GetTempPath()) ('silage-tools-' + [Guid]::NewGuid())
    New-Item -ItemType Directory $temp | Out-Null
    try {
        $base = 'https://github.com/yt-dlp/yt-dlp/releases/latest/download'
        $want = Get-Sum "$base/SHA2-256SUMS" 'yt-dlp_win.zip'
        $ytZip = Join-Path $temp 'yt-dlp_win.zip'
        Invoke-WebRequest "$base/yt-dlp_win.zip" -OutFile $ytZip -UseBasicParsing
        Assert-Hash $ytZip $want
        Expand-Archive -LiteralPath $ytZip -DestinationPath (Join-Path $temp 'yt-dlp')
        if (-not (Test-Path (Join-Path $temp 'yt-dlp\yt-dlp.exe'))) { throw 'yt-dlp.exe is not in yt-dlp_win.zip' }

        $zipUrl = 'https://github.com/denoland/deno/releases/latest/download/deno-x86_64-pc-windows-msvc.zip'
        $want = Get-Sum "$zipUrl.sha256sum" $null
        $zip = Join-Path $temp 'deno.zip'
        Invoke-WebRequest $zipUrl -OutFile $zip -UseBasicParsing
        Assert-Hash $zip $want
        Expand-Archive -LiteralPath $zip -DestinationPath (Join-Path $temp 'deno')

        $cachedYt = Join-Path $cache 'yt-dlp'
        if (Test-Path $cachedYt) { Remove-Item -Recurse -Force $cachedYt }
        Copy-Item (Join-Path $temp 'yt-dlp') $cachedYt -Recurse -Force
        Copy-Item (Join-Path $temp 'deno\deno.exe') (Join-Path $cache 'deno.exe') -Force
        Write-Output 'Trailer tools downloaded and verified'
    } finally { Remove-Item -Recurse -Force $temp -ErrorAction SilentlyContinue }
} catch {
    Write-Warning "Could not refresh the trailer tools: $($_.Exception.Message)"
}

foreach ($name in 'yt-dlp\yt-dlp.exe', 'deno.exe') {
    if (-not (Test-Path (Join-Path $cache $name))) { throw "$name is not available (no download and nothing cached). Connect to the internet and package again." }
}
$outYt = Join-Path $Out 'yt-dlp'
if (Test-Path $outYt) { Remove-Item -Recurse -Force $outYt }
Copy-Item (Join-Path $cache 'yt-dlp') $outYt -Recurse -Force
Copy-Item (Join-Path $cache 'deno.exe') (Join-Path $Out 'deno.exe') -Force
# An older packaging left the single-file yt-dlp here; the folder build replaces it.
Remove-Item -LiteralPath (Join-Path $Out 'yt-dlp.exe') -Force -ErrorAction SilentlyContinue
@'
Bundled so YouTube trailers play without a download.

yt-dlp (https://github.com/yt-dlp/yt-dlp) is released into the public domain (The Unlicense).
Deno (https://github.com/denoland/deno) is released under the MIT license.
Both are unmodified official release builds, checked against the checksums published with each release.
'@ | Set-Content -Encoding UTF8 (Join-Path $Out 'README-tools.txt')
