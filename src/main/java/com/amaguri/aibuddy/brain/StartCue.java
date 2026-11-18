package com.amaguri.aibuddy.brain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 「スタートの合図」かどうかの判定（Minecraft に依存しないので javac だけで検査できる）。
 * 検査: javac -d /tmp/sc StartCue.java && java -ea -cp /tmp/sc com.amaguri.aibuddy.brain.StartCue
 * 迷う言い回しは false にして、LLM の start_run に任せる（誤爆より取りこぼしのほうが安い）。
 */
final class StartCue {
	private StartCue() {
	}

	/** 打ち消し・保留。これを含む発話は合図にしない。 */
	private static final Pattern NEGATION = Pattern.compile(
			"しないで|ないで|始めない|はじめない|まだ|待って|待て|ちょっと待|ちょい待|待ち|やめ|止ま|とまれ|ストップ|だめ|駄目|ません|じゃない|"
					+ "don'?t|not|wait|stop|later|\\bno\\b");

	private static final String WORDS = "スタート|すたーと|開始|始めよう|はじめよう|始めるぞ|はじめるぞ|始めて|始めろ|いくぞ|行くぞ|よーいどん|よーいドン|よーい、?どん|\\bstart\\b|\\bsuta-?to\\b|\\bsutaato\\b";
	/** 文末が合図の語＋ごく軽い語尾だけで終わる。 */
	private static final Pattern TAIL = Pattern.compile(
			"(" + WORDS + ")(しよう|しよっか|しよ|します|する(ぞ|よ)?|して(くれ|ください)?|か|だ|な|ね|よ|ぞ|ー|~|〜|!|\\b)*$");
	/** 長い文: 文頭が合図、または「〜しよう/〜するぞ」の形。 */
	private static final Pattern HEAD = Pattern.compile(
			"^(よし|よーし|じゃあ|それじゃ|では|さあ|さて|ok|おーけー|オーケー)?[、, ]*(" + WORDS + ")");
	private static final Pattern FORM = Pattern.compile(
			"(スタートしよう|スタートするぞ|始めよう|はじめよう|始めるぞ|はじめるぞ|行くぞ|いくぞ|よーいどん|let'?s start)!*$");

	static boolean isStartCue(String text) {
		if (text == null) return false;
		String t = normalize(text);
		if (t.isEmpty() || NEGATION.matcher(t).find()) return false;
		for (String sentence : t.split("[。.!?\\n]+")) {
			String s = sentence.strip();
			if (s.isEmpty()) continue;
			if (s.length() <= 20) {
				if (TAIL.matcher(s).find()) return true;
			} else if (HEAD.matcher(s).find() || FORM.matcher(s).find()) {
				return true;
			}
		}
		return false;
	}

	/** 小文字化・全角記号の半角化・文末記号の整理。「！」「？」は区切りとして残す。 */
	private static String normalize(String text) {
		String t = text.toLowerCase(Locale.ROOT).strip()
				.replace('！', '!').replace('？', '?').replace('，', ',').replace('、', ',')
				.replace("’", "'");
		return t.replace(",", "、");
	}

	public static void main(String[] args) {
		String[] pos = { "スタート", "スタートしよう", "よし、始めよう", "いくぞ！", "start", "let's start", "よーいどん", "はじめよう！",
				"じゃあ開始", "それじゃ、いくぞ。頼んだ", "スタートしようか", "よし、スタートするか", "suta-to", "sutato" };
		String[] neg = { "スタートはまだしないで", "ちょっと待ってスタートは後で", "はじめまして", "初めて見た", "restart したい", "スタート地点どこ？",
				"はじめて作ったやつだよ", "開始位置を教えて", "don't start yet", "まだ始めよう思ってないよ、待って",
				"今日は家の近くでスタートする予定の配信で、さっき話した件なんだけど" };
		for (String p : pos) assert isStartCue(p) : "取りこぼし: " + p;
		for (String n : neg) assert !isStartCue(n) : "誤爆: " + n;
		System.out.println("OK pos=" + pos.length + " neg=" + neg.length);
	}
}
