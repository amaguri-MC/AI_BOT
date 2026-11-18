package com.amaguri.aibuddy.entity;

import net.minecraft.util.RandomSource;

/** 溶け具合に応じて台詞を崩す。melt 0.5 未満は無変換、0.5→1.0 で崩れが強くなる。 */
public final class Slur {
	private static final String PLAIN = "かきくけこさしすせそたちつてとはひふへほカキクケコサシスセソタチツテトハヒフヘホ";
	private static final String VOICED = "がぎぐげござじずぜぞだぢづでどばびぶべぼガギグゲゴザジズゼゾダヂヅデドバビブベボ";
	private static final String[] TAILS = {"…", "……", "…ぁ", "…う", "…ぁぁ"};

	/** 水たまりの台詞の最大文字数（「…」込み） */
	static final int FRAGMENT_MAX = 20;

	private Slur() {
	}

	public static String apply(String text, float melt, RandomSource r) {
		if (text == null || text.isEmpty() || melt < 0.5f) {
			return text;
		}
		float p = Math.min(1f, (melt - 0.5f) * 2f); // 0..1
		StringBuilder sb = new StringBuilder(text.length() + 8);
		char prev = 0;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			// 平仮名・片仮名の濁点化
			int idx = PLAIN.indexOf(c);
			if (idx >= 0 && r.nextFloat() < p * 0.15f) {
				c = VOICED.charAt(idx);
			}
			sb.append(c);
			// 文字の伸ばし
			if (isKana(c) && c != 'ー' && prev != 'ー' && r.nextFloat() < p * 0.10f) {
				sb.append(r.nextFloat() < p ? "ーー" : "ー");
				prev = 'ー';
			} else {
				prev = c;
			}
			// 語中のため息
			if (p > 0.5f && i < text.length() - 1 && isKana(c) && r.nextFloat() < (p - 0.5f) * 0.08f) {
				sb.append('…');
			}
		}
		// 語尾の崩れ
		if (r.nextFloat() < 0.2f + p * 0.6f) {
			int end = sb.length();
			while (end > 0 && "。．.!！?？".indexOf(sb.charAt(end - 1)) >= 0) {
				end--;
			}
			sb.setLength(end);
			sb.append(TAILS[r.nextInt(TAILS.length)]);
		}
		return sb.toString();
	}

	/**
	 * 水たまり用: 元の文を15字以内に切り、文字の間に「…」を挟んで途切れ途切れにする。挟んだ後の全体は「…」込みで最大 FRAGMENT_MAX 字。
	 * すでに「…」で区切られていれば、そのまま（二重に崩しすぎない）。崩し(apply)はこの後に say 側でかかる。
	 */
	public static String fragment(String text, RandomSource r) {
		if (text == null) {
			return "";
		}
		String t = text.strip();
		if (t.length() > 15) {
			t = t.substring(0, 15);
		}
		long dots = t.chars().filter(ch -> ch == '…').count();
		if (dots >= 2 || t.length() < 2) {
			return t; // 15字以内なので FRAGMENT_MAX に収まる
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < t.length(); i++) {
			sb.append(t.charAt(i));
			if (i < t.length() - 1 && t.charAt(i) != '…' && t.charAt(i + 1) != '…' && r.nextFloat() < 0.35f) {
				sb.append('…');
			}
		}
		String out = sb.toString();
		if (out.indexOf('…') < 0) {
			// 短い返事でも必ず途切れさせる（真ん中と最後に「…」）
			int mid = Math.max(1, out.length() / 2);
			out = out.substring(0, mid) + "…" + out.substring(mid) + "…";
		}
		if (out.length() > FRAGMENT_MAX) {
			// 「…」を挟んだ後の全体で約20字に収める（最後は「…」で途切れさせる）
			String cut = out.substring(0, FRAGMENT_MAX - 1);
			while (cut.endsWith("…")) {
				cut = cut.substring(0, cut.length() - 1);
			}
			out = cut + "…";
		}
		return out;
	}

	private static boolean isKana(char c) {
		return (c >= 'ぁ' && c <= 'ゖ') || (c >= 'ァ' && c <= 'ヺ') || c == 'ー';
	}
}
