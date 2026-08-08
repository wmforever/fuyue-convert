param(
  [string]$MavenBin = "mvn",
  [string]$JlinkBin = "",
  [string]$JpackageBin = "",
  [string]$MavenArgs = "-DskipTests"
)

$ErrorActionPreference = "Stop"
$RootDir = Resolve-Path (Join-Path $PSScriptRoot "..")
$DistDir = Join-Path $RootDir "dist"

[xml]$pom = Get-Content (Join-Path $RootDir "pom.xml")
$Version = $pom.project.version
if (-not $Version) { $Version = "0.0.0" }
$AppVersion = "0.1.1"
if ($Version -match "^\d+(\.\d+){0,2}") { $AppVersion = $Matches[0] }

$Arch = $env:PROCESSOR_ARCHITECTURE
if (-not $Arch) { $Arch = "x64" }
$PackageName = "fuyue-convert-$Version-windows-$Arch"
$PackageDir = Join-Path $DistDir $PackageName
$RuntimeDir = Join-Path $PackageDir "runtime"

if (-not $JlinkBin) {
  if ($env:JAVA_HOME) {
    $JlinkBin = Join-Path $env:JAVA_HOME "bin\jlink.exe"
  } else {
    $JlinkBin = "jlink.exe"
  }
}
if (-not $JpackageBin) {
  if ($env:JAVA_HOME) {
    $JpackageBin = Join-Path $env:JAVA_HOME "bin\jpackage.exe"
  } else {
    $JpackageBin = "jpackage.exe"
  }
}

Push-Location $RootDir
$EffectiveMavenArgs = if ($env:PACKAGE_MAVEN_ARGS) { $env:PACKAGE_MAVEN_ARGS } else { $MavenArgs }
if (-not $EffectiveMavenArgs) {
  $EffectiveMavenArgs = "-DskipTests"
} elseif ($EffectiveMavenArgs -notmatch "-DskipTests" -and $EffectiveMavenArgs -notmatch "-Dmaven\.test\.skip") {
  $EffectiveMavenArgs = "$EffectiveMavenArgs -DskipTests"
}
$MavenArgList = $EffectiveMavenArgs.Split(" ", [System.StringSplitOptions]::RemoveEmptyEntries)
& $MavenBin @MavenArgList package
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Remove-Item -Recurse -Force $PackageDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force `
  $PackageDir, `
  (Join-Path $PackageDir "app"), `
  (Join-Path $PackageDir "bin"), `
  (Join-Path $PackageDir "data"), `
  (Join-Path $PackageDir "logs") | Out-Null

Copy-Item (Join-Path $RootDir "web-api\target\web-api-$Version.jar") (Join-Path $PackageDir "app\fuyue-convert.jar")
Copy-Item (Join-Path $RootDir "deploy\application.yml.example") (Join-Path $PackageDir "application.yml")
Copy-Item (Join-Path $RootDir "README.md") $PackageDir
Copy-Item (Join-Path $RootDir "README_EN.md") $PackageDir
Copy-Item (Join-Path $RootDir "LICENSE") $PackageDir

& $JlinkBin `
  --add-modules java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.security.jgss,java.sql,jdk.crypto.ec,jdk.unsupported `
  --bind-services `
  --strip-debug `
  --no-header-files `
  --no-man-pages `
  --compress=2 `
  --output $RuntimeDir
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

@'
@echo off
setlocal
set APP_HOME=%~dp0..
if "%SERVER_PORT%"=="" set SERVER_PORT=8080
if "%JAVA_OPTS%"=="" set JAVA_OPTS=-Xms256m -Xmx1g -Djava.awt.headless=true
if "%AUTO_OPEN_BROWSER%"=="" set AUTO_OPEN_BROWSER=true
echo Fuyue Convert 正在启动...
echo 浏览器地址: http://127.0.0.1:%SERVER_PORT%
"%APP_HOME%\runtime\bin\java.exe" %JAVA_OPTS% -jar "%APP_HOME%\app\fuyue-convert.jar" --server.port=%SERVER_PORT% --format-converter.auto-open-browser=%AUTO_OPEN_BROWSER% --spring.config.additional-location="%APP_HOME%\application.yml"
pause
'@ | Set-Content -Encoding UTF8 (Join-Path $PackageDir "start.bat")

@'
$AppHome = Resolve-Path (Join-Path $PSScriptRoot ".")
if (-not $env:SERVER_PORT) { $env:SERVER_PORT = "8080" }
if (-not $env:JAVA_OPTS) { $env:JAVA_OPTS = "-Xms256m -Xmx1g -Djava.awt.headless=true" }
if (-not $env:AUTO_OPEN_BROWSER) { $env:AUTO_OPEN_BROWSER = "true" }
Write-Host "Fuyue Convert 正在启动..."
Write-Host "浏览器地址: http://127.0.0.1:$env:SERVER_PORT"
$JavaOptions = $env:JAVA_OPTS -split " "
& "$AppHome\runtime\bin\java.exe" @JavaOptions -jar "$AppHome\app\fuyue-convert.jar" "--server.port=$env:SERVER_PORT" "--format-converter.auto-open-browser=$env:AUTO_OPEN_BROWSER" "--spring.config.additional-location=$AppHome\application.yml"
'@ | Set-Content -Encoding UTF8 (Join-Path $PackageDir "start.ps1")

$ZipPath = Join-Path $DistDir "$PackageName.zip"
Remove-Item -Force $ZipPath -ErrorAction SilentlyContinue
Compress-Archive -Path $PackageDir -DestinationPath $ZipPath

$JpackageCommand = Get-Command $JpackageBin -ErrorAction SilentlyContinue
if ($JpackageCommand) {
  $ExeImageRoot = Join-Path $DistDir "$PackageName-exe"
  $ExeZipPath = Join-Path $DistDir "$PackageName-exe.zip"
  Remove-Item -Recurse -Force $ExeImageRoot -ErrorAction SilentlyContinue
  Remove-Item -Force $ExeZipPath -ErrorAction SilentlyContinue
  New-Item -ItemType Directory -Force $ExeImageRoot | Out-Null

  & $JpackageBin `
    --type app-image `
    --name FuyueConvert `
    --dest $ExeImageRoot `
    --input (Join-Path $PackageDir "app") `
    --main-jar fuyue-convert.jar `
    --main-class org.springframework.boot.loader.launch.JarLauncher `
    --runtime-image $RuntimeDir `
    --app-version $AppVersion `
    --vendor Fuyue `
    --description "Open-source document format conversion platform" `
    --win-console `
    --arguments "--format-converter.auto-open-browser=true" `
    --java-options "-Xms256m" `
    --java-options "-Xmx1g" `
    --java-options "-Djava.awt.headless=true"
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

  Copy-Item (Join-Path $RootDir "README.md") (Join-Path $ExeImageRoot "FuyueConvert")
  Copy-Item (Join-Path $RootDir "README_EN.md") (Join-Path $ExeImageRoot "FuyueConvert")
  Copy-Item (Join-Path $RootDir "LICENSE") (Join-Path $ExeImageRoot "FuyueConvert")
  Compress-Archive -Path (Join-Path $ExeImageRoot "FuyueConvert") -DestinationPath $ExeZipPath
  Write-Host "已生成 $ExeZipPath"
} else {
  Write-Host "未找到 jpackage，已跳过 FuyueConvert.exe 版发布包。"
}
Pop-Location

Write-Host "已生成 $ZipPath"
