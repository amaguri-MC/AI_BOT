# BRAIN（Claude API・ペルソナ・会話ログ）

## ファイルの置き場所（ゲームの実行フォルダ基準）
| 場所 | 内容 |
|---|---|
| `config/aibuddy/config.json` | APIキーやモデルなどの設定（無ければ初回起動で作られる） |
| `config/aibuddy/personas/*.json` | 人格。1ファイル1人 |
| `config/aibuddy/prompt_common.txt` | 全員共通のルール文（無ければ既定文が書き出される） |
| `logs/aibuddy/<personaId>-<日付>.jsonl` | 会話ログ（調整用） |

サンプルは `config-example/aibuddy/` にある。コピーして使う。

## config.json
| キー | 既定 | 意味 |
|---|---|---|
| `apiKey` | `""` | 空なら環境変数 `ANTHROPIC_API_KEY`。**リポジトリにコミットしない** |
| `model` | `claude-haiku-5-5` | 使うモデル |
| `thinkIntervalSec` | 8 | 何も起きなくても考える間隔（スキルが暇のとき） |
| `maxTokens` | 400 | 1回の返答の上限 |
| `minGapSec` | 3 | 連続して考える最短間隔 |
| `requestTimeoutSec` | 30 | APIのタイムアウト |
| `chatRadius` | 64 | この半径内のチャットに反応する（名前を呼ばれれば距離に関係なく反応） |
| `historyTurns` | 12 | 覚えておく往復数 |
| `backoffSec` | 30 | 429・タイムアウト後にAPIを休む秒数（401/403/400は300秒以上） |

## ペルソナ（personas/*.json）
```json
{
  "id": "example",
  "displayName": "ぽん太",
  "skin": "スキンを借りるMCID",
  "systemPrompt": ["行ごとの配列でも", "1つの文字列でもよい"],
  "fallbackLines": ["APIが使えないときや水たまり中に言う定型文"]
}
```
`id` を省略するとファイル名になる。`/aibuddy spawn <id>` で呼び出せる。

## 動き方
- 考えるきっかけ: 出来事がある / 近くでチャットされた・名前を呼ばれた / `thinkIntervalSec` 経過かつスキルが暇。1体につき同時に1リクエストまで。
- ツールの結果は次の思考のときにまとめて `tool_result` で返す。
- 水たまり中はAPIを呼ばず、たまに定型文か「……」だけ言う。
- キー無し・429・タイムアウトのときはログに警告を出し、定型文を時々言う。
- `/aibuddy reload` で設定・人格・共通ルールを読み直す（動作中のAIは次の思考から反映）。

## ログの見方
1行1JSON。主な項目:
- `trigger`: `event` / `chat` / `interval`
- `input`: そのとき渡した「状況・今やっていること・出来事・チャット」
- `text` / `tools`: モデルの返答とツール呼び出し（`result` は実行結果）
- `usage`: トークン数（`input_tokens` / `output_tokens`）、`latencyMs`: 所要時間
- `error` / `status`: API失敗、`fallback`: 定型文を言った

例: 発言だけ一覧にする
```
jq -r 'select(.tools) | .tools[] | select(.name=="say") | .input.text' logs/aibuddy/example-*.jsonl
```

## 調整のコツ
- まず `prompt_common.txt` ではなく人格ファイルの口調・性格を直す。共通ルールは全員に効く。
- 長くしゃべる → 共通ルールの「40字以内」を強める。しゃべりすぎ → 「毎回しゃべらない」を強め、`thinkIntervalSec` を伸ばす。
- ログの `input` を見て、モデルに渡った情報が足りているか確認する。誤動作の多くは情報不足。
- 費用は `usage` の合計で見る。`thinkIntervalSec` と `historyTurns` を下げると減る。
