param(
    [string]$TargetDir = "$PSScriptRoot\..\src-tauri\lib",
    [string]$LocalDllPath = ""
)

$ErrorActionPreference = "Stop"

New-Item -ItemType Directory -Force -Path $TargetDir | Out-Null
$DestDll = Join-Path $TargetDir "libmpv-2.dll"

if ($LocalDllPath -ne "" -and (Test-Path $LocalDllPath)) {
    Copy-Item -Path $LocalDllPath -Destination $DestDll -Force
    Write-Host "Copied local libmpv-2.dll to $DestDll" -ForegroundColor Green
    exit 0
}

Write-Host "Querying latest mpv-dev-x86_64 release from shinchiro/mpv-winbuild-cmake..." -ForegroundColor Cyan
$Headers = @{ "User-Agent" = "AtrisStream-Setup" }
if (-not [string]::IsNullOrWhiteSpace($env:GITHUB_TOKEN)) {
    $Headers["Authorization"] = "Bearer $env:GITHUB_TOKEN"
}
$Release = Invoke-RestMethod -Uri "https://api.github.com/repos/shinchiro/mpv-winbuild-cmake/releases/latest" -Headers $Headers
$Asset = $Release.assets | Where-Object { $_.name -match "^mpv-dev-x86_64-.*\.7z$" -and $_.name -notmatch "v3" } | Select-Object -First 1

if (-not $Asset) {
    $Asset = $Release.assets | Where-Object { $_.name -match "^mpv-dev-x86_64-.*\.7z$" } | Select-Object -First 1
}

if (-not $Asset) {
    Write-Error "Could not find mpv-dev-x86_64 asset in latest release. Please download libmpv-2.dll manually and place it at $DestDll"
    exit 1
}

$TempDir = Join-Path $env:TEMP ("atris-libmpv-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $TempDir | Out-Null
$ArchivePath = Join-Path $TempDir $Asset.name

Write-Host "Downloading $($Asset.name)..." -ForegroundColor Cyan
Invoke-WebRequest -Uri $Asset.browser_download_url -OutFile $ArchivePath

$SevenZip = Get-Command "7z" -ErrorAction SilentlyContinue
if (-not $SevenZip -and (Test-Path "C:\Program Files\7-Zip\7z.exe")) {
    $SevenZip = "C:\Program Files\7-Zip\7z.exe"
}

if (-not $SevenZip) {
    Write-Warning "7z.exe not found. Archive downloaded to $ArchivePath."
    Write-Warning "Please extract libmpv-2.dll from the archive and copy it to: $DestDll"
    exit 0
}

& $SevenZip x $ArchivePath "-o$TempDir\extracted" -y | Out-Null
$FoundDll = Get-ChildItem -Path "$TempDir\extracted" -Filter "libmpv-2.dll" -Recurse | Select-Object -First 1

if (-not $FoundDll) {
    Write-Error "libmpv-2.dll was not found inside $($Asset.name)."
    exit 1
}

Copy-Item -Path $FoundDll.FullName -Destination $DestDll -Force
Remove-Item -Path $TempDir -Recurse -Force -ErrorAction SilentlyContinue

Write-Host "Successfully installed libmpv-2.dll to $DestDll" -ForegroundColor Green
