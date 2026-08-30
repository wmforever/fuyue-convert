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
$AppVersion = "0.1.4"
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
Copy-Item (Join-Path $RootDir "THIRD_PARTY_NOTICES.md") $PackageDir
New-Item -ItemType Directory -Force (Join-Path $PackageDir "docs") | Out-Null
Copy-Item (Join-Path $RootDir "docs\known-limitations.md") (Join-Path $PackageDir "docs")
Copy-Item (Join-Path $RootDir "docs\test-report.md") (Join-Path $PackageDir "docs")

$BundleOcr = if ($env:FORMAT_CONVERTER_BUNDLE_OCR) { $env:FORMAT_CONVERTER_BUNDLE_OCR } else { "auto" }
if ($BundleOcr -notin @("false", "0")) {
  $Tesseract = Get-Command tesseract.exe -ErrorAction SilentlyContinue
  if (-not $Tesseract) {
    $Tesseract = Get-ChildItem "$env:ProgramFiles", "${env:ProgramFiles(x86)}" -Recurse -File -Filter tesseract.exe -ErrorAction SilentlyContinue | Select-Object -First 1
  }
  if ($Tesseract) {
    & (Join-Path $RootDir "scripts\prepare-ocr-runtime.ps1") -Destination (Join-Path $PackageDir "app\ocr")
  } elseif ($BundleOcr -in @("true", "1")) {
    throw "要求内置 OCR，但构建机未安装 Tesseract"
  } else {
    Write-Host "构建机未安装 Tesseract，本次运行包不含内置 OCR"
  }
}
New-Item -ItemType Directory -Force (Join-Path $PackageDir "app\docs") | Out-Null
Copy-Item (Join-Path $RootDir "THIRD_PARTY_NOTICES.md") (Join-Path $PackageDir "app")
Copy-Item (Join-Path $RootDir "docs\known-limitations.md") (Join-Path $PackageDir "app\docs")
Copy-Item (Join-Path $RootDir "docs\test-report.md") (Join-Path $PackageDir "app\docs")

Write-Host "运行包不会自动复制构建机上的 Poppler；PDF 保真路线使用 PDFBox 回退或由用户在运行环境中显式配置。"

