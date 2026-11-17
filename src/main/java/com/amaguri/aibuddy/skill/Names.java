package com.amaguri.aibuddy.skill;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/** 日本語の短い呼び名。無いものはIDのパスをそのまま使う。 */
final class Names {
	private static final Map<String, String> JP = new HashMap<>();

	static {
		String[] kv = {
			"diamond", "ダイヤ", "iron_ingot", "鉄", "raw_iron", "生鉄", "gold_ingot", "金", "raw_gold", "生金",
			"coal", "石炭", "charcoal", "木炭", "copper_ingot", "銅", "raw_copper", "生銅", "emerald", "エメラルド",
			"lapis_lazuli", "ラピス", "redstone", "レッドストーン", "quartz", "クォーツ", "netherite_scrap", "ネザライトの欠片",
			"ancient_debris", "古代の残骸", "obsidian", "黒曜石", "cobblestone", "丸石", "stone", "石", "dirt", "土",
			"gravel", "砂利", "sand", "砂", "netherrack", "ネザーラック", "blaze_rod", "ブレイズロッド",
			"ender_pearl", "エンダーパール", "ender_eye", "エンダーアイ", "eye_of_ender", "エンダーアイ",
			"oak_log", "オークの原木", "birch_log", "シラカバの原木", "spruce_log", "トウヒの原木", "jungle_log", "ジャングルの原木",
			"acacia_log", "アカシアの原木", "dark_oak_log", "ダークオークの原木", "mangrove_log", "マングローブの原木", "cherry_log", "サクラの原木",
			"oak_planks", "オークの板材", "stick", "棒", "crafting_table", "作業台", "furnace", "かまど", "torch", "松明",
			"bread", "パン", "cooked_beef", "ステーキ", "cooked_porkchop", "焼き豚", "cooked_chicken", "焼き鳥", "apple", "リンゴ",
			"golden_apple", "金のリンゴ", "carrot", "ニンジン", "potato", "ジャガイモ", "baked_potato", "ベイクドポテト",
			"beef", "生牛肉", "porkchop", "生豚肉", "chicken", "生鶏肉", "rotten_flesh", "腐った肉", "bone", "骨", "string", "糸",
			"gunpowder", "火薬", "flint", "火打石", "flint_and_steel", "火打ち石と打ち金", "water_bucket", "水入りバケツ", "lava_bucket", "溶岩入りバケツ", "bucket", "バケツ",
			"diamond_pickaxe", "ダイヤのツルハシ", "iron_pickaxe", "鉄のツルハシ", "stone_pickaxe", "石のツルハシ", "wooden_pickaxe", "木のツルハシ",
			"diamond_sword", "ダイヤの剣", "iron_sword", "鉄の剣", "stone_sword", "石の剣", "wooden_sword", "木の剣",
			"bow", "弓", "arrow", "矢", "shield", "盾", "takoyaki", "たこ焼き", "tako_ashi", "タコの足",
			"diamond_ore", "ダイヤ鉱石", "deepslate_diamond_ore", "深層ダイヤ鉱石", "iron_ore", "鉄鉱石", "deepslate_iron_ore", "深層鉄鉱石",
			"gold_ore", "金鉱石", "deepslate_gold_ore", "深層金鉱石", "coal_ore", "石炭鉱石", "deepslate_coal_ore", "深層石炭鉱石",
			"copper_ore", "銅鉱石", "deepslate_copper_ore", "深層銅鉱石", "lapis_ore", "ラピス鉱石", "deepslate_lapis_ore", "深層ラピス鉱石",
			"redstone_ore", "レッドストーン鉱石", "deepslate_redstone_ore", "深層レッドストーン鉱石", "emerald_ore", "エメラルド鉱石",
			"deepslate_emerald_ore", "深層エメラルド鉱石", "nether_gold_ore", "ネザー金鉱石", "nether_quartz_ore", "ネザークォーツ鉱石",
			"zombie", "ゾンビ", "skeleton", "スケルトン", "creeper", "クリーパー", "spider", "クモ", "cave_spider", "洞窟グモ", "enderman", "エンダーマン",
			"witch", "ウィッチ", "slime", "スライム", "husk", "ハスク", "drowned", "ドラウンド", "stray", "ストレイ", "phantom", "ファントム",
			"blaze", "ブレイズ", "ghast", "ガスト", "magma_cube", "マグマキューブ", "wither_skeleton", "ウィザースケルトン",
			"piglin", "ピグリン", "piglin_brute", "ピグリンブルート", "zombified_piglin", "ゾンビピグリン", "hoglin", "ホグリン",
			"pillager", "ピリジャー", "vindicator", "ヴィンディケーター", "silverfish", "シルバーフィッシュ", "endermite", "エンダーマイト",
			"cow", "ウシ", "pig", "ブタ", "sheep", "ヒツジ", "villager", "村人", "iron_golem", "アイアンゴーレム",
			"item", "アイテム"
		};
		for (int i = 0; i + 1 < kv.length; i += 2) {
			JP.put(kv[i], kv[i + 1]);
		}
	}

	private Names() {
	}

	static String raw(String idPath, String fallback) {
		String v = JP.get(idPath);
		return v != null ? v : fallback;
	}

	static String item(Item item) {
		String path = BuiltInRegistries.ITEM.getKey(item).getPath();
		return raw(path, path);
	}

	static String block(Block block) {
		String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
		return raw(path, path);
	}

	static String entity(EntityType<?> type) {
		String path = BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
		if (path.equals("chicken")) {
			return "ニワトリ";
		}
		return raw(path, path);
	}
}
