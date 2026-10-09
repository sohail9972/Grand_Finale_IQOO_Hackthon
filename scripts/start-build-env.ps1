# ============================================================
# start-build-env.ps1
# Run BEFORE any Gradle build.
# Closes memory hogs and verifies free RAM.
# ============================================================

Write-Host ""
Write-Host "=== ANIMAL HEALTH BRIDGE — START BUILD ENV ===" -ForegroundColor Cyan
Write-Host ""

# Step 1: Close memory hogs
Write-Host "[1/4] Closing memory-heavy apps..." -ForegroundColor Yellow
$appsToClose = @("chrome", "msedge", "msedgewebview2", "firefox", "slack", "discord", "spotify", "teams", "Everything")
$closed = 0
foreach ($app in $appsToClose) {
    $procs = Get-Process $app -ErrorAction SilentlyContinue
    if ($procs) {
        $procs | Stop-Process -Force -ErrorAction SilentlyContinue
        Write-Host "  Closed: $app ($($procs.Count) process(es))" -ForegroundColor Gray
        $closed += $procs.Count
    }
}
if ($closed -eq 0) {
    Write-Host "  No heavy apps running." -ForegroundColor Gray
}

# Step 2: Stop any lingering Gradle/Kotlin daemons
Write-Host ""
Write-Host "[2/4] Stopping Gradle and Kotlin daemons..." -ForegroundColor Yellow
Get-Process gradle, kotlin-daemon, java, javaw -ErrorAction SilentlyContinue | 
    Where-Object { $_.Path -like "*gradle*" -or $_.Path -like "*kotlin*" } |
    Stop-Process -Force -ErrorAction SilentlyContinue
Write-Host "  Done." -ForegroundColor Gray

# Step 3: Set JAVA_HOME
Write-Host ""
Write-Host "[3/4] Setting JAVA_HOME..." -ForegroundColor Yellow
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
if (Test-Path $env:JAVA_HOME) {
    Write-Host "  JAVA_HOME = $env:JAVA_HOME" -ForegroundColor Green
} else {
    Write-Host "  ERROR: JDK 17 not found at $env:JAVA_HOME" -ForegroundColor Red
    Write-Host "  Update the path in this script." -ForegroundColor Red
    exit 1
}

# Step 4: Check free memory
Write-Host ""
Write-Host "[4/4] Checking free RAM..." -ForegroundColor Yellow
$mem = Get-CimInstance Win32_OperatingSystem
$freeGB = [math]::Round($mem.FreePhysicalMemory / 1MB, 2)
$totalGB = [math]::Round($mem.TotalVisibleMemorySize / 1MB, 2)

Write-Host ""
Write-Host "  Free:  $freeGB GB" -ForegroundColor $(if ($freeGB -ge 4) { "Green" } elseif ($freeGB -ge 2.5) { "Yellow" } else { "Red" })
Write-Host "  Total: $totalGB GB" -ForegroundColor Gray
Write-Host ""

if ($freeGB -lt 2.5) {
    Write-Host "WARNING: Less than 2.5 GB free." -ForegroundColor Red
    Write-Host "Build may freeze the laptop. Close more apps or restart." -ForegroundColor Red
    $proceed = Read-Host "Continue anyway? (y/N)"
    if ($proceed -ne "y" -and $proceed -ne "Y") {
        Write-Host "Aborted." -ForegroundColor Yellow
        exit 1
    }
} elseif ($freeGB -lt 4) {
    Write-Host "OK to build (will be slow)." -ForegroundColor Yellow
} else {
    Write-Host "Ready to build." -ForegroundColor Green
}

Write-Host ""
Write-Host "=== ENV READY ===" -ForegroundColor Cyan
Write-Host ""