# AI Buddy 用 Whisper 自動セットアップ＆起動
# 1回目: whisper.cpp と日本語モデルを自動でダウンロードして起動
# 2回目以降: すぐ起動
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$ProgressPreference = 'SilentlyContinue'
$root = Join-Path $env:USERPROFILE 'aibuddy-whisper'
$bin  = Join-Path $root 'bin'
$models = Join-Path $root 'models'
New-Item -ItemType Directory -Force -Path $bin, $models | Out-Null

function Say($t) { Write-Host ""; Write-Host "==> $t" -ForegroundColor Cyan }

# GPU（NVIDIA）があるか
$hasNvidia = $false
try { if (Get-Command nvidia-smi -ErrorAction SilentlyContinue) { nvidia-smi -L | Out-Null; if ($LASTEXITCODE -eq 0) { $hasNvidia = $true } } } catch {}

# 1. whisper.cpp 本体
$server = Get-ChildItem -Path $bin -Recurse -Include 'whisper-server.exe','server.exe' -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $server) {
  Say "whisper.cpp の最新版を探しています..."
  # 最新版には Windows 用 zip が無いことがあるので、zip が付いている一番新しい版を探す
  $zips = @()
  try {
    $rels = Invoke-RestMethod -Uri 'https://api.github.com/repos/ggml-org/whisper.cpp/releases?per_page=15' -Headers @{ 'User-Agent' = 'aibuddy' }
    foreach ($r in $rels) {
      $z = $r.assets | Where-Object { $_.name -like '*.zip' -and $_.name -match 'x64' -and $_.name -notmatch 'xcframework|arm' }
      if ($z) { $zips = @($z); Write-Host ("  使う版: " + $r.tag_name); break }
    }
  } catch { Write-Host "  GitHub の一覧が取れなかったので、固定の版 v1.8.2 を使います" }
  $pick = $null
  if ($zips.Count -gt 0) {
    if ($hasNvidia) { $pick = $zips | Where-Object { $_.name -match 'cublas|cuda' } | Sort-Object name -Descending | Select-Object -First 1 }
    if (-not $pick) { $pick = $zips | Where-Object { $_.name -match '^whisper-bin-x64' } | Select-Object -First 1 }
    if (-not $pick) { $pick = $zips | Where-Object { $_.name -notmatch 'cublas|cuda' } | Select-Object -First 1 }
  }
  if (-not $pick) {
    $fixed = if ($hasNvidia) { 'whisper-cublas-12.4.0-bin-x64.zip' } else { 'whisper-bin-x64.zip' }
    $pick = [pscustomobject]@{ name = $fixed; size = 0; browser_download_url = "https://github.com/ggml-org/whisper.cpp/releases/download/v1.8.2/$fixed" }
  }
  if (-not $pick) { throw "Windows 版の zip が見つかりませんでした。司令塔に「whisper の zip が見つからない」と伝えてください。" }
  Say ("ダウンロード中: " + $pick.name)
  $zip = Join-Path $root $pick.name
  Invoke-WebRequest -Uri $pick.browser_download_url -OutFile $zip
  Expand-Archive -Path $zip -DestinationPath $bin -Force
  Remove-Item $zip
  $server = Get-ChildItem -Path $bin -Recurse -Include 'whisper-server.exe','server.exe' | Select-Object -First 1
  if (-not $server) { throw "zip の中に whisper-server.exe がありませんでした。" }
}

# 2. 日本語モデル（GPU あり=large-v3-turbo 約1.6GB / なし=small 約0.5GB）
$modelName = if ($hasNvidia) { 'ggml-large-v3-turbo.bin' } else { 'ggml-small.bin' }
$modelPath = Join-Path $models $modelName
if (-not (Test-Path $modelPath)) {
  Say "音声認識モデル $modelName をダウンロード中（数分かかります。閉じないでください）..."
  $tmp = "$modelPath.part"
  Invoke-WebRequest -Uri "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$modelName" -OutFile $tmp
  Move-Item $tmp $modelPath -Force
}

# 3. 起動
Say "Whisper を起動します。この黒い画面は撮影中ずっと開いたままにしてください（閉じると声が届きません）"
Write-Host ("  GPU: " + $(if ($hasNvidia) {'あり'} else {'なし'}) + " / モデル: $modelName")
Set-Location $server.DirectoryName
& $server.FullName -m $modelPath -l ja --host 127.0.0.1 --port 8080
