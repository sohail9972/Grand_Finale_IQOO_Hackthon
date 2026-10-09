# ============================================================
# build.ps1
# Run AFTER start-build-env.ps1.
# Builds the debug APK with memory-safe flags.
# ============================================================

$ErrorActionPreference = "Continue"

# Verify we're in the project folder
$projectRoot = "D:\ProjectsForResume\Grand_Finale_Pitch"
if (-not (Test-Path "$projectRoot\gradlew.bat")) {
    Write-Host "ERROR: Project not found at $projectRoot" -ForegroundColor Red
    exit 1
}
Set-Location $projectRoot

# Set Java
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"

Write-Host ""
Write-Host "=== BUILDING DEBUG APK ===" -ForegroundColor Cyan
Write-Host "Started: $(Get-Date -Format 'HH:mm:ss')" -ForegroundColor Gray
Write-Host ""

$startTime = Get-Date

# Run Gradle build
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain 2>&1 | 
    Tee-Object -Variable buildOutput | 
    Select-Object -Last 40

$endTime = Get-Date
$duration = [math]::Round(($endTime - $startTime).TotalSeconds, 0)

Write-Host ""
Write-Host "=== BUILD COMPLETE ===" -ForegroundColor Cyan
Write-Host "Duration: $duration seconds" -ForegroundColor Gray
Write-Host ""

# Check result
$buildSuccess = $buildOutput | Select-String "BUILD SUCCESSFUL"
$buildFailed = $buildOutput | Select-String "BUILD FAILED"

if ($buildSuccess) {
    Write-Host "BUILD SUCCESSFUL" -ForegroundColor Green
    $apkPath = "$projectRoot\app\build\outputs\apk\debug\app-debug.apk"
    if (Test-Path $apkPath) {
        $sizeMB = [math]::Round((Get-Item $apkPath).Length / 1MB, 2)
        Write-Host "APK: $apkPath ($sizeMB MB)" -ForegroundColor Green
    }
} elseif ($buildFailed) {
    Write-Host "BUILD FAILED — check output above." -ForegroundColor Red
    exit 1
} else {
    Write-Host "Build status unknown — check output above." -ForegroundColor Yellow
}