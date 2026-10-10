<#
.SYNOPSIS
  密钥泄露扫描器：阻止密码 / API Key / 授权码等敏感信息进入 Git。
.DESCRIPTION
  规则 1：匹配 sk- 开头的长密钥（OpenAI / DashScope 风格）。
  规则 2：配置文件（yml/yaml/properties/env/xml/json/java/ts/js）中出现
          password|secret|token|api-key|access-key|授权码等键名，但值不是 ${ENV} 占位符、
          环境变量引用或空值时告警。
  默认只扫描会被 Git 跟踪的文件（遵守 .gitignore），因此本地 .env 不会被误报。
.PARAMETER Staged
  只扫描当前暂存区中新增/修改的文件（供 pre-commit 钩子使用）。
.PARAMETER Repo
  指定要扫描的仓库根目录，默认当前目录。
.PARAMETER Install
  把本脚本安装为 pre-commit 钩子（写入 .git/hooks）。
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts/check-secrets.ps1 -Install
#>
param(
    [switch]$Staged,
    [string]$Repo = (Get-Location).Path,
    [switch]$Install
)

$ErrorActionPreference = 'Stop'
$Repo = (Resolve-Path $Repo).Path
$scriptPath = $MyInvocation.MyCommand.Path

if ($Install) {
    $hookDir = Join-Path $Repo '.git\hooks'
    if (-not (Test-Path $hookDir)) { Write-Error "not a git repository: $Repo"; exit 1 }
    $hookPath = Join-Path $hookDir 'pre-commit'
    $hook = "#!/bin/sh`npowershell.exe -NoProfile -ExecutionPolicy Bypass -File '$scriptPath' -Staged -Repo '$Repo'"
    Set-Content -Path $hookPath -Value $hook -Encoding ascii -NoNewline
    Write-Output "installed pre-commit hook -> $hookPath"
    exit 0
}

function Get-CandidateFiles {
    Push-Location $Repo
    try {
        if ($Staged) {
            $names = git diff --cached --name-only --diff-filter=ACM
        } else {
            $tracked = git ls-files
            $untracked = git ls-files --others --exclude-standard
            $names = @($tracked) + @($untracked)
        }
        return @($names | Where-Object { $_ })
    } finally { Pop-Location }
}

$files = Get-CandidateFiles
$violations = @()
$skPattern = 'sk-[A-Za-z0-9]{28,}'
$configExt = @('.yml', '.yaml', '.properties', '.env', '.toml', '.ini', '.conf')
$keyPattern = '(?i)\b(password|passwd|secret|secretkey|accesskey|api[-_]?key|token|authorization[-_]?code|auth[-_]?code|credential)\b\s*[:=]'

foreach ($file in $files) {
    $full = Join-Path $Repo $file
    if (-not (Test-Path -LiteralPath $full -PathType Leaf)) { continue }
    $ext = [System.IO.Path]::GetExtension($file).ToLower()
    if ($ext -in @('.png', '.jpg', '.jpeg', '.ico', '.gif', '.mp3', '.wav', '.jar', '.class', '.zip', '.pdf')) { continue }

    $lineNo = 0
    foreach ($line in Get-Content -LiteralPath $full) {
        $lineNo++
        if ($line -match $skPattern) {
            $violations += "${file}:${lineNo}: suspicious API key pattern"
            continue
        }
        if ($ext -in $configExt -and $line -match $keyPattern) {
            $trimmed = $line.Trim()
            if ($trimmed.StartsWith('#') -or $trimmed.StartsWith('//') -or $trimmed.StartsWith('*') -or $trimmed.StartsWith('<!--')) { continue }
            $sepIndex = $line.IndexOfAny(@(':', '='))
            $value = $line.Substring($sepIndex + 1).Trim().Trim('"').Trim("'")
            if ($value -eq '' -or $value.StartsWith('${') -or $value -match '^(YOUR_|<|\$\{|\*)' -or $value -match '^process\.env') { continue }
            $violations += "${file}:${lineNo}: hardcoded credential-like value"
        }
    }
}

if ($violations.Count -gt 0) {
    Write-Output '=== SECRET SCAN FAILED ==='
    $violations | ForEach-Object { Write-Output $_ }
    Write-Output '=========================='
    Write-Output 'commit 已阻止：请把敏感值移到环境变量或 .env（参考 .env.example），配置文件中只保留 ${ENV_NAME:} 占位符。'
    Write-Output '如确认是误报，可用 git commit --no-verify 强行提交（不推荐）。'
    exit 1
}
Write-Output 'secret scan passed'
exit 0