param(
  [Parameter(Mandatory = $true)]
  [string]$Archive
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $Archive)) {
  throw "发布包不存在: $Archive"
}

$WorkDir = Join-Path ([System.IO.Path]::GetTempPath()) ("fuyue-convert-smoke-" + [System.Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force $WorkDir | Out-Null

try {
  Expand-Archive -Path $Archive -DestinationPath $WorkDir -Force
  $Java = Get-ChildItem -Path $WorkDir -Recurse -File -Filter "java.exe" |
    Where-Object { $_.FullName -match "\\runtime\\bin\\java\.exe$" } |
    Select-Object -First 1
  if (-not $Java) {
    throw "发布包缺少内置 Java Runtime"
  }

  $Jar = Get-ChildItem -Path $WorkDir -Recurse -File -Filter "fuyue-convert.jar" | Select-Object -First 1
  $StartBat = Get-ChildItem -Path $WorkDir -Recurse -File -Filter "start.bat" | Select-Object -First 1
  $Exe = Get-ChildItem -Path $WorkDir -Recurse -File -Filter "FuyueConvert.exe" | Select-Object -First 1

  if (-not $Jar -and -not $Exe) {
    throw "发布包缺少应用入口"
  }
  if ($Archive -like "*-exe.zip" -and -not $Exe) {
    throw "exe 发布包缺少 FuyueConvert.exe"
  }
  if ($Archive -notlike "*-exe.zip" -and -not $StartBat) {
    throw "普通 Windows 发布包缺少 start.bat"
  }

  & $Java.FullName -version
  Write-Host "Windows 发布包结构检查通过: $Archive"
} finally {
  Remove-Item -Recurse -Force $WorkDir -ErrorAction SilentlyContinue
}
