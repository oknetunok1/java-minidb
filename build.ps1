param([string]$Action = 'run', [string]$Jdk = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
[Console]::InputEncoding = [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
if ($Jdk) { $env:PATH = "$Jdk\bin;" + $env:PATH }
New-Item -ItemType Directory -Force build | Out-Null
$sources = @(Get-ChildItem src -Recurse -Filter *.java | ForEach-Object FullName)
& javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -d build @sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
if ($Action -eq 'run') {
    & java -cp build lab.minidb.Main
} elseif ($Action -eq 'test' -or $Action -eq 'benchmark') {
    $tests = @(Get-ChildItem tests -Recurse -Filter *.java | ForEach-Object FullName)
    & javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -cp build -d build @tests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    if ($Action -eq 'test') { & java -cp build lab.minidb.DatabaseTest }
    else { & java -cp build lab.minidb.Benchmark }
} elseif ($Action -ne 'build') { throw 'Use run, build, test or benchmark' }
exit $LASTEXITCODE
