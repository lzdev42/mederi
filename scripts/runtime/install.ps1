# Installs shared Windows runtimes with WinGet. With no arguments, installs all components.
$ErrorActionPreference = 'Stop'

function Show-InstallerOutput([IO.StreamReader]$Reader) {
    $output = $Reader.ReadToEnd()
    if ($output.Length) { [Console]::Write($output) }
}

$packages = @{
    node   = @{ Id = 'OpenJS.NodeJS'; Type = 'wix'; Commands = @('node.exe', 'npm.cmd', 'npx.cmd') }
    python = @{ Id = 'Python.Python.3.14'; Type = 'burn'; Commands = @('python.exe') }
    git    = @{ Id = 'Git.Git'; Custom = '/o:PathOption=Cmd'; Commands = @('git.exe') }
    uv     = @{ Id = 'astral-sh.uv'; Commands = @('uv.exe', 'uvx.exe') }
}
$components = if ($args.Count) { $args } else { @('node', 'python', 'git', 'uv') }
foreach ($component in $components) {
    if (-not $packages.ContainsKey($component)) { throw "Unknown runtime: $component" }
}

$winget = (Get-Command winget.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1).Source
if (-not $winget -and $env:LOCALAPPDATA) {
    $alias = Join-Path $env:LOCALAPPDATA 'Microsoft\WindowsApps\winget.exe'
    if (Test-Path -LiteralPath $alias -PathType Leaf) { $winget = $alias }
}
if (-not $winget) {
    throw 'WinGet is unavailable. Install or update Microsoft App Installer, then try again.'
}

$principal = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    # Use one UAC prompt for all requested machine-wide installers.
    $launcher = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $log = [IO.Path]::GetTempFileName()
    try {
        $scriptPath = $PSCommandPath.Replace("'", "''")
        $logPath = $log.Replace("'", "''")
        $componentArgs = ($components | ForEach-Object { "'$_'" }) -join ' '
        # A separately elevated process cannot inherit the installer's PTY handles.
        # Stream its output through a temporary file and retain its real exit code.
        $command = "& '$scriptPath' $componentArgs *> '$logPath'; if (-not `$?) { exit 1 }"
        $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
        Write-Output '[Mederi] Windows approval'
        $installer = Start-Process -FilePath $launcher -ArgumentList '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', $encoded -Verb RunAs -PassThru
        $reader = [IO.StreamReader]::new([IO.File]::Open($log, 'Open', 'Read', 'ReadWrite'), [Text.Encoding]::Unicode)
        try {
            while (-not $installer.WaitForExit(250)) { Show-InstallerOutput $reader }
            Show-InstallerOutput $reader
            if ($installer.ExitCode -ne 0) {
                throw "System runtime setup exited with code $($installer.ExitCode)."
            }
        } finally {
            $reader.Dispose()
            $installer.Dispose()
        }
    } finally {
        Remove-Item -LiteralPath $log -Force -ErrorAction SilentlyContinue
    }
} else {
    Write-Output '[Mederi] Installing'
    foreach ($component in $components) {
        $package = $packages[$component]
        Write-Output "Installing $($package.Id) for all users..."
        # The Python machine manifest sets InstallAllUsers=1 and PrependPath=1.
        $installArgs = @('install', '--id', $package.Id, '--exact', '--source', 'winget', '--scope', 'machine',
            '--silent', '--disable-interactivity', '--accept-package-agreements', '--accept-source-agreements')
        if ($package.Type) { $installArgs += @('--installer-type', $package.Type) }
        if ($package.Custom) { $installArgs += @('--custom', $package.Custom) }
        & $winget @installArgs
        $code = $LASTEXITCODE
        # WinGet can return UPDATE_NOT_APPLICABLE for an existing installation.
        # Verify the machine PATH below before reporting success in either case.
        if ($code -ne 0 -and $code -ne 0x8A15002B) {
            Write-Error "WinGet failed to install $($package.Id) (0x$($code.ToString('X8')))." -ErrorAction Continue
            exit $code
        }
    }

    $previousPath = $env:Path
    try {
        $env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine')
        foreach ($component in $components) {
            foreach ($command in $packages[$component].Commands) {
                if (-not (Get-Command $command -CommandType Application -ErrorAction SilentlyContinue)) {
                    throw "$command is missing from the machine PATH. Repair the system installation and retry."
                }
            }
        }
    } finally {
        $env:Path = $previousPath
    }
}

Write-Output 'Mederi runtime setup complete. Reopen terminals and apps to load the updated PATH.'
