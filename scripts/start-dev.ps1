param([switch]$SkipBuild)
. "$PSScriptRoot\common.ps1"
Import-StudioEnv
Use-StudioJava
if ($env:SPRING_PROFILES_ACTIVE -ne 'dev' -or $env:SERVER_ADDRESS -ne '127.0.0.1') { throw 'This launcher requires dev mode and SERVER_ADDRESS=127.0.0.1. Use deployment instructions for production.' }
$dbUri = [Uri]($env:STUDIO_DB_URL -replace '^jdbc:','')
$dbPort = if ($dbUri.Port -gt 0) { $dbUri.Port } else { 3306 }
if (!(Test-StudioPort -Port $dbPort -Address $dbUri.Host)) { throw 'Start your MySQL server and create/configure the dedicated database first. This launcher does not start or modify your MySQL service.' }
$apiPort = if ($env:SERVER_PORT) { [int]$env:SERVER_PORT } else { 18080 }
foreach ($port in @($apiPort,5173)) {
    if (Test-StudioPort $port) { throw "Port $port is already occupied. Stop this copy first or use an unused environment; no existing process was changed." }
}
if (!$SkipBuild) {
    & mvn.cmd -f (Join-Path $StudioRoot 'backend\pom.xml') clean package
    if ($LASTEXITCODE -ne 0) { throw 'Backend build or tests failed.' }
    Push-Location (Join-Path $StudioRoot 'frontend')
    try {
        & npm.cmd ci
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
    } finally { Pop-Location }
}
$jar = Join-Path $StudioRoot 'backend\target\studio-1.0.0.jar'
if (!(Test-Path -LiteralPath $jar)) { throw 'No backend JAR found; run without -SkipBuild.' }
$vitePath = Join-Path $StudioRoot 'frontend\node_modules\vite\bin\vite.js'
if (!(Test-Path -LiteralPath $vitePath)) { throw 'Frontend dependencies missing; run without -SkipBuild.' }
$env:STUDIO_BASE_PATH='/'
$env:STUDIO_API_PROXY="http://127.0.0.1:$apiPort"
try {
    $proc = Start-Process -FilePath (Join-Path $env:JAVA_HOME 'bin\java.exe') -ArgumentList @('-Xms128m','-Xmx512m','-Dfile.encoding=UTF-8','-Duser.timezone=Asia/Shanghai','-jar',('"' + $jar + '"')) -WorkingDirectory (Join-Path $StudioRoot 'backend') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $StudioWork 'backend-out.log') -RedirectStandardError (Join-Path $StudioWork 'backend-err.log')
    $proc.Id | Set-Content (Join-Path $StudioWork 'backend.pid')
    Wait-StudioPort $apiPort 60
    $nodePath = (Get-Command node.exe -ErrorAction Stop).Source
    $proc = Start-Process -FilePath $nodePath -ArgumentList @(('"'+$vitePath+'"'),'--host','127.0.0.1','--port','5173','--strictPort') -WorkingDirectory (Join-Path $StudioRoot 'frontend') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $StudioWork 'frontend-out.log') -RedirectStandardError (Join-Path $StudioWork 'frontend-err.log')
    $proc.Id | Set-Content (Join-Path $StudioWork 'frontend.pid')
    Wait-StudioPort 5173
} catch {
    & "$PSScriptRoot\stop-dev.ps1"
    throw
}
Write-Output 'Ready: http://127.0.0.1:5173 (logs in work/)'
