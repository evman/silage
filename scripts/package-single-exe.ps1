param(
    [Parameter(Mandatory=$true)][string]$Archive,
    [Parameter(Mandatory=$true)][string]$Output
)
$ErrorActionPreference = 'Stop'
$compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path $compiler)) { throw 'Windows .NET Framework compiler is missing' }
$source = Join-Path $PSScriptRoot '..\packaging\windows\PortableLauncher.cs'
$icon = (Resolve-Path (Join-Path $PSScriptRoot '..\packaging\windows\silage.ico')).Path
$archivePath = (Resolve-Path -LiteralPath $Archive).Path
$outputPath = [System.IO.Path]::GetFullPath($Output)
& $compiler /nologo /target:winexe /platform:x64 /optimize+ `
    /reference:System.IO.Compression.dll /reference:System.IO.Compression.FileSystem.dll `
    /reference:System.Windows.Forms.dll `
    "/win32icon:$icon" "/resource:$archivePath,Silage.Payload.zip" "/out:$outputPath" $source
if ($LASTEXITCODE -ne 0) { throw 'Compiling the portable launcher failed' }
# Check the actual single-file deliverable with developer Java settings removed.
$env:JAVA_HOME = $null
$env:JAVA_TOOL_OPTIONS = $null
$env:JDK_JAVA_OPTIONS = $null
$process = Start-Process -FilePath $outputPath -ArgumentList '--check' -Wait -PassThru
if ($process.ExitCode -ne 0) { throw "Portable EXE playback check failed: $($process.ExitCode)" }
Write-Output "Single-file portable app: $outputPath"
