# Snapshot the current V1 into a history-free branch for open sourcing.
#
# Why: this repository's history contains real demo keys committed by the upstream course author
# (see docs/dev-ops/OPENSOURCE-CHECKLIST.md section 1.1). Flipping the repo public would expose them.
# An orphan branch has no parent commit, so nothing from history travels with it.
#
# Usage (run from the repository root; Windows PowerShell 5.1 or PowerShell 7+):
#   powershell -NoProfile -File docs/dev-ops/scripts/opensource-snapshot.ps1
#   powershell -NoProfile -File docs/dev-ops/scripts/opensource-snapshot.ps1 -Commit
#   powershell -NoProfile -File docs/dev-ops/scripts/opensource-snapshot.ps1 -IncludeInternalDocs
#
# After it succeeds:
#   git remote add public https://github.com/<account>/<public-repo>.git
#   git push public public-snapshot:main
#
[CmdletBinding()]
param(
    [string]$BaseRef = 'origin/V1-for-secondary-development',
    [string]$Branch = 'public-snapshot',
    [switch]$IncludeInternalDocs,
    [switch]$Commit
)

$ErrorActionPreference = 'Stop'

# Internal docs that are NOT published with the open-source snapshot
# (they stay in the private repo; see OPENSOURCE-CHECKLIST.md section 3.2)
$internalDocs = @(
    'docs/dev-ops/interview-prep-resume-five-points.md',
    'docs/dev-ops/M4-status-and-next-tasks.md',
    'docs/dev-ops/NEXT-SESSION-PROMPT.md',
    'docs/dev-ops/GAP-five-points-development-plan.md'
)

Write-Host '== 0) preconditions ==' -ForegroundColor Cyan
if ((git status --porcelain)) { throw 'working tree is not clean; commit or stash first' }
git rev-parse --verify $BaseRef | Out-Null
if ((git branch --list $Branch)) { throw "branch $Branch already exists; delete it or pass -Branch" }

Write-Host "== 1) create orphan branch $Branch from $BaseRef ==" -ForegroundColor Cyan
git checkout --orphan $Branch $BaseRef

Write-Host '== 2) drop internal docs ==' -ForegroundColor Cyan
if ($IncludeInternalDocs) {
    Write-Host '   (-IncludeInternalDocs: keeping everything)'
} else {
    foreach ($f in $internalDocs) {
        if (Test-Path $f) {
            git rm -q -f --ignore-unmatch $f
            Write-Host "   removed  $f"
        }
    }
}

Write-Host '== 3) credential scan (working tree) ==' -ForegroundColor Cyan
$scan = 'docs/dev-ops/scripts/scan-secrets.ps1'
if (Test-Path $scan) {
    & powershell -NoProfile -File $scan -TreeOnly
    if ($LASTEXITCODE -ne 0) {
        Write-Host 'scan found credentials -> aborting before commit; fix and re-run' -ForegroundColor Red
        exit 1
    }
}

Write-Host '== 4) snapshot contents ==' -ForegroundColor Cyan
git --no-pager status --short | Select-Object -First 15
$pending = (git status --porcelain | Measure-Object -Line).Lines
Write-Host ('   pending changes: ' + $pending)

if ($Commit) {
    Write-Host '== 5) commit snapshot ==' -ForegroundColor Cyan
    git add -A
    git commit -q -m "chore: initial open-source snapshot (secondary development: incident root-cause agent)" -m "No parent commit: this snapshot carries no history (history contains the upstream course author's demo keys, so it is not published). Upstream attribution: see NOTICE."
    Write-Host '   committed. next steps:' -ForegroundColor Green
    Write-Host '     git remote add public https://github.com/<account>/<public-repo>.git'
    Write-Host "     git push public ${Branch}:main"
} else {
    Write-Host '(-Commit not passed: branch and worktree are ready; re-run with -Commit when satisfied)' -ForegroundColor Yellow
}
