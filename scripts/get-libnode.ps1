# Downloads the nodejs-mobile library (libnode.so + headers) into app/libnode.
# It is ~80 MB, so it is not stored in git. Run once after cloning:
#   powershell -ExecutionPolicy Bypass -File scripts\get-libnode.ps1

$ErrorActionPreference = 'Stop'
$version = '24.20.0-0'
$url = "https://github.com/digidem/nodejs-mobile/releases/download/v$version/nodejs-mobile-android-$version.zip"

$root = Split-Path -Parent $PSScriptRoot
$target = Join-Path $root 'app\libnode'
$tmp = Join-Path ([IO.Path]::GetTempPath()) "libnode-$version"
$zip = "$tmp.zip"

if (Test-Path (Join-Path $target 'bin\arm64-v8a\libnode.so')) {
    Write-Host "libnode already present in $target"
    exit 0
}

Write-Host "Downloading nodejs-mobile $version (~75 MB)..."
Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing

Write-Host 'Unpacking...'
if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
Expand-Archive -Path $zip -DestinationPath $tmp

$so = Get-ChildItem $tmp -Recurse -Filter libnode.so | Where-Object { $_.Directory.Name -eq 'arm64-v8a' } | Select-Object -First 1
$nodeH = Get-ChildItem $tmp -Recurse -Filter node.h | Select-Object -First 1
if (-not $so -or -not $nodeH) { throw 'libnode.so or node.h not found in the download' }

New-Item -ItemType Directory -Force (Join-Path $target 'bin\arm64-v8a') | Out-Null
New-Item -ItemType Directory -Force (Join-Path $target 'include') | Out-Null
Copy-Item $so.FullName (Join-Path $target 'bin\arm64-v8a\libnode.so')
Copy-Item $nodeH.Directory.FullName (Join-Path $target 'include\node') -Recurse -Force

Remove-Item $zip, $tmp -Recurse -Force
Write-Host "Done: $target"
