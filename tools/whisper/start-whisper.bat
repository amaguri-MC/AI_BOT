@echo off
chcp 65001 >nul
rem whisper.cpp の server を 127.0.0.1:8080 で起動する。
rem このファイルを、whisper-server.exe と同じフォルダに置いてダブルクリック。
rem モデルは同じフォルダの models\ に入れる。GPU なしなら MODEL を ggml-small.bin にする。
set MODEL=models\ggml-large-v3-turbo.bin
rem set MODEL=models\ggml-small.bin

cd /d "%~dp0"
if not exist "%MODEL%" (
  echo モデルが見つかりません: %MODEL%
  echo docs\VOICE.md の手順3を見てください。
  pause
  exit /b 1
)
if exist whisper-server.exe (
  whisper-server.exe -m "%MODEL%" -l ja --host 127.0.0.1 --port 8080
) else if exist server.exe (
  server.exe -m "%MODEL%" -l ja --host 127.0.0.1 --port 8080
) else (
  echo whisper-server.exe が見つかりません。docs\VOICE.md の手順2を見てください。
  pause
  exit /b 1
)
pause
