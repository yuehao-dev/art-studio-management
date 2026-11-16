$ErrorActionPreference = 'Stop'
$StudioRoot = Split-Path -Parent $PSScriptRoot
$StudioWork = Join-Path $StudioRoot 'work'
New-Item -ItemType Directory -Path $StudioWork -Force | Out-Null
function Import-StudioEnv {
    $envFile = Join-Path $StudioRoot '.env.local'
    if (!(Test-Path -LiteralPath $envFile)) { throw 'Copy .env.example to .env.local and configure your local database first.' }
    foreach ($line in [IO.File]::ReadAllLines($envFile)) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
            [Environment]::SetEnvironmentVariable($matches[1], $matches[2].Trim(), 'Process')
        }
    }
}
function Use-StudioJava {
    if (!$env:JAVA_HOME) {
        $compiler = Get-Command javac.exe -ErrorAction Stop
        $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $compiler.Source)
    }
    if (!(Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) { throw 'JAVA_HOME must point to a JDK 17+ installation.' }
    $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
    $version = & (Join-Path $env:JAVA_HOME 'bin\javac.exe') -version 2>&1
    if ([string]$version -notmatch 'javac (\d+)' -or [int]$matches[1] -lt 17) { throw 'A JDK 17+ installation is required.' }
}
function Test-StudioPort([int]$Port, [string]$Address='127.0.0.1') {
    $client = New-Object Net.Sockets.TcpClient
    try {
        $pending = $client.BeginConnect($Address,$Port,$null,$null)
        if (!$pending.AsyncWaitHandle.WaitOne(3000)) { return $false }
        $client.EndConnect($pending)
        return $true
    } catch { return $false } finally { $client.Dispose() }
}
function Wait-StudioPort([int]$Port,[int]$Seconds=45) {
    $end = (Get-Date).AddSeconds($Seconds)
    do { if (Test-StudioPort $Port) { return }; Start-Sleep -Milliseconds 500 } while ((Get-Date) -lt $end)
    throw "Port $Port did not become ready; inspect logs in work/."
}
