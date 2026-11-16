. "$PSScriptRoot\common.ps1"
foreach ($component in @('frontend','backend')) {
    $pidFile = Join-Path $StudioWork "$component.pid"
    if (Test-Path -LiteralPath $pidFile) {
        $studioPid = [int](Get-Content -LiteralPath $pidFile)
        $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$studioPid" -ErrorAction SilentlyContinue
        $valid = $proc -and $proc.CommandLine -like "*$StudioRoot*" -and (($component -eq 'backend' -and $proc.CommandLine -like '*studio-1.0.0.jar*') -or ($component -eq 'frontend' -and $proc.CommandLine -like '*vite*bin*vite.js*'))
        if ($valid) { Stop-Process -Id $studioPid -Force; Write-Output "Stopped $component ($studioPid)." }
        Remove-Item -LiteralPath $pidFile -Force
    }
}
Write-Output 'MySQL is left running because other local projects may use it.'
