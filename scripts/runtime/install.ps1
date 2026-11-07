# Windows PowerShell 5.1+ and PowerShell 7. No administrator or package manager required.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Download([string]$Url, [string]$Destination) {
    Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $Destination -TimeoutSec 300
}

function Assert-Checksum([string]$Archive, [string]$Expected) {
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $Archive).Hash -ne $Expected) {
        throw "Checksum mismatch: $Archive"
    }
}

function Invoke-Runtime([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Executable exited with code $LASTEXITCODE." }
}

function Expand-Runtime([string]$Archive, [string]$Destination) {
    $tar = Join-Path $env:SystemRoot 'System32\tar.exe'
    if (Test-Path -LiteralPath $tar) {
        New-Item -ItemType Directory -Force -Path $Destination | Out-Null
        Invoke-Runtime $tar @('-xf', $Archive, '-C', $Destination)
    } else {
        Expand-Archive -LiteralPath $Archive -DestinationPath $Destination
    }
}

function Publish-Runtime([string]$Source, [string]$Destination) {
    if (Test-Path -LiteralPath $Destination) { Remove-Item -LiteralPath $Destination -Recurse -Force }
    Move-Item -LiteralPath $Source -Destination $Destination
}

$work = $null
try {
    $components = if ($args.Count) { $args } else { @('node', 'python', 'uv') }
    foreach ($component in $components) {
        if ($component -notin @('node', 'python', 'uv')) { throw "Unknown runtime: $component" }
    }
    $architecture = if ($env:PROCESSOR_ARCHITEW6432) { $env:PROCESSOR_ARCHITEW6432 } else { $env:PROCESSOR_ARCHITECTURE }
    switch ($architecture) {
        'AMD64' { $nodeArch = 'x64'; $uvArch = 'x86_64' }
        'ARM64' { $nodeArch = 'arm64'; $uvArch = 'aarch64' }
        default { throw 'Mederi runtime setup supports x64 and ARM64.' }
    }

    $root = if ($env:MEDERI_RUNTIME_HOME) { $env:MEDERI_RUNTIME_HOME } else { Join-Path $env:USERPROFILE '.mederi\runtime' }
    $work = Join-Path $root ('.setup.' + [guid]::NewGuid())
    New-Item -ItemType Directory -Force -Path $work | Out-Null
    $uvDir = Join-Path $root 'uv'

    if ('node' -in $components) {
        Write-Output 'Installing Node.js, npm and npx...'
        $nodeUrl = 'https://nodejs.org/dist/latest-v24.x'
        $checksums = Join-Path $work 'node-checksums'
        Download "$nodeUrl/SHASUMS256.txt" $checksums
        $entry = Get-Content -LiteralPath $checksums | Where-Object { $_ -match "^[a-f0-9]{64}\s+node-v24\.\d+\.\d+-win-$nodeArch\.zip$" } | Select-Object -First 1
        if (-not $entry) { throw 'No compatible Node.js archive found.' }
        $checksum, $archive = $entry -split '\s+'
        $zip = Join-Path $work $archive
        $nodeVersion = ($archive -split '-')[1]
        Download "https://nodejs.org/dist/$nodeVersion/$archive" $zip
        Assert-Checksum $zip $checksum
        Expand-Runtime $zip $work
        $nodeDir = Join-Path $work ([IO.Path]::GetFileNameWithoutExtension($archive))
        Invoke-Runtime (Join-Path $nodeDir 'node.exe') @('--version')
        foreach ($cli in @('npm', 'npx')) {
            Invoke-Runtime (Join-Path $nodeDir 'node.exe') @((Join-Path $nodeDir "node_modules\npm\bin\$cli-cli.js"), '--version')
        }
        Publish-Runtime $nodeDir (Join-Path $root 'node')
    }

    $uv = Join-Path $uvDir 'uv.exe'
    $installUv = 'uv' -in $components
    if ('python' -in $components -and -not $installUv -and -not (Test-Path -LiteralPath $uv)) {
        $systemUv = Get-Command uv.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($systemUv) { $uv = $systemUv.Source } else { $installUv = $true }
    }
    if ($installUv) {
        Write-Output 'Installing uv and uvx...'
        $archive = "uv-$uvArch-pc-windows-msvc.zip"
        $url = "https://github.com/astral-sh/uv/releases/download/0.12.17/$archive"
        $zip = Join-Path $work $archive
        $checksumFile = "$zip.sha256"
        Download $url $zip
        Download "$url.sha256" $checksumFile
        Assert-Checksum $zip ((Get-Content -Raw -LiteralPath $checksumFile).Trim() -split '\s+')[0]
        $extracted = Join-Path $work 'uv-extracted'
        Expand-Runtime $zip $extracted
        $uvExe = Get-ChildItem -LiteralPath $extracted -Filter 'uv.exe' -File -Recurse | Select-Object -First 1
        if (-not $uvExe) { throw 'uv.exe was missing from the archive.' }
        Invoke-Runtime $uvExe.FullName @('--version')
        Invoke-Runtime (Join-Path $uvExe.DirectoryName 'uvx.exe') @('--version')
        Publish-Runtime $uvExe.DirectoryName $uvDir
    }

    if ('python' -in $components) {
        Write-Output 'Installing Python...'
        $env:UV_PYTHON_INSTALL_DIR = Join-Path $root 'python'
        $env:UV_PYTHON_BIN_DIR = Join-Path $root 'python-bin'
        $env:UV_PYTHON_NO_REGISTRY = '1'
        $env:UV_PYTHON_INSTALL_REGISTRY = '0'
        Invoke-Runtime $uv @('--no-config', 'python', 'install', '3.13', '--managed-python', '--default')
        Invoke-Runtime (Join-Path $env:UV_PYTHON_BIN_DIR 'python.exe') @('--version')
    }
    Write-Output 'Mederi runtime setup complete.'
} catch {
    Write-Error -ErrorAction Continue $_
    exit 1
} finally {
    if ($work -and (Test-Path -LiteralPath $work)) {
        Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
    }
}
