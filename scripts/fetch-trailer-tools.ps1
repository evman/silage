param([Parameter(Mandatory = $true)][string]$Out)
# Puts yt-dlp.exe and deno.exe (what YouTube trailers need) into $Out, so the packaged app carries them.
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
        foreach ($line in (Get-Content -LiteralPath $tmp)) {
            if ($line.Trim() -match '^([0-9a-fA-F]{64})(?:\s+\*?(.+))?$' -and (-not $Name -or $Matches[2].Trim() -eq $Name)) { return $Matches[1].ToLowerInvariant() }
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
        $want = Get-Sum "$base/SHA2-256SUMS" 'yt-dlp.exe'
        Invoke-WebRequest "$base/yt-dlp.exe" -OutFile (Join-Path $temp 'yt-dlp.exe') -UseBasicParsing
        Assert-Hash (Join-Path $temp 'yt-dlp.exe') $want

        $zipUrl = 'https://github.com/denoland/deno/releases/latest/download/deno-x86_64-pc-windows-msvc.zip'
        $want = Get-Sum "$zipUrl.sha256sum" $null
        $zip = Join-Path $temp 'deno.zip'
        Invoke-WebRequest $zipUrl -OutFile $zip -UseBasicParsing
        Assert-Hash $zip $want
        Expand-Archive -LiteralPath $zip -DestinationPath (Join-Path $temp 'deno')

        Copy-Item (Join-Path $temp 'yt-dlp.exe') (Join-Path $cache 'yt-dlp.exe') -Force
        Copy-Item (Join-Path $temp 'deno\deno.exe') (Join-Path $cache 'deno.exe') -Force
        Write-Output 'Trailer tools downloaded and verified'
    } finally { Remove-Item -Recurse -Force $temp -ErrorAction SilentlyContinue }
} catch {
    Write-Warning "Could not refresh the trailer tools: $($_.Exception.Message)"
}

foreach ($name in 'yt-dlp.exe', 'deno.exe') {
    $file = Join-Path $cache $name
    if (-not (Test-Path $file)) { throw "$name is not available (no download and nothing cached). Connect to the internet and package again." }
    Copy-Item $file (Join-Path $Out $name) -Force
}
@'
Bundled so YouTube trailers play without a download.

yt-dlp (https://github.com/yt-dlp/yt-dlp) is released into the public domain (The Unlicense).
Deno (https://github.com/denoland/deno) is released under the MIT license.
Both are unmodified official release builds, checked against the checksums published with each release.
'@ | Set-Content -Encoding UTF8 (Join-Path $Out 'README-tools.txt')
