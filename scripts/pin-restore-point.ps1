# Pin current workspace as the active restore point (replaces previous pin).
# Usage: .\scripts\pin-restore-point.ps1 [-Message "optional note"]

param(
    [string]$Message = ""
)

$ErrorActionPreference = "Continue"
Set-Location (Split-Path $PSScriptRoot -Parent)

if (-not (Test-Path ".git")) {
    git init -b main | Out-Null
}

$stamp = Get-Date -Format "yyyy-MM-dd HH:mm"
$ver = ""
if (Test-Path "app\build.gradle.kts") {
    if ((Get-Content "app\build.gradle.kts" -Raw) -match 'versionName\s*=\s*"([^"]+)"') {
        $ver = $Matches[1]
    }
}

$note = if ($Message) { $Message } else { "Hills 1.7.2 unlock working (Zot-equivalent)" }
$prevHash = "(first pin)"
try {
    $existing = git rev-parse --short restore-point 2>$null
    if ($LASTEXITCODE -eq 0 -and $existing) { $prevHash = $existing }
} catch {
}

@"
# Restore Point (active)

| Field | Value |
|-------|-------|
| Pinned at | $stamp |
| Version | $(if ($ver) { $ver } else { "unknown" }) |
| Git tag | restore-point |
| Previous commit | $prevHash |
| Note | $note |

## Restore to this point

Run: cd e:\23\apk\zoevip ; .\scripts\restore-to-pin.ps1

Or tell Cursor: **恢复到恢复点**

## Pin a new restore point (replaces this one)

Run: cd e:\23\apk\zoevip ; .\scripts\pin-restore-point.ps1 -Message "your note"

Or tell Cursor: **钉入恢复点**
"@ | Set-Content -Encoding UTF8 "RESTORE_POINT.md"

$paths = @(
    ".gitignore",
    "RESTORE_POINT.md",
    "scripts",
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradle.properties",
    "gradlew.bat",
    "gradle",
    "build.ps1",
    "README.md",
    "PROGRESS.md",
    "docs",
    "info",
    "branding",
    "artifacts",
    "app/build.gradle.kts",
    "app/src"
)

git add -- $paths 2>$null
git add -u

$env:GIT_AUTHOR_NAME = "ZoeVIP Restore"
$env:GIT_AUTHOR_EMAIL = "restore@local"
$env:GIT_COMMITTER_NAME = "ZoeVIP Restore"
$env:GIT_COMMITTER_EMAIL = "restore@local"

$status = git status --porcelain
if ($status) {
    $commitMsg = "pin restore-point @ $stamp"
    if ($ver) { $commitMsg += " (v$ver)" }
    if ($Message) { $commitMsg += " — $Message" }
    git commit -m $commitMsg
} else {
    Write-Host "No file changes since last commit; moving tag only."
}

$tagMsg = "Restore point pinned $stamp"
if ($ver) { $tagMsg += " | ZoeVIP v$ver" }
if ($Message) { $tagMsg += " | $Message" }
git tag -f restore-point -m $tagMsg
$hash = git rev-parse --short restore-point

# Refresh marker with final commit hash
(Get-Content RESTORE_POINT.md -Raw) `
    -replace '\| Previous commit \| .* \|', "| Commit | $hash |" `
    -replace '\| Previous commit \|', '| Commit |' `
    | Set-Content -Encoding UTF8 "RESTORE_POINT.md"

Write-Host ""
Write-Host "Restore point pinned: restore-point -> $hash ($stamp)"
if ($ver) { Write-Host "ZoeVIP version: v$ver" }
Write-Host "To restore later: .\scripts\restore-to-pin.ps1"
