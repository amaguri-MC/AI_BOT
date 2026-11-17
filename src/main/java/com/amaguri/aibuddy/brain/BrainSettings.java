package com.amaguri.aibuddy.brain;

/** config/aibuddy/config.json の中身。Gson が直接読むので、フィールド初期値が既定値になる。 */
final class BrainSettings {
	String apiKey = "";
	String model = "claude-haiku-5-5";
	int thinkIntervalSec = 8;
	int maxTokens = 400;
	/** 1回の思考から次の思考までの最短間隔（秒）。 */
	int minGapSec = 3;
	int requestTimeoutSec = 30;
	/** 近くのプレイヤーのチャットに反応する半径。 */
	int chatRadius = 64;
	/** 保持する往復数。 */
	int historyTurns = 12;
	/** 429・タイムアウトなどの後にAPIを休む秒数。 */
	int backoffSec = 30;

	void clamp() {
		if (apiKey == null) apiKey = "";
		if (model == null || model.isBlank()) model = "claude-haiku-5-5";
		thinkIntervalSec = Math.max(1, thinkIntervalSec);
		maxTokens = Math.max(64, Math.min(4096, maxTokens));
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
