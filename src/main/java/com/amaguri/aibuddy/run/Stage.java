package com.amaguri.aibuddy.run;

import java.util.Locale;

/**
 * エンドラ討伐までの段階。ffSeconds は早送りのときの標準時間（合計720秒＝約12分）。
 * 数字は上手いスピードランナーの区間タイムを目安にした。
 */
public enum Stage {
	// 序盤はルザク流（非効率なことをしない上級者）の手順に固定: 原木3→作業台→木のツルハシ→丸石は最低限→石ツルハシ・石斧→原木を追加。
	WOOD("wood", "原木集め", 15),
	TABLE("table", "作業台", 5),
	WOODEN_PICK("wooden_pick", "木のツルハシ", 5),
	STONE("stone", "石ツルハシと石斧", 20),
	MORE_WOOD("more_wood", "原木の追加", 15),
	// 鉄は掘らずに探索で拾う（沈没船→宝の地図→埋もれた宝 / 村 / 廃ポータル）。揃わない時だけ見えている鉄鉱石を少し掘る
	LOOT("loot", "探索で鉄集め", 100),
	FOOD("food", "食料集め", 30),
	PORTAL("portal", "ネザーゲート", 60),
	BLAZE("blaze", "ブレイズロッド集め", 120),
	PEARLS("pearls", "エンダーパール集め", 120),
	EYES("eyes", "エンダーアイ作り", 10),
	STRONGHOLD("stronghold", "要塞探し", 90),
	FILL_PORTAL("fill_portal", "エンドポータル起動", 20),
	DRAGON("dragon", "エンドラ戦", 110),
	DONE("done", "完走", 0);

	public final String id;
	public final String label;
	public final int ffSeconds;

	Stage(String id, String label, int ffSeconds) {
		this.id = id;
		this.label = label;
		this.ffSeconds = ffSeconds;
	}

	public Stage next() {
		return this == DONE ? DONE : values()[ordinal() + 1];
	}

	/** id（wood など）か名前（WOOD）か日本語ラベルから。無ければ null。 */
	public static Stage byId(String s) {
		if (s == null) {
			return null;
		}
		String k = s.trim().toLowerCase(Locale.ROOT);
		if (k.equals("iron")) {
			return LOOT; // v0.2 の保存データ（鉄の段階）は探索の段階として読む
		}
		for (Stage st : values()) {
			if (st.id.equals(k) || st.name().toLowerCase(Locale.ROOT).equals(k) || st.label.equals(s.trim())) {
				return st;
			}
		}
		return null;
	}

	public static int totalFfSeconds() {
		int t = 0;
		for (Stage s : values()) {
			t += s.ffSeconds;
		}
		return t;
	}
}
