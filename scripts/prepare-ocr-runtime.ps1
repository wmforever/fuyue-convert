param(
  [Parameter(Mandatory = $true)]
  [string]$Destination
)
$ErrorActionPreference = "Stop"
$RootDir = Resolve-Path (Join-Path $PSScriptRoot "..")
& node (Join-Path $RootDir "desktop\scripts\prepare-ocr-runtime.mjs") $Destination
if ($LASTEXITCODE -ne 0) { throw "内置 OCR 构建或真实识别自检失败: $LASTEXITCODE" }
