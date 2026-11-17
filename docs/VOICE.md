# ボイスチャットで話しかける

Simple Voice Chat（SVC）で話した声を、あなたの PC の Whisper で文字にして、近くのAIボットに「チャットで聞いた」のと同じように届けます。返事はチャットに出ます。

## 仕組み
1. SVC のマイク音声をサーバーで受け取る（近く64ブロック以内にAIボットが居る人だけ）
2. 600ms 黙ったら1回の発話として区切る（0.4秒未満は捨てる／20秒で強制区切り）
3. 16kHz の WAV にして Whisper サーバー（既定 `http://127.0.0.1:8080/inference`）へ送る
4. 文字になったら `[voice] 名前: 文字` をログに出し、AIボットに渡す

Whisper が動くのは、マイクを使う人（サーバーを動かす人）の PC です。

## 準備（Windows）
1. Minecraft に Simple Voice Chat を入れる（サーバー側。参加する人も入れる）。
2. `tools/whisper/` の `whisper-start.bat` と `setup-and-start.ps1` を同じフォルダに置いて、`whisper-start.bat` をダブルクリックする。
3. 1回目は whisper.cpp と日本語モデルを自動でダウンロードする（GPU ありは約1.6GB、なしは約0.5GB。数分かかる）。入る場所は `ユーザーフォルダ\aibuddy-whisper`。2回目からはすぐ起動する。
4. 「Windows によって PC が保護されました」と出たら「詳細情報」→「実行」。
5. 黒い画面に `listening` のような表示が出ればOK。この画面は開いたままにする。
6. Minecraft を起動して、AIボットのそばでマイクに向かって話す。ログに `[voice] 名前: 文字` が出れば成功。

## 設定 `config/aibuddy/voice.json`
初回起動で自動作成されます。
```json
{
  "enabled": true,
  "whisperUrl": "http://127.0.0.1:8080/inference",
  "language": "ja",
  "radius": 64.0
}
```
- `enabled`: false で無効
- `whisperUrl`: Whisper サーバーの場所
- `radius`: この距離内にAIボットが居る人の声だけ処理する

## うまくいかないとき
- ログに「Whisper に繋げない」: 手順5の黒い画面が開いているか確認。
- 何も出ない: SVC が入っているか、AIボットのそばで話しているか確認。
- 遅い: GPU なしなら `ggml-small.bin` か `ggml-base.bin` にする。
- 「ご視聴ありがとうございました」のような Whisper の定番の幻聴は自動で捨てます。
