package com.amaguri.aibuddy.skill;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 手書きのレシピ表と精錬表。RecipeManager は使わない（取り出しAPIの不確かさを避けるため）。
 * 材料は「アイテム」か「タグ」で指定する。
 */
final class Recipes {
	private Recipes() {
	}

	/** 材料1種。item か tag のどちらか。 */
	record Ing(Item item, TagKey<Item> tag, int n, String label) {
		int have(com.amaguri.aibuddy.entity.FakeMember o) {
			return item != null ? Inv.count(o, item) : Inv.count(o, tag);
		}

		boolean take(com.amaguri.aibuddy.entity.FakeMember o, int times) {
			return item != null ? Inv.remove(o, item, n * times) : Inv.remove(o, tag, n * times);
		}
	}

	record Recipe(Item out, int outCount, boolean table, List<Ing> ings) {
	}

	/** 精錬: 入力 → 出力。 */
	record Smelt(Item in, Item out) {
	}

	private static final List<Recipe> ALL = new ArrayList<>();
	private static final List<Smelt> SMELTS = new ArrayList<>();
	private static final Map<String, String> JP_KIND = new HashMap<>();
	private static final Map<String, String> JP_MAT = new HashMap<>();
	private static final Map<String, String> ALIAS = new HashMap<>();

	static Ing of(Item i, int n) {
		return new Ing(i, null, n, label(i));
	}

	/** 材料不足メッセージ用の呼び名。 */
	static String label(Item i) {
		if (i == Items.IRON_INGOT) {
			return "鉄インゴット";
		}
		if (i == Items.GOLD_INGOT) {
			return "金インゴット";
		}
		if (i == Items.RAW_IRON) {
			return "生鉄";
		}
		return Names.item(i);
	}

	static Ing of(TagKey<Item> t, int n, String label) {
		return new Ing(null, t, n, label);
	}

	private static void add(Item out, int cnt, boolean table, Ing... ings) {
		ALL.add(new Recipe(out, cnt, table, List.of(ings)));
	}

	static final Ing PLANKS1 = of(ItemTags.PLANKS, 1, "板材");

