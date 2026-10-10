<#
.SYNOPSIS
  从 git 完整历史中清除已泄露的密钥（重写所有提交，属于破坏性操作，请阅读 SECURITY.md 后再执行）。
.DESCRIPTION
  依赖 git-filter-repo（安装：pip install git-filter-repo 或 winget install --id=NewAM.git-filter-repo）。
  要清除的密钥字符串从 scripts/secret-expressions.txt 读取（该文件已被 .gitignore 忽略，不会被提交）。
  每行格式：原始密钥==>***REMOVED***
.PARAMETER Push
  重写完成后执行 git push --force-with-lease 覆盖远端历史（不加此参数只重写本地）。
.EXAMPLE
  # 先只重写本地历史，确认无误后再加 -Push
  powershell -ExecutionPolicy Bypass -File scripts/purge-git-history.ps1
  powershell -ExecutionPolicy Bypass -File scripts/purge-git-history.ps1 -Push
#>
param([switch]$Push)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path | Split-Path -Parent
$exprFile = Join-Path $root 'scripts\secret-expressions.txt'
$repos = @(
    (Join-Path $root ''),
    (Join-Path $root 'vibe-music-server')
)

if (-not (Test-Path $exprFile)) {
    Write-Error "找不到 $exprFile 。请先创建该文件，每行一条规则：原始密钥==>***REMOVED***"
    exit 1
}
$content = Get-Content $exprFile -Raw
if ($content -notmatch '==>') {
    Write-Error "$exprFile 内容不合法，每行应为：原始密钥==>***REMOVED***"
    exit 1
}

$cmd = Get-Command git-filter-repo -ErrorAction SilentlyContinue
if (-not $cmd) { $cmd = Get-Command 'git-filter-repo.exe' -ErrorAction SilentlyContinue }
if (-not $cmd) {
    Write-Error '未安装 git-filter-repo。请先执行：pip install git-filter-repo 或 winget install --id=NewAM.git-filter-repo'
    exit 1
}

foreach ($repo in $repos) {
    Write-Output ''
    Write-Output "===== processing: $repo ====="
    Push-Location $repo
    try {
        $dirty = git status --porcelain
        if ($dirty) {
            Write-Error "工作区有未提交改动，请先提交或 stash 后重试：$repo"
            exit 1
        }

        $remoteUrl = git remote get-url origin
        Write-Output "remote origin = $remoteUrl"

        git filter-repo --replace-text $exprFile --force
        if ($LASTEXITCODE -ne 0) { Write-Error "filter-repo failed in $repo"; exit 1 }

        # filter-repo 默认会移除 origin，这里恢复
        if (-not (git remote get-url origin 2>$null)) {
            git remote add origin $remoteUrl
            Write-Output 'restored remote origin'
        }

        if ($Push) {
            Write-Output 'force pushing rewritten history ...'
            git push --force-with-lease origin --all
            git push --force-with-lease origin --tags
        } else {
            Write-Output '本地历史已重写（未推送）。确认无误后重新运行本脚本并加 -Push。'
        }
    } finally { Pop-Location }
}

Write-Output ''
Write-Output '完成。后续操作见 SECURITY.md「历史清理之后」章节。'