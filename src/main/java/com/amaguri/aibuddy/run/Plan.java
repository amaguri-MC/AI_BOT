package com.amaguri.aibuddy.run;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import com.amaguri.aibuddy.skill.RunSkills;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * 『持ち物と周囲から次の一手を決める表』。段階ごとに上から見て、条件に合った最初の行が次の行動になる。
 * 失敗した行はしばらく（失敗回数×5秒、最大60秒）飛ばすので、同じ所で止まらず次の行に進む。
 * どの行にも当たらなければ最後の「探検」行になる（暇な時間を作らない）。
 * tool が "#jump:段階" の行は段階を戻す/進める（足りない物を取りに戻る）。
 */
final class Plan {
	private Plan() {
	}

	/** 表の1行。timeout は tick（その時間を過ぎたら打ち切って次の行へ）。 */
	record Row(String key, Predicate<Ctx> when, String tool, Function<Ctx, JsonObject> in, int timeout, String why) {
	}

	/** 段階が終わったか。 */
	static boolean done(Stage s, Ctx c) {
		return switch (s) {
			case WOOD -> c.pickTier() >= 1;
			case STONE -> c.pickTier() >= 2 && c.axeTier() >= 2 && c.swordTier() >= 2;
			case IRON -> c.buckets() >= 2 && c.has(Items.SHIELD) && (c.has(Items.FLINT_AND_STEEL) || c.ap.flintGaveUp());
			case FOOD -> c.cooked() >= 8 || c.ap.foodGaveUp() && c.raw() == 0;
			case PORTAL -> c.nether();
			case BLAZE -> c.blazeUnits() >= 14 || c.rods() >= 7;
			case PEARLS -> c.pearls() + c.eyes() >= c.ap.eyesNeeded();
			case EYES -> c.eyes() >= c.ap.eyesNeeded();
			case STRONGHOLD -> c.overworld() && c.mark(RunSkills.MARK_FRAME) != null && c.dist(c.mark(RunSkills.MARK_FRAME)) <= 8;
			case FILL_PORTAL -> c.end();
			case DRAGON -> c.ap.dragonKilled();
			case DONE -> false;
		};
	}

	/** 段階の進み具合（goalText 用）「4/7」など。無ければ空。 */
	static String progress(Stage s, Ctx c) {
		return switch (s) {
			case WOOD -> "木材" + Math.min(c.woodUnits(), 14) + "/14";
			case STONE -> "道具" + ((c.pickTier() >= 2 ? 1 : 0) + (c.axeTier() >= 2 ? 1 : 0) + (c.swordTier() >= 2 ? 1 : 0)) + "/3";
			case IRON -> "鉄" + c.ingots() + "・バケツ" + c.buckets() + "/2";
			case FOOD -> "焼いた食料" + c.cooked() + "/8";
			case BLAZE -> Math.min(7, (c.blazeUnits() + 1) / 2) + "/7";
			case PEARLS -> Math.min(c.ap.eyesNeeded(), c.pearls() + c.eyes()) + "/" + c.ap.eyesNeeded();
			case EYES -> c.eyes() + "/" + c.ap.eyesNeeded();
			case STRONGHOLD -> c.mark(RunSkills.MARK_STRONGHOLD) == null ? "" : "あと" + Math.round(c.dist(c.mark(RunSkills.MARK_STRONGHOLD))) + "m";
			default -> "";
		};
	}

	/** 段階の終わりに postEvent する文。 */
	static String doneEvent(Stage s, Ctx c) {
		String at = " 座標 " + c.o.blockPosition().getX() + " " + c.o.blockPosition().getY() + " " + c.o.blockPosition().getZ();
		return switch (s) {
			case WOOD -> "木のツルハシできた";
			case STONE -> "石ツール揃った";
			case IRON -> "鉄の道具揃った（バケツ" + c.buckets() + "・盾" + (c.has(Items.FLINT_AND_STEEL) ? "・火打石" : "") + "）";
			case FOOD -> "食料揃った（" + (c.cooked() + c.raw()) + "個）";
			case PORTAL -> "ネザーに入った" + at;
			case BLAZE -> "ブレイズロッド集まった（" + c.rods() + "本）";
			case PEARLS -> "エンダーパール集まった（" + c.pearls() + "個）";
			case EYES -> "エンダーアイ作った（" + c.eyes() + "個）";
			case STRONGHOLD -> "要塞のエンドポータル見つけた" + at;
			case FILL_PORTAL -> "エンドに入った";
			case DRAGON -> "完走した（エンドラ討伐）";
			case DONE -> "";
		};
	}

