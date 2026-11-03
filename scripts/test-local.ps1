$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$repo = Join-Path $env:USERPROFILE '.m2\repository'
$patterns = @('org\junit\jupiter\junit-jupiter-api\5.12.2\*.jar','org\junit\jupiter\junit-jupiter-params\5.12.2\*.jar','org\junit\jupiter\junit-jupiter-engine\5.12.2\*.jar','org\junit\platform\junit-platform-*\1.12.2\*.jar','org\opentest4j\opentest4j\1.3.0\*.jar','org\apiguardian\apiguardian-api\1.1.2\*.jar','com\fasterxml\jackson\core\*\2.19.2\*.jar')
$jars = foreach ($pattern in $patterns) { Get-ChildItem (Join-Path $repo $pattern) | Where-Object Name -NotMatch '(sources|javadoc)' | ForEach-Object FullName }
New-Item -ItemType Directory -Force .tools\lib | Out-Null
foreach ($jar in $jars) { Copy-Item -LiteralPath $jar -Destination .tools\lib -Force }
$cp = (Get-ChildItem .tools\lib\*.jar | ForEach-Object FullName) -join ';'
$javaBin = 'C:\Program Files\Java\jdk-24\bin'
New-Item -ItemType Directory -Force target\test-classes | Out-Null
& "$javaBin\javac.exe" -encoding UTF-8 --release 17 -cp $cp -d target\test-classes (Get-ChildItem src\test\java\codes\nord\*.java | ForEach-Object FullName)
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$javaBin\java.exe" -cp "target\test-classes;$cp" codes.nord.LocalRunner
exit $LASTEXITCODE