& $JlinkBin `
  --add-modules java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.security.jgss,java.sql,jdk.crypto.ec,jdk.unsupported `
  --bind-services `
  --strip-debug `
  --no-header-files `
  --no-man-pages `
  --compress=2 `
  --output $RuntimeDir
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

if ($env:FORMAT_CONVERTER_REQUIRE_TEMURIN_RUNTIME -in @("true", "1")) {
  $RuntimeSettings = (& (Join-Path $RuntimeDir "bin\java.exe") -XshowSettings:properties -version 2>&1 | Out-String)
  if ($RuntimeSettings -notmatch "java\.vendor\s*=\s*Eclipse Adoptium") {
    throw "正式发布只允许使用 Eclipse Temurin/Adoptium Java Runtime"
  }
}

@'
@echo off
setlocal
set APP_HOME=%~dp0..
if "%SERVER_PORT%"=="" set SERVER_PORT=8080
if "%JAVA_OPTS%"=="" set JAVA_OPTS=-Xms256m -Xmx1g -Djava.awt.headless=true
if "%AUTO_OPEN_BROWSER%"=="" set AUTO_OPEN_BROWSER=true
set "FORMAT_CONVERTER_APP_HOME=%APP_HOME%\app"
if exist "%APP_HOME%\app\poppler\bin\pdftoppm.exe" set "PDFTOPPM_BIN=%APP_HOME%\app\poppler\bin\pdftoppm.exe"
echo Fuyue Convert 正在启动...
echo 浏览器地址: http://127.0.0.1:%SERVER_PORT%
"%APP_HOME%\runtime\bin\java.exe" %JAVA_OPTS% -Dformat.converter.app.home="%APP_HOME%" -jar "%APP_HOME%\app\fuyue-convert.jar" --server.port=%SERVER_PORT% --format-converter.auto-open-browser=%AUTO_OPEN_BROWSER% --spring.config.additional-location="%APP_HOME%\application.yml"
'@ | Set-Content -Encoding UTF8 (Join-Path $PackageDir "start.bat")

@'
Set WshShell = CreateObject("WScript.Shell")
command = """" & Replace(WScript.ScriptFullName, "start.vbs", "start.bat") & """"
WshShell.Run command, 0, False
'@ | Set-Content -Encoding ASCII (Join-Path $PackageDir "start.vbs")

@'
$AppHome = Resolve-Path (Join-Path $PSScriptRoot ".")
if (-not $env:SERVER_PORT) { $env:SERVER_PORT = "8080" }
if (-not $env:JAVA_OPTS) { $env:JAVA_OPTS = "-Xms256m -Xmx1g -Djava.awt.headless=true" }
if (-not $env:AUTO_OPEN_BROWSER) { $env:AUTO_OPEN_BROWSER = "true" }
$env:FORMAT_CONVERTER_APP_HOME = Join-Path $AppHome "app"
if (-not $env:PDFTOPPM_BIN) {
  $BundledPdftoppm = Join-Path $AppHome "app\poppler\bin\pdftoppm.exe"
  if (Test-Path $BundledPdftoppm) { $env:PDFTOPPM_BIN = $BundledPdftoppm }
}
Write-Host "Fuyue Convert 正在启动..."
Write-Host "浏览器地址: http://127.0.0.1:$env:SERVER_PORT"
$JavaOptions = $env:JAVA_OPTS -split " "
& "$AppHome\runtime\bin\java.exe" @JavaOptions "-Dformat.converter.app.home=$AppHome" -jar "$AppHome\app\fuyue-convert.jar" "--server.port=$env:SERVER_PORT" "--format-converter.auto-open-browser=$env:AUTO_OPEN_BROWSER" "--spring.config.additional-location=$AppHome\application.yml"
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
    --arguments '--format-converter.auto-open-browser=true --format-converter.data-root=${user.home}/FuyueConvert/data' `
    --java-options "-Xms256m" `
    --java-options "-Xmx1g" `
    --java-options "-Djava.awt.headless=true"
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

  Copy-Item (Join-Path $RootDir "README.md") (Join-Path $ExeImageRoot "FuyueConvert")
  Copy-Item (Join-Path $RootDir "README_EN.md") (Join-Path $ExeImageRoot "FuyueConvert")
  Copy-Item (Join-Path $RootDir "LICENSE") (Join-Path $ExeImageRoot "FuyueConvert")
  Copy-Item (Join-Path $RootDir "THIRD_PARTY_NOTICES.md") (Join-Path $ExeImageRoot "FuyueConvert")
  New-Item -ItemType Directory -Force (Join-Path $ExeImageRoot "FuyueConvert\docs") | Out-Null
  Copy-Item (Join-Path $RootDir "docs\known-limitations.md") (Join-Path $ExeImageRoot "FuyueConvert\docs")
  Copy-Item (Join-Path $RootDir "docs\test-report.md") (Join-Path $ExeImageRoot "FuyueConvert\docs")
  Compress-Archive -Path (Join-Path $ExeImageRoot "FuyueConvert") -DestinationPath $ExeZipPath
  Write-Host "已生成 $ExeZipPath"

  foreach ($InstallerType in @("exe", "msi")) {
    $InstallerPath = Join-Path $DistDir "FuyueConvert-$AppVersion.$InstallerType"
    Remove-Item -Force $InstallerPath -ErrorAction SilentlyContinue
    & $JpackageBin `
      --type $InstallerType `
      --name FuyueConvert `
      --dest $DistDir `
      --input (Join-Path $PackageDir "app") `
      --main-jar fuyue-convert.jar `
      --main-class org.springframework.boot.loader.launch.JarLauncher `
      --runtime-image $RuntimeDir `
      --app-version $AppVersion `
      --vendor Fuyue `
      --description "Open-source document format conversion platform" `
      --license-file (Join-Path $RootDir "LICENSE") `
      --win-menu `
      --win-menu-group "Fuyue Convert" `
      --win-shortcut `
      --win-dir-chooser `
      --win-per-user-install `
      --win-upgrade-uuid "3f1df18a-09de-4f90-a1d8-273df230f38b" `
      --arguments '--format-converter.auto-open-browser=true --format-converter.data-root=${user.home}/FuyueConvert/data' `
      --java-options "-Xms256m" `
      --java-options "-Xmx1g" `
      --java-options "-Djava.awt.headless=true"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    if (-not (Test-Path $InstallerPath)) { throw "jpackage 未生成预期安装器: $InstallerPath" }
    Write-Host "已生成 $InstallerPath"
  }
} else {
  throw "未找到 jpackage，无法生成 Windows EXE/MSI 发布包"
}
Pop-Location

Write-Host "已生成 $ZipPath"