	// ---- 表 ----

	static List<Row> rows(Stage s) {
		List<Row> t = new ArrayList<>();
		switch (s) {
			case WOOD -> {
				t.add(row("log", c -> c.woodUnits() < 14, "mine", c -> j("block", "log", "count", Math.max(1, (int) Math.ceil((14 - c.woodUnits()) / 4.0))), 60, "原木を集める"));
				t.add(row("planks", c -> c.planks() < 9 && c.logs() > 0, "craft", c -> j("item", "oak_planks", "count", Math.min(c.logs(), 3) * 4), 20, "板材を作る"));
				t.add(row("sticks", c -> c.sticks() < 2 && c.planks() >= 2, "craft", c -> j("item", "stick", "count", 4), 20, "棒を作る"));
				t.add(row("wpick", c -> c.planks() >= 3 && c.sticks() >= 2, "craft", c -> j("item", "wooden_pickaxe", "count", 1), 30, "木のツルハシを作る"));
			}
			case STONE -> {
				t.add(row("cobble", c -> c.cobble() < cobbleNeed(c), "mine", c -> j("block", "stone", "count", Math.max(1, cobbleNeed(c) - c.cobble())), 90, "石を掘る"));
				addSticks(t, c -> stickNeed(c));
				t.add(row("spick", c -> c.pickTier() < 2 && c.cobble() >= 3 && c.sticks() >= 2, "craft", c -> j("item", "stone_pickaxe", "count", 1), 30, "石のツルハシ"));
				t.add(row("saxe", c -> c.axeTier() < 2 && c.cobble() >= 3 && c.sticks() >= 2, "craft", c -> j("item", "stone_axe", "count", 1), 30, "石の斧"));
				t.add(row("ssword", c -> c.swordTier() < 2 && c.cobble() >= 2 && c.sticks() >= 1, "craft", c -> j("item", "stone_sword", "count", 1), 30, "石の剣"));
			}
			case IRON -> {
				t.add(row("iron_ore", c -> c.ingots() + c.n(Items.RAW_IRON) < ironNeed(c), "mine", c -> j("block", "iron", "count", Math.max(1, ironNeed(c) - c.ingots() - c.n(Items.RAW_IRON))), 20 * 150, "鉄を掘る"));
				t.add(row("coal", c -> c.n(Items.RAW_IRON) > 0 && !c.fuel(), "mine", c -> j("block", "coal", "count", 2), 20 * 60, "燃料の石炭"));
				t.add(row("fuel_log", c -> c.n(Items.RAW_IRON) > 0 && !c.fuel(), "mine", c -> j("block", "log", "count", 2), 60 * 20, "燃料の原木"));
				t.add(row("smelt_iron", c -> c.n(Items.RAW_IRON) > 0 && c.ingots() < ironNeed(c), "smelt", c -> j("item", "raw_iron", "count", Math.min(c.n(Items.RAW_IRON), ironNeed(c) - c.ingots())), 20 * 60, "鉄を焼く"));
				t.add(row("bucket", c -> c.buckets() < 2 && c.ingots() >= 3, "craft", c -> j("item", "bucket", "count", 1), 30, "バケツを作る"));
				t.add(row("fns", c -> !c.has(Items.FLINT_AND_STEEL) && c.has(Items.FLINT) && c.ingots() >= 1, "craft", c -> j("item", "flint_and_steel", "count", 1), 30, "火打石と打ち金"));
				t.add(row("shield_planks", c -> !c.has(Items.SHIELD) && c.ingots() >= 1 && c.planks() < 6 && c.logs() > 0, "craft", c -> j("item", "oak_planks", "count", 8), 20, "盾用の板材"));
				t.add(row("shield_log", c -> !c.has(Items.SHIELD) && c.ingots() >= 1 && c.planks() < 6 && c.logs() == 0, "mine", c -> j("block", "log", "count", 2), 60 * 20, "盾用の原木"));
				t.add(row("shield", c -> !c.has(Items.SHIELD) && c.ingots() >= 1 && c.planks() >= 6, "craft", c -> j("item", "shield", "count", 1), 30, "盾を作る"));
				t.add(row("gravel", c -> !c.has(Items.FLINT_AND_STEEL) && !c.has(Items.FLINT) && !c.ap.flintGaveUp(), "mine", c -> j("block", "gravel", "count", 3), 60 * 20, "火打石用の砂利"));
			}
			case FOOD -> {
				t.add(row("cook_fuel", c -> c.raw() > 0 && !c.fuel(), "mine", c -> j("block", "log", "count", 2), 60 * 20, "焼く燃料"));
				t.add(row("cook", c -> c.raw() >= 4 || c.raw() > 0 && c.cooked() + c.raw() >= 8, "smelt", c -> j("item", id(c.mostRaw()), "count", c.n(c.mostRaw())), 20 * 90, "肉を焼く"));
				t.add(row("hunt", c -> animal(c) != null, "hunt", c -> j("target", animal(c), "count", 3), 20 * 60, "動物を狩る"));
				t.add(row("cook_rest", c -> c.raw() > 0, "smelt", c -> j("item", id(c.mostRaw()), "count", c.n(c.mostRaw())), 20 * 90, "肉を焼く"));
			}
			case PORTAL -> {
				t.add(row("end", Ctx::end, "#jump:DRAGON", null, 0, "エンドにいる"));
				t.add(row("enter", c -> c.overworld() && portalAt(c, c.mark(RunSkills.MARK_PORTAL_OW)), "enter_portal", c -> pos(c.mark(RunSkills.MARK_PORTAL_OW)), 20 * 120, "ゲートに入る"));
				t.add(row("ruin", c -> c.overworld() && !c.ap.ruinChecked(), "find_structure", c -> {
					c.ap.setRuinChecked();
					return j("type", "ruined_portal");
				}, 20 * 90, "近くの廃ポータルを見に行く"));
				t.add(row("ruin_loot", c -> c.mark(RunSkills.MARK_RUIN) != null && c.dist(c.mark(RunSkills.MARK_RUIN)) < 20 && !c.ap.ruinLooted(), "loot_chests", c -> {
					c.ap.setRuinLooted();
					return new JsonObject();
				}, 20 * 30, "廃ポータルのチェスト"));
				t.add(row("need_bucket", c -> c.buckets() < 2 && c.n(Items.OBSIDIAN) < 10, "#jump:IRON", null, 0, "バケツが足りない"));
				t.add(row("water", c -> c.has(Items.BUCKET) && !c.has(Items.WATER_BUCKET) && c.n(Items.OBSIDIAN) < 10, "fill_water", c -> new JsonObject(), 20 * 60, "水を汲む"));
				t.add(row("build", c -> true, "build_nether_portal", c -> new JsonObject(), 20 * 150, "ゲートを作る"));
			}
			case BLAZE -> {
				t.add(row("ow_enter", c -> c.overworld() && portalAt(c, c.mark(RunSkills.MARK_PORTAL_OW)), "enter_portal", c -> pos(c.mark(RunSkills.MARK_PORTAL_OW)), 20 * 120, "ネザーへ"));
				t.add(row("ow_portal", Ctx::overworld, "#jump:PORTAL", null, 0, "まずゲート"));
				t.add(row("fortress", c -> c.nether() && (c.mark(RunSkills.MARK_FORTRESS) == null || c.dist(c.mark(RunSkills.MARK_FORTRESS)) > 72), "find_structure", c -> j("type", "fortress"), 20 * 360, "ネザー要塞へ"));
				t.add(row("blaze", Ctx::nether, "hunt", c -> j("target", "blaze", "count", Math.max(1, (14 - c.blazeUnits() + 1) / 2)), 20 * 180, "ブレイズを狩る"));
				t.add(row("fortress_roam", c -> c.nether() && c.mark(RunSkills.MARK_FORTRESS) != null, "goto", c -> around(c, c.mark(RunSkills.MARK_FORTRESS), 40), 20 * 60, "要塞の中を回る"));
			}
			case PEARLS -> {
				t.add(row("barter", c -> c.n(Items.GOLD_INGOT) >= 2 && c.nether() && c.nearest(EntityTypes.PIGLIN, 32) != null, "barter", c -> j("gold", Math.min(16, c.n(Items.GOLD_INGOT))), 20 * 120, "ピグリンと取引"));
				t.add(row("enderman", c -> !c.end(), "hunt", c -> j("target", "enderman", "count", Math.max(1, c.ap.eyesNeeded() - c.pearls() - c.eyes())), 20 * 180, "エンダーマンを狩る"));
			}
			case EYES -> {
				t.add(row("powder", c -> c.powder() < Math.min(c.pearls(), c.ap.eyesNeeded() - c.eyes()) && c.rods() > 0, "craft", c -> j("item", "blaze_powder", "count", Math.min(c.rods() * 2, Math.max(2, c.ap.eyesNeeded() - c.eyes() - c.powder()))), 30, "ブレイズパウダー"));
				t.add(row("eye", c -> c.pearls() > 0 && c.powder() > 0, "craft", c -> j("item", "ender_eye", "count", Math.min(c.pearls(), Math.min(c.powder(), c.ap.eyesNeeded() - c.eyes()))), 60, "エンダーアイ"));
				t.add(row("need_pearls", c -> c.pearls() == 0, "#jump:PEARLS", null, 0, "パールが足りない"));
				t.add(row("need_rods", c -> c.powder() == 0 && c.rods() == 0, "#jump:BLAZE", null, 0, "ロッドが足りない"));
			}
			case STRONGHOLD -> {
				t.add(row("end", Ctx::end, "#jump:DRAGON", null, 0, "エンドにいる"));
				t.add(row("back_mark", c -> c.nether() && c.mark(RunSkills.MARK_PORTAL_NETHER) != null, "enter_portal", c -> pos(c.mark(RunSkills.MARK_PORTAL_NETHER)), 20 * 240, "ゲートでオーバーワールドへ"));
				t.add(row("back_any", Ctx::nether, "enter_portal", c -> new JsonObject(), 20 * 60, "近くのゲートへ"));
				t.add(row("back_build", Ctx::nether, "build_nether_portal", c -> new JsonObject(), 20 * 120, "帰りのゲートを作る"));
				t.add(row("locate", Ctx::overworld, "locate_stronghold", c -> new JsonObject(), 20 * 600, "エンダーアイで要塞を探す"));
				t.add(row("direct", Ctx::overworld, "find_structure", c -> j("type", "stronghold"), 20 * 600, "要塞へ向かう"));
			}
			case FILL_PORTAL -> {
				t.add(row("not_ow", c -> !c.overworld() && !c.end(), "#jump:STRONGHOLD", null, 0, "要塞に戻る"));
				t.add(row("far", c -> c.overworld() && (c.mark(RunSkills.MARK_FRAME) == null || c.dist(c.mark(RunSkills.MARK_FRAME)) > 16), "#jump:STRONGHOLD", null, 0, "部屋から離れた"));
				t.add(row("fill", Ctx::overworld, "fill_end_portal", c -> new JsonObject(), 20 * 150, "アイをはめて飛び込む"));
			}
			case DRAGON -> {
				t.add(row("not_end", c -> !c.end(), "#jump:FILL_PORTAL", null, 0, "エンドに行く"));
				t.add(row("dragon", Ctx::end, "fight_dragon", c -> new JsonObject(), 20 * 900, "エンドラと戦う"));
			}
			case DONE -> {
				t.add(row("follow", c -> c.nearestPlayer(48) != null, "follow_player", c -> {
					ServerPlayer p = c.nearestPlayer(48);
					return j("name", p.getScoreboardName(), "player", p.getScoreboardName());
				}, 20 * 60, "みんなのところへ"));
				t.add(row("wave", c -> true, "emote", c -> j("kind", "wave"), 40, "手を振る"));
			}
			default -> {
			}
		}
		// 最後の砦: 何も当たらなければ探検（新しい木・石・鉱石・溶岩・動物を見つける）
		if (s != Stage.DONE) {
			t.add(row("explore", c -> !c.end(), "explore", c -> new JsonObject(), 20 * 40, "探検して探す"));
		}
		return t;
	}

