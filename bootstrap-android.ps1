$ErrorActionPreference = 'Stop'
$version = '8.11.1'
$tooling = Join-Path $PSScriptRoot '.tooling'
$gradleHome = Join-Path $tooling "gradle-$version"
if (Test-Path (Join-Path $gradleHome 'bin\gradle.bat')) { Write-Host "Gradle $version is already available."; exit 0 }
New-Item -ItemType Directory -Force -Path $tooling | Out-Null
$zip = Join-Path $tooling "gradle-$version-bin.zip"
Invoke-WebRequest -Uri "https://services.gradle.org/distributions/gradle-$version-bin.zip" -OutFile $zip
Expand-Archive -Path $zip -DestinationPath $tooling -Force
Remove-Item -LiteralPath $zip
Write-Host "Installed Gradle $version at $gradleHome"
