# Run and Push Script for System Design
# Run this in PowerShell from d:\git\system-design

param(
    [switch]$BuildOnly,
    [switch]$PushOnly
)

$ErrorActionPreference = "Stop"
$projectRoot = $PSScriptRoot

# Build
if (-not $PushOnly) {
    Write-Host "Building project..." -ForegroundColor Cyan
    $env:MAVEN_PROJECTBASEDIR = $projectRoot
    java -classpath "$projectRoot\.mvn\wrapper\maven-wrapper.jar" `
        "-Dmaven.multiModuleProjectDirectory=$projectRoot" `
        org.apache.maven.wrapper.MavenWrapperMain clean install -q
    
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Host "Build successful!" -ForegroundColor Green
}

if ($BuildOnly) { exit 0 }

# Git push
Write-Host "`nPushing to GitHub..." -ForegroundColor Cyan
Set-Location $projectRoot

if (-not (Test-Path ".git")) {
    git init
    git add .
    git commit -m "Initial commit: System design - Rate Limiter, Notification, Snake Ladder, Logging"
    git branch -M main
    git remote add origin git@github.com:RohanYadav1812/system-design.git
}
git add .
git status
git diff --cached --quiet 2>$null
if ($LASTEXITCODE -eq 0) {
    Write-Host "No changes to commit."
} else {
    git commit -m "Update system design"
}
git push -u origin main

Write-Host "`nDone!" -ForegroundColor Green
