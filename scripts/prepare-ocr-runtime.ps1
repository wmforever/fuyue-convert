param(
  [Parameter(Mandatory = $true)]
  [string]$Destination
)

$ErrorActionPreference = "Stop"
$Tesseract = $null
if ($env:FORMAT_CONVERTER_TESSERACT_BINARY -and (Test-Path $env:FORMAT_CONVERTER_TESSERACT_BINARY)) {
  $Tesseract = Get-Item $env:FORMAT_CONVERTER_TESSERACT_BINARY
} else {
  $Command = Get-Command tesseract.exe -ErrorAction SilentlyContinue
  if ($Command) { $Tesseract = Get-Item $Command.Source }
}
if (-not $Tesseract) {
  $Tesseract = Get-ChildItem "$env:ProgramFiles", "${env:ProgramFiles(x86)}" -Recurse -File -Filter tesseract.exe -ErrorAction SilentlyContinue |
    Select-Object -First 1
}
if (-not $Tesseract) { throw "未找到 Tesseract，无法生成内置 OCR 运行时" }

$InstallDir = $Tesseract.Directory.FullName
$TessdataSource = if ($env:FORMAT_CONVERTER_TESSDATA_SOURCE) {
  $env:FORMAT_CONVERTER_TESSDATA_SOURCE
} else {
  Join-Path $InstallDir "tessdata"
}
if (-not (Test-Path $TessdataSource -PathType Container)) {
  throw "无法定位 tessdata；请设置 FORMAT_CONVERTER_TESSDATA_SOURCE"
}

$BinDir = Join-Path $Destination "bin"
$LibDir = Join-Path $Destination "lib"
$DataDir = Join-Path $Destination "tessdata"
New-Item -ItemType Directory -Force $BinDir, $LibDir, $DataDir | Out-Null
Get-ChildItem $InstallDir -File | Where-Object { $_.Extension -in @(".exe", ".dll") } |
  Copy-Item -Destination $BinDir -Force

$Languages = if ($env:FORMAT_CONVERTER_BUNDLED_OCR_LANGUAGES) {
  $env:FORMAT_CONVERTER_BUNDLED_OCR_LANGUAGES -split "[+,; ]+"
} else {
  @("eng", "chi_sim", "chi_sim_vert")
}
foreach ($Language in $Languages) {
  if (-not $Language) { continue }
  $Model = Join-Path $TessdataSource "$Language.traineddata"
  if (-not (Test-Path $Model -PathType Leaf)) { throw "缺少内置 OCR 语言模型: $Model" }
  Copy-Item $Model $DataDir -Force
}
$Osd = Join-Path $TessdataSource "osd.traineddata"
if (Test-Path $Osd) { Copy-Item $Osd $DataDir -Force }
foreach ($Folder in @("configs", "tessconfigs")) {
  $Source = Join-Path $TessdataSource $Folder
  if (Test-Path $Source -PathType Container) { Copy-Item $Source $DataDir -Recurse -Force }
}
$TsvConfig = Join-Path $DataDir "configs\tsv"
if (-not (Test-Path $TsvConfig -PathType Leaf)) { throw "内置 OCR 缺少 TSV 输出配置" }

$OldPath = $env:PATH
$OldTessdata = $env:TESSDATA_PREFIX
try {
  $env:PATH = "$BinDir;$LibDir;$OldPath"
  $env:TESSDATA_PREFIX = $DataDir
  $Output = & (Join-Path $BinDir "tesseract.exe") --list-langs --tessdata-dir $DataDir 2>&1
  if ($LASTEXITCODE -ne 0) { throw "内置 OCR --list-langs 自检失败" }
  foreach ($Language in $Languages) {
    if ($Language -and $Output -notcontains $Language) { throw "内置 OCR 自检未发现语言模型: $Language" }
  }
  & (Join-Path $BinDir "tesseract.exe") --version | Select-Object -First 1
} finally {
  $env:PATH = $OldPath
  $env:TESSDATA_PREFIX = $OldTessdata
}
Write-Host "已生成内置 OCR 运行时: $Destination"
