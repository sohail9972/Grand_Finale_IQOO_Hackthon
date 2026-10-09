# ============================================================
# stop-build-env.ps1
# Run AFTER the build completes.
# Cleans up Gradle/Java processes and shows final state.
# ============================================================

Write-Host ""
Write-Host "=== STOP BUILD ENV ===" -ForegroundColor Cyan
Write-Host ""

# Step 1: Stop Gradle daemon
Write-Host "[1/2] Stopping Gradle daemon..." -ForegroundColor Yellow
$projectRoot = "D:\ProjectsForResume\Grand_Finale_Pitch"
if (Test-Path "$projectRoot\gradlew.bat") {
    Set-Location $projectRoot
    .\gradlew.bat --stop 2>&1 | Out-Null
    Write-Host "  Daemon stopped." -ForegroundColor Gray
}

# Step 2: Kill any remaining Gradle/Kotlin processes
Write-Host "[2/2] Cleaning up stray processes..." -ForegroundColor Yellow
Get-Process kotlin-daemon, gradle -ErrorAction SilentlyContinue | 
    Stop-Process -Force -ErrorAction SilentlyContinue
Write-Host "  Done." -ForegroundColor Gray

# Final memory report
$mem = Get-CimInstance Win32_OperatingSystem
$freeGB = [math]::Round($mem.FreePhysicalMemory / 1MB, 2)
Write-Host ""
Write-Host "Free RAM now: $freeGB GB" -ForegroundColor $(if ($freeGB -ge 4) { "Green" } else { "Yellow" })
Write-Host ""
Write-Host "=== CLEANUP COMPLETE ===" -ForegroundColor Cyan
Write-Host ""