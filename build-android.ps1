param([switch]$SkipBootstrap, [switch]$RunLint)
$ErrorActionPreference = 'Stop'
$gradle = "$PSScriptRoot\gradlew.bat"
if (!(Test-Path -LiteralPath $gradle)) { throw "Gradle wrapper not found: $gradle" }
Push-Location $PSScriptRoot
try {
if ($RunLint) {
    & $gradle ':app:assembleDebug' ':app:lintDebug'
} else {
    & $gradle ':app:assembleDebug'
}
$buildExit = $LASTEXITCODE
} finally { Pop-Location }
if ($buildExit -ne 0) { exit $buildExit }
Write-Host "APK: $PSScriptRoot\app\build\outputs\apk\debug\app-debug.apk"
