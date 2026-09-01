# Restore entire workspace to the active restore-point tag.
# WARNING: discards ALL uncommitted changes and removes untracked files.

$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot -Parent)

if (-not (Test-Path ".git")) {
    Write-Error "No git repo. Nothing to restore."
}

if (-not (git rev-parse restore-point 2>$null)) {
    Write-Error "Tag 'restore-point' not found. Run .\scripts\pin-restore-point.ps1 first."
}

$hash = git rev-parse --short restore-point
Write-Host "This will reset ALL files to restore-point ($hash)."
Write-Host "Uncommitted work and new untracked files will be LOST."
$confirm = Read-Host "Type YES to continue"

if ($confirm -ne "YES") {
    Write-Host "Cancelled."
    exit 1
}

git reset --hard restore-point
git clean -fdx -e local.properties -e zoevip.keystore

Write-Host ""
Write-Host "Restored to restore-point ($hash)."
Write-Host "Keystore/local.properties were kept if they existed."