	static {
		// 原木・幹 → 板材（2x2）。種類ごと
		for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
			String p = id.getPath();
			String base = null;
			if (p.endsWith("_log")) {
				base = p.substring(0, p.length() - 4);
			} else if (p.endsWith("_stem") && !p.startsWith("mushroom")) {
				base = p.substring(0, p.length() - 5);
			} else if (p.equals("bamboo_block")) {
				base = "bamboo";
			}
			if (base == null || p.startsWith("stripped_")) {
				continue;
			}
			Item log = BuiltInRegistries.ITEM.getValue(id);
			Identifier pid = Identifier.withDefaultNamespace(base + "_planks");
			if (BuiltInRegistries.ITEM.containsKey(pid)) {
				add(BuiltInRegistries.ITEM.getValue(pid), 4, false, of(log, 1));
			}
		}
		add(Items.STICK, 4, false, of(ItemTags.PLANKS, 2, "板材"));
		// ボート: 板材5。本物は3x3だが、川辺で作れるよう作業台なしにする
		for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
			String p = id.getPath();
			if ((p.endsWith("_boat") && !p.endsWith("chest_boat")) || p.equals("bamboo_raft")) {
				add(BuiltInRegistries.ITEM.getValue(id), 1, false, of(ItemTags.PLANKS, 5, "板材"));
			}
		}
		add(Items.CRAFTING_TABLE, 1, false, of(ItemTags.PLANKS, 4, "板材"));
		add(Items.CHEST, 1, true, of(ItemTags.PLANKS, 8, "板材"));
		add(Items.TORCH, 4, false, of(ItemTags.COALS, 1, "石炭"), of(Items.STICK, 1));
		add(Items.FURNACE, 1, true, of(ItemTags.STONE_CRAFTING_MATERIALS, 8, "丸石"));
		add(Items.BUCKET, 1, true, of(Items.IRON_INGOT, 3));
		add(Items.FLINT_AND_STEEL, 1, false, of(Items.IRON_INGOT, 1), of(Items.FLINT, 1));
		add(Items.SHIELD, 1, true, of(Items.IRON_INGOT, 1), of(ItemTags.PLANKS, 6, "板材"));
		add(Items.BLAZE_POWDER, 2, false, of(Items.BLAZE_ROD, 1));
		add(Items.ENDER_EYE, 1, false, of(Items.BLAZE_POWDER, 1), of(Items.ENDER_PEARL, 1));
		add(Items.BREAD, 1, true, of(Items.WHEAT, 3));
		add(Items.BOW, 1, true, of(Items.STICK, 3), of(Items.STRING, 3));
		add(Items.ARROW, 4, false, of(Items.FLINT, 1), of(Items.STICK, 1), of(Items.FEATHER, 1));

		// 道具: 材質ごと
		Object[][] mats = {
			{ "wooden", ItemTags.PLANKS, "板材" },
			{ "stone", ItemTags.STONE_TOOL_MATERIALS, "丸石" },
			{ "iron", Items.IRON_INGOT, null },
			{ "golden", Items.GOLD_INGOT, null },
			{ "diamond", Items.DIAMOND, null },
		};
		for (Object[] m : mats) {
			String name = (String) m[0];
			Ing one = ingOf(m, 1);
			add(item(name + "_pickaxe"), 1, true, ingOf(m, 3), of(Items.STICK, 2));
			add(item(name + "_axe"), 1, true, ingOf(m, 3), of(Items.STICK, 2));
			add(item(name + "_sword"), 1, true, ingOf(m, 2), of(Items.STICK, 1));
			add(item(name + "_shovel"), 1, true, one, of(Items.STICK, 2));
			add(item(name + "_hoe"), 1, true, ingOf(m, 2), of(Items.STICK, 2));
		}
		// 防具: 鉄・ダイヤ
		for (String name : new String[] { "iron", "diamond" }) {
			Item mat = name.equals("iron") ? Items.IRON_INGOT : Items.DIAMOND;
			add(item(name + "_helmet"), 1, true, of(mat, 5));
			add(item(name + "_chestplate"), 1, true, of(mat, 8));
			add(item(name + "_leggings"), 1, true, of(mat, 7));
			add(item(name + "_boots"), 1, true, of(mat, 4));
		}
		// ベッド: 同じ色の羊毛3 + 板材3
		for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
			String p = id.getPath();
			if (p.endsWith("_wool")) {
				Identifier bed = Identifier.withDefaultNamespace(p.substring(0, p.length() - 5) + "_bed");
				if (BuiltInRegistries.ITEM.containsKey(bed)) {
					add(BuiltInRegistries.ITEM.getValue(bed), 1, true, of(BuiltInRegistries.ITEM.getValue(id), 3), of(ItemTags.PLANKS, 3, "板材"));
				}
			}
		}

		// 精錬
		smelt(Items.RAW_IRON, Items.IRON_INGOT);
		smelt(Items.RAW_GOLD, Items.GOLD_INGOT);
		smelt(Items.RAW_COPPER, Items.COPPER_INGOT);
		smelt(Items.IRON_ORE, Items.IRON_INGOT);
		smelt(Items.GOLD_ORE, Items.GOLD_INGOT);
		smelt(Items.COPPER_ORE, Items.COPPER_INGOT);
		smelt(Items.DEEPSLATE_IRON_ORE, Items.IRON_INGOT);
		smelt(Items.DEEPSLATE_GOLD_ORE, Items.GOLD_INGOT);
		smelt(Items.DEEPSLATE_COPPER_ORE, Items.COPPER_INGOT);
		smelt(Items.SAND, Items.GLASS);
		smelt(Items.COBBLESTONE, Items.STONE);
		smelt(Items.ANCIENT_DEBRIS, Items.NETHERITE_SCRAP);
		smelt(Items.OAK_LOG, Items.CHARCOAL);
		smelt(Items.SPRUCE_LOG, Items.CHARCOAL);
		smelt(Items.BIRCH_LOG, Items.CHARCOAL);
		smelt(Items.JUNGLE_LOG, Items.CHARCOAL);
		smelt(Items.ACACIA_LOG, Items.CHARCOAL);
		smelt(Items.DARK_OAK_LOG, Items.CHARCOAL);
		smelt(Items.BEEF, Items.COOKED_BEEF);
		smelt(Items.PORKCHOP, Items.COOKED_PORKCHOP);
		smelt(Items.CHICKEN, Items.COOKED_CHICKEN);
		smelt(Items.MUTTON, Items.COOKED_MUTTON);
		smelt(Items.RABBIT, Items.COOKED_RABBIT);
		smelt(Items.COD, Items.COOKED_COD);
		smelt(Items.SALMON, Items.COOKED_SALMON);
		smelt(Items.POTATO, Items.BAKED_POTATO);
		smelt(Items.KELP, Items.DRIED_KELP);

		String[] kinds = {
			"ツルハシ", "pickaxe", "つるはし", "pickaxe", "斧", "axe", "剣", "sword", "シャベル", "shovel", "スコップ", "shovel",
			"クワ", "hoe", "ヘルメット", "helmet", "兜", "helmet", "チェストプレート", "chestplate", "胸当て", "chestplate",
			"レギンス", "leggings", "ブーツ", "boots",
		};
		for (int i = 0; i + 1 < kinds.length; i += 2) {
			JP_KIND.put(kinds[i], kinds[i + 1]);
		}
		String[] ms = { "木", "wooden", "石", "stone", "鉄", "iron", "金", "golden", "ダイヤ", "diamond", "ダイヤモンド", "diamond" };
		for (int i = 0; i + 1 < ms.length; i += 2) {
			JP_MAT.put(ms[i], ms[i + 1]);
		}
		String[] al = {
			"板材", "oak_planks", "木の板", "oak_planks", "板", "oak_planks", "planks", "oak_planks", "棒", "stick", "木の棒", "stick",
			"作業台", "crafting_table", "かまど", "furnace", "竈", "furnace", "チェスト", "chest", "松明", "torch", "バケツ", "bucket",
			"火打石と打ち金", "flint_and_steel", "火打ち石と打ち金", "flint_and_steel", "打ち金", "flint_and_steel",
			"盾", "shield", "ボート", "oak_boat", "舟", "oak_boat", "いかだ", "bamboo_raft", "ブレイズパウダー", "blaze_powder", "エンダーアイ", "ender_eye", "パン", "bread", "弓", "bow", "矢", "arrow",
			"ベッド", "white_bed", "bed", "white_bed", "pickaxe", "iron_pickaxe",
			"鉄", "iron_ingot", "iron", "iron_ingot", "鉄インゴット", "iron_ingot", "金インゴット", "gold_ingot", "金", "gold_ingot", "gold", "gold_ingot",
			"銅", "copper_ingot", "copper", "copper_ingot", "ガラス", "glass", "glass", "glass", "石炭", "coal", "木炭", "charcoal",
			"ステーキ", "cooked_beef", "焼き豚", "cooked_porkchop", "焼き鳥", "cooked_chicken", "生牛肉", "beef", "生豚肉", "porkchop",
			"生鶏肉", "chicken", "牛肉", "beef", "豚肉", "porkchop", "鶏肉", "chicken", "羊肉", "mutton", "ジャガイモ", "potato",
			"ベイクドポテト", "baked_potato", "丸石", "cobblestone", "砂", "sand", "石", "stone",
		};
		for (int i = 0; i + 1 < al.length; i += 2) {
			ALIAS.put(al[i], al[i + 1]);
		}
	}

	private static Ing ingOf(Object[] m, int n) {
		if (m[1] instanceof Item it) {
			return of(it, n);
		}
		@SuppressWarnings("unchecked")
		TagKey<Item> t = (TagKey<Item>) m[1];
		return of(t, n, (String) m[2]);
	}

	private static Item item(String path) {
		return BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(path));
	}

	private static void smelt(Item in, Item out) {
		SMELTS.add(new Smelt(in, out));
	}

	/** 名前（日本語・英語ID）からアイテムを引く。無ければ null。 */
	static Item resolveItem(String q) {
		if (q == null) {
			return null;
		}
		String s = q.trim();
		if (s.startsWith("minecraft:")) {
			s = s.substring("minecraft:".length());
		}
		String lower = s.toLowerCase(Locale.ROOT).replace(' ', '_');
		String alias = ALIAS.get(s);
		if (alias == null) {
			alias = ALIAS.get(lower);
		}
		if (alias != null) {
			lower = alias;
		}
		Identifier id = Identifier.tryParse(lower);
		if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
			return BuiltInRegistries.ITEM.getValue(id);
		}
		// 「木のツルハシ」「鉄の剣」形式
		int no = s.indexOf('の');
		if (no > 0) {
			String mat = JP_MAT.get(s.substring(0, no));
			String kind = JP_KIND.get(s.substring(no + 1));
			if (mat != null && kind != null) {
				Identifier tid = Identifier.withDefaultNamespace(mat + "_" + kind);
				if (BuiltInRegistries.ITEM.containsKey(tid)) {
					return BuiltInRegistries.ITEM.getValue(tid);
				}
			}
		}
		// 材質なしの「ツルハシ」→ 持っていない前提で一番作りやすい木
		String kind = JP_KIND.get(s);
		if (kind != null) {
			return item("wooden_" + kind);
		}
		// 日本語名の逆引き
		for (Identifier k : BuiltInRegistries.ITEM.keySet()) {
			Item it = BuiltInRegistries.ITEM.getValue(k);
			if (Names.item(it).equals(s)) {
				return it;
			}
		}
		return null;
	}

	/** 出力アイテムのレシピ候補（板材のように原木の種類で複数ある）。 */
	static List<Recipe> forOutput(Item out) {
		List<Recipe> r = new ArrayList<>();
		for (Recipe x : ALL) {
			if (x.out() == out) {
				r.add(x);
			}
		}
		return r;
	}

	/** 板材なら種類を問わない候補（原木の手持ちで選ぶため）。 */
	static List<Recipe> planksRecipes() {
		List<Recipe> r = new ArrayList<>();
		for (Recipe x : ALL) {
			if (new ItemStack(x.out()).is(ItemTags.PLANKS)) {
				r.add(x);
			}
		}
		return r;
	}

	/** 入力または出力として一致する精錬。手持ちの入力があるものを優先。 */
	static List<Smelt> smeltsFor(Item q) {
		List<Smelt> r = new ArrayList<>();
		for (Smelt s : SMELTS) {
			if (s.in() == q || s.out() == q) {
				r.add(s);
			}
		}
		return r;
	}

	/** 燃料として燃やせる時間(tick)。燃料でなければ 0。 */
	static int fuelTicks(ItemStack st) {
		if (st.isEmpty()) {
			return 0;
		}
		if (st.is(Items.COAL) || st.is(Items.CHARCOAL)) {
			return 1600;
		}
		if (st.is(ItemTags.PLANKS) || st.is(ItemTags.LOGS_THAT_BURN)) {
			return 300;
		}
		if (st.is(Items.STICK)) {
			return 100;
		}
		return 0;
	}
}
