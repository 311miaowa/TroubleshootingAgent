# Credential / privacy scan -- usable as an open-source pre-flight check or a pre-push hook.
#
# Usage (Windows PowerShell 5.1 or PowerShell 7+):
#   powershell -NoProfile -File docs/dev-ops/scripts/scan-secrets.ps1
#   powershell -NoProfile -File docs/dev-ops/scripts/scan-secrets.ps1 -TreeOnly
#
# Exit codes:
#   0 = clean
#   1 = working tree hits  -> fix before pushing
#   2 = history-only hits  -> this repo must NOT be flipped public; use the snapshot flow
#                            (see docs/dev-ops/OPENSOURCE-CHECKLIST.md section 3)
#
[CmdletBinding()]
param(
    [switch]$TreeOnly
)

$ErrorActionPreference = 'Continue'

# --- high-risk shapes --------------------------------------------------------
$patterns = [ordered]@{
    'OpenAI-compatible sk- key' = 'sk-[A-Za-z0-9]{24,}'
    'Baidu Qianfan AK'          = 'ALTAK-[A-Za-z0-9]{8,}'
    'Amap key (32 hex)'         = 'key=[0-9a-f]{32}'
    'Bare 32-hex token'         = '\b[0-9a-f]{32}\b'
    'apikey query parameter'    = 'apikey=[A-Za-z0-9]{8,}'
    'GitHub token'              = 'gh[pousr]_[A-Za-z0-9]{20,}|github_pat_'
    'GitLab token'              = 'glpat-[A-Za-z0-9_-]{16,}'
    'Slack token'               = 'xox[baprs]-[A-Za-z0-9-]{10,}'
    'Google API key'            = 'AIza[0-9A-Za-z_-]{35}'
    'AWS access key id'         = 'AKIA[0-9A-Z]{16}'
    'Private key block'         = '-----BEGIN [A-Z ]*PRIVATE KEY'
    'JWT'                       = 'eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{10,}'
    'Personal email address'    = '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}'
    'Known third-party relay'   = 'apis\.itedus\.cn'
    'JDBC url with credentials' = 'jdbc:[a-z]+://[^:/@ ]+:[^@/ ]+@'
    'Hardcoded weak password'   = "password\s*[:=]\s*['""]?(123456|admin|postgres|root)['""]?"
}

# --- known-safe placeholders (a line matching this is not a finding) ---------
$allowlist = 'sk-your-openai-api-key|sk-REPLACE_WITH_YOUR_API_KEY|sk-test123456789|sk-updated123456789|ALTAK-your-baidu-api-key|change-me-[a-z-]*|your-api-key|your-key|your-key-here|\$\{[A-Z_]+:\}|passwordEnv|PASSWORD:|PASSWORD=|password: \$\{|MYSQL_ROOT_PASSWORD=\$\{|noreply|users\.noreply|example\.com|admin@qq\.com|<你的|your-email'

# --- known-safe 32-hex values (not secrets, but the same shape) --------------
# ACL recovery report/backup ids produced by the DSH sandbox ACL tooling.
$knownSafe32Hex = '8970a6d4164344c7b68c2001ff891bc9|9c25f7f0e3b442948e37b135883400ca|00000000000000000000000000000000'

# --- files that legitimately contain pattern source text ---------------------
$selfSkip = 'docs/dev-ops/scripts/scan-secrets.ps1'

function Test-Lines {
    param([string[]]$Lines)
    $hits = @()
    foreach ($line in $Lines) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        if ($line -match $allowlist) { continue }
        foreach ($k in $patterns.Keys) {
            if ($line -match $patterns[$k]) {
                $shown = $line
                if ($shown.Length -gt 200) { $shown = $shown.Substring(0, 200) }
                $hits += ('[{0}] {1}' -f $k, $shown)
                break
            }
        }
    }
    return $hits
}

Write-Host '=== 1) working tree scan (tracked files) ===' -ForegroundColor Cyan
$treeLines = @()
foreach ($k in $patterns.Keys) {
    # no rev: git grep searches tracked files in the working tree, which is the right scope
    # for a pre-push hook and for the pre-snapshot check.
    $treeLines += (git grep -n -I -E $patterns[$k] 2>$null)
}
# drop: the scanner's own source (it contains the pattern text) and known-safe 32-hex values
$treeLines = $treeLines | Where-Object { $_ -and ($_ -notlike ($selfSkip + ':*')) -and ($_ -notmatch $knownSafe32Hex) }
$treeHits = Test-Lines -Lines $treeLines
if ($treeHits.Count -gt 0) {
    Write-Host ('WORKING TREE HITS: {0}' -f $treeHits.Count) -ForegroundColor Red
    $treeHits | Select-Object -First 40 | ForEach-Object { Write-Host ('  ' + $_) }
} else {
    Write-Host 'working tree: clean' -ForegroundColor Green
}

if ($TreeOnly) {
    if ($treeHits.Count -gt 0) { exit 1 } else { exit 0 }
}

Write-Host ''
Write-Host '=== 2) full history scan (all refs) ===' -ForegroundColor Cyan
$histHits = @()
foreach ($k in $patterns.Keys) {
    $pat = $patterns[$k]
    # -S needs --pickaxe-regex, otherwise the pattern is matched literally (and never hits)
    $commits = git log --all --pickaxe-regex --format='%h %ad %an %s' --date=short -S"$pat" 2>$null
    if ($commits) { $histHits += $commits }
}
if ($histHits.Count -gt 0) {
    Write-Host ('HISTORY HITS (commits, before dedup): {0}' -f $histHits.Count) -ForegroundColor Yellow
    $histHits | Sort-Object -Unique | Select-Object -First 30 | ForEach-Object { Write-Host ('  ' + $_) }
    Write-Host 'NOTE: history hits mean this repo must NOT be flipped public.' -ForegroundColor Yellow
    Write-Host '      Use the snapshot flow: docs/dev-ops/OPENSOURCE-CHECKLIST.md section 3.' -ForegroundColor Yellow
} else {
    Write-Host 'history: no hits' -ForegroundColor Green
}

if ($treeHits.Count -gt 0) { exit 1 }
if ($histHits.Count -gt 0) { exit 2 }
exit 0
