param([string]$Maven = 'mvn', [switch]$Offline)
$ErrorActionPreference = 'Stop'
$Project = Split-Path -Parent $PSScriptRoot
$Commit = 'ad7c02a59e4dc3296423e16fd2d017b59ee4d7dc'
$Remote = 'https://github.com/Logisim-Ita/Logisim.git'
$Upstream = Join-Path $Project 'upstream'
$Reports = Join-Path $Project 'work/reports'
New-Item -ItemType Directory -Force -Path $Reports | Out-Null

if (-not (Test-Path -LiteralPath $Upstream)) {
    git init $Upstream
    if ($LASTEXITCODE -ne 0) { throw 'Git could not initialize the upstream directory.' }
    git -C $Upstream remote add origin $Remote
    if ($LASTEXITCODE -ne 0) { throw 'Git could not set the upstream remote.' }
    git -C $Upstream fetch --depth 1 origin $Commit
    if ($LASTEXITCODE -ne 0) { throw 'Git could not fetch the pinned upstream commit.' }
    git -C $Upstream checkout --detach $Commit
    if ($LASTEXITCODE -ne 0) { throw 'Git could not check out the pinned upstream commit.' }
}

$Current = git -C $Upstream rev-parse HEAD
if ($LASTEXITCODE -ne 0 -or $Current -ne $Commit) { throw 'The upstream directory is not at the pinned commit.' }
if (git -C $Upstream status --porcelain --untracked-files=no) { throw 'Tracked upstream files have local changes.' }
$MavenCommand = (Get-Command $Maven).Source
$Cache = Join-Path $Project 'work/m2-cache'
$Log = Join-Path $Reports 'm0-maven.log'
Push-Location (Join-Path $Upstream 'Logisim-Fork')
try {
    $BuildArgs = @('-B', "-Dmaven.repo.local=$Cache")
    if ($Offline) { $BuildArgs += '-o' }
    $BuildArgs += @('clean', 'package')
    & $MavenCommand @BuildArgs 2>&1 | ForEach-Object { $_.ToString() } | Set-Content -Encoding UTF8 -Path $Log
    $BuildExit = $LASTEXITCODE
    Get-Content -LiteralPath $Log -Tail 12
    if ($BuildExit -ne 0) { throw "Maven build failed. See $Log" }
} finally {
    Pop-Location
}
