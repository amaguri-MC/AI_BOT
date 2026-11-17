package com.amaguri.aibuddy.brain;

/** config/aibuddy/config.json の中身。Gson が直接読むので、フィールド初期値が既定値になる。 */
final class BrainSettings {
	String apiKey = "";
	String model = "claude-haiku-5-5";
	/** 旧設定（v0.1）。W4 以降は使わない（config.json の互換のためだけに残す）。 */
	int thinkIntervalSec = 8;
	/** 暇つぶしの独り言を呼ぶ最短間隔（秒）。出来事・チャット・声のときは関係なく呼ぶ。 */
	int idleTalkSec = 45;
	int maxTokens = 400;
	/** 1回の思考から次の思考までの最短間隔（秒）。 */
	int minGapSec = 3;
	int requestTimeoutSec = 30;
	/** 近くのプレイヤーのチャットに反応する半径。 */
	int chatRadius = 64;
	/** 旧設定（v0.1）。W4 以降は履歴を持たないので使わない。 */
	int historyTurns = 12;
	/** 429・タイムアウトなどの後にAPIを休む秒数。 */
	int backoffSec = 30;

	void clamp() {
		if (apiKey == null) apiKey = "";
		if (model == null || model.isBlank()) model = "claude-haiku-5-5";
		thinkIntervalSec = Math.max(1, thinkIntervalSec);
		maxTokens = Math.max(64, Math.min(4096, maxTokens));
		idleTalkSec = Math.max(10, idleTalkSec);
		minGapSec = Math.max(1, minGapSec);
		requestTimeoutSec = Math.max(5, requestTimeoutSec);
		chatRadius = Math.max(1, chatRadius);
		historyTurns = Math.max(1, Math.min(50, historyTurns));
		backoffSec = Math.max(1, backoffSec);
	}

	/** config の apiKey、空なら環境変数 ANTHROPIC_API_KEY。無ければ空文字。 */
	String resolveKey() {
		if (apiKey != null && !apiKey.isBlank()) return apiKey.trim();
		String env = System.getenv("ANTHROPIC_API_KEY");
		return env == null ? "" : env.trim();
	}
}
