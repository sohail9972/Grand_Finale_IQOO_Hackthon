# ============================================================
# monitor-memory.ps1
# Run in a SEPARATE window during builds.
# Prints free RAM every 5 seconds. Press Ctrl+C to stop.
# ============================================================

Write-Host "Monitoring RAM. Press Ctrl+C to stop." -ForegroundColor Cyan
Write-Host ""

while ($true) {
    $mem = Get-CimInstance Win32_OperatingSystem
    $freeGB = [math]::Round($mem.FreePhysicalMemory / 1MB, 2)
    $usedGB = [math]::Round(($mem.TotalVisibleMemorySize - $mem.FreePhysicalMemory) / 1MB, 2)

    $color = if ($freeGB -lt 1) { "Red" } elseif ($freeGB -lt 2) { "Yellow" } else { "Green" }
    $time = Get-Date -Format "HH:mm:ss"

    Write-Host "[$time] Free: $freeGB GB  |  Used: $usedGB GB" -ForegroundColor $color

    Start-Sleep -Seconds 5
}