	// ---- 計算 ----

	static int cobbleNeed(Ctx c) {
		return (c.pickTier() >= 2 ? 0 : 3) + (c.axeTier() >= 2 ? 0 : 3) + (c.swordTier() >= 2 ? 0 : 2)
				+ (c.has(Items.FURNACE) ? 0 : 8);
	}

	static int stickNeed(Ctx c) {
		return (c.pickTier() >= 2 ? 0 : 2) + (c.axeTier() >= 2 ? 0 : 2) + (c.swordTier() >= 2 ? 0 : 1);
	}

	/** 必要な鉄: バケツ2個（水用と溶岩用）・盾・火打石と打ち金。 */
	static int ironNeed(Ctx c) {
		return Math.max(0, 2 - c.buckets()) * 3 + (c.has(Items.SHIELD) ? 0 : 1) + (c.has(Items.FLINT_AND_STEEL) || c.ap.flintGaveUp() ? 0 : 1);
	}

	private static void addSticks(List<Row> t, java.util.function.ToIntFunction<Ctx> need) {
		t.add(row("sticks", c -> c.sticks() < need.applyAsInt(c) && c.planks() >= 2, "craft", c -> j("item", "stick", "count", 4), 20, "棒"));
		t.add(row("planks2", c -> c.sticks() < need.applyAsInt(c) && c.planks() < 2 && c.logs() > 0, "craft", c -> j("item", "oak_planks", "count", 4), 20, "板材"));
		t.add(row("log2", c -> c.sticks() < need.applyAsInt(c) && c.planks() < 2 && c.logs() == 0, "mine", c -> j("block", "log", "count", 1), 60 * 20, "原木"));
	}

