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
$Process = $null

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

  if (-not $Jar) { throw "发布包缺少 fuyue-convert.jar" }
  if ($Archive -like "*-exe.zip" -and -not $Exe) {
    throw "exe 发布包缺少 FuyueConvert.exe"
  }
  if ($Archive -notlike "*-exe.zip" -and -not $StartBat) {
    throw "普通 Windows 发布包缺少 start.bat"
  }

  & $Java.FullName -version
  $Listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
  $Listener.Start()
  $Port = ([System.Net.IPEndPoint]$Listener.LocalEndpoint).Port
  $Listener.Stop()
  $LogFile = Join-Path $WorkDir "app.log"
  $ErrorLog = Join-Path $WorkDir "app-error.log"
  $Process = Start-Process -FilePath $Java.FullName -ArgumentList @(
    "-Xms128m", "-Xmx512m", "-jar", $Jar.FullName,
    "--server.port=$Port", "--format-converter.auto-open-browser=false",
    "--format-converter.data-root=$WorkDir\data"
  ) -PassThru -RedirectStandardOutput $LogFile -RedirectStandardError $ErrorLog

  $Healthy = $false
  for ($i = 0; $i -lt 60; $i++) {
    if ($Process.HasExited) { break }
    try {
      $Health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/health" -TimeoutSec 2
      if ($Health.status -eq "UP") { $Healthy = $true; break }
    } catch { }
    Start-Sleep -Seconds 1
  }
  if (-not $Healthy) { throw "Windows 发布包健康检查失败" }
  $OcrDirectory = Join-Path $Jar.Directory.FullName "ocr"
  if ((Test-Path $OcrDirectory) -and -not $Health.ocr.bundled) {
    throw "Windows 发布包内置 OCR 未被应用自动发现"
  }

  $InputFile = Join-Path $WorkDir "worker-smoke.txt"
  $OutputFile = Join-Path $WorkDir "worker-smoke.docx"
  Set-Content -Path $InputFile -Value "runtime worker smoke" -NoNewline -Encoding UTF8
  $CreatedText = & curl.exe -fsS -X POST -F "files=@$InputFile;type=text/plain" -F "targetFormat=docx" "http://127.0.0.1:$Port/api/tasks"
  if ($LASTEXITCODE -ne 0) { throw "Windows 发布包创建转换任务失败" }
  $Created = $CreatedText | ConvertFrom-Json
  $Snapshot = $null
  for ($i = 0; $i -lt 60; $i++) {
    $Snapshot = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/tasks/$($Created.taskId)" -TimeoutSec 3
    if ($Snapshot.status -eq "SUCCESS" -or $Snapshot.status -eq "FAILED") { break }
    Start-Sleep -Seconds 1
  }
  if ($Snapshot.status -ne "SUCCESS") { throw "Windows 发布包 Worker 转换失败: $($Snapshot.errorCode)" }
  Invoke-WebRequest -Uri "http://127.0.0.1:$Port/api/tasks/$($Created.taskId)/download" -OutFile $OutputFile
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $Docx = [System.IO.Compression.ZipFile]::OpenRead($OutputFile)
  try {
    $Entry = $Docx.GetEntry("word/document.xml")
    if (-not $Entry) { throw "Windows 发布包 DOCX 结构无效" }
    $Reader = [System.IO.StreamReader]::new($Entry.Open())
    try { $Xml = $Reader.ReadToEnd() } finally { $Reader.Dispose() }
    if ($Xml -notmatch "runtime worker smoke") { throw "Windows 发布包 DOCX 缺少预期文字" }
  } finally { $Docx.Dispose() }
  Write-Host "Windows 发布包 Worker 转换通过: $Archive"
} finally {
  if ($Process -and -not $Process.HasExited) {
    Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
    $Process.WaitForExit()
  }
  Remove-Item -Recurse -Force $WorkDir -ErrorAction SilentlyContinue
}
