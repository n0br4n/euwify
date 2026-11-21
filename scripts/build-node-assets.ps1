# Rebuilds the Node.js part of the app after changing nodejs/main.js or
# nodejs/package.json. It installs the npm packages, packs them into one zip
# (Android skips asset folders starting with "_", and thousands of loose files
# would make every start slow), and copies everything into the app's assets.
#   powershell -ExecutionPolicy Bypass -File scripts\build-node-assets.ps1

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$src = Join-Path $root 'nodejs'
$assets = Join-Path $root 'app\src\main\assets\nodejs-project'

Push-Location $src
try {
    npm ci --omit=dev --no-fund --no-audit
    if ($LASTEXITCODE -ne 0) { throw 'npm ci failed' }
    node --check main.js
    if ($LASTEXITCODE -ne 0) { throw 'main.js has a syntax error' }
} finally { Pop-Location }

New-Item -ItemType Directory -Force $assets | Out-Null
$zipPath = Join-Path $assets 'node_modules.zip'
if (Test-Path $zipPath) { Remove-Item $zipPath }

Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($zipPath, 'Create')
$n = 0
try {
    Get-ChildItem (Join-Path $src 'node_modules') -Recurse -File |
        Where-Object { $_.Name -notmatch '\.(map|md|markdown)$' -and $_.Name -notlike '*.d.ts' } |
        ForEach-Object {
            # Entry names must use forward slashes.
            $rel = $_.FullName.Substring($src.Length + 1).Replace('\', '/')
            [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $_.FullName, $rel, 'Optimal')
            $n++
        }
} finally { $zip.Dispose() }

Copy-Item (Join-Path $src 'main.js') (Join-Path $assets 'main.js') -Force
Write-Host ("Packed {0} files, {1:N1} MB -> {2}" -f $n, ((Get-Item $zipPath).Length / 1MB), $assets)