	/** 近くにいる狩れる動物の種類（近い順）。いなければ null。 */
	static String animal(Ctx c) {
		EntityType<?>[] types = { EntityTypes.COW, EntityTypes.PIG, EntityTypes.SHEEP, EntityTypes.CHICKEN };
		String[] ids = { "cow", "pig", "sheep", "chicken" };
		String best = null;
		double bd = Double.MAX_VALUE;
		for (int i = 0; i < types.length; i++) {
			var e = c.nearest(types[i], 48);
			if (e != null && e.distanceToSqr(c.o) < bd) {
				bd = e.distanceToSqr(c.o);
				best = ids[i];
			}
		}
		return best;
	}

	static boolean portalAt(Ctx c, BlockPos p) {
		if (p == null) {
			return false;
		}
		if (!c.l.hasChunkAt(p)) {
			return c.dist(p) < 2000; // 読み込まれていなければ在ると見なして向かう
		}
		return c.l.getBlockState(p).is(Blocks.NETHER_PORTAL);
	}

	static String id(net.minecraft.world.item.Item i) {
		return i == null ? "beef" : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(i).getPath();
	}

	static JsonObject pos(BlockPos p) {
		JsonObject o = new JsonObject();
		if (p != null) {
			o.addProperty("x", p.getX());
			o.addProperty("y", p.getY());
			o.addProperty("z", p.getZ());
		}
		return o;
	}

	static JsonObject around(Ctx c, BlockPos p, int r) {
		var rnd = c.o.getRandom();
		return pos(p.offset(rnd.nextInt(r * 2 + 1) - r, 0, rnd.nextInt(r * 2 + 1) - r));
	}

	static JsonObject j(Object... kv) {
		JsonObject o = new JsonObject();
		for (int i = 0; i + 1 < kv.length; i += 2) {
			String k = (String) kv[i];
			Object v = kv[i + 1];
			if (v instanceof Number n) {
				o.addProperty(k, n);
			} else {
				o.addProperty(k, String.valueOf(v));
			}
		}
		return o;
	}

	private static Row row(String key, Predicate<Ctx> when, String tool, Function<Ctx, JsonObject> in, int timeout, String why) {
		return new Row(key, when, tool, in, timeout, why);
	}
}
