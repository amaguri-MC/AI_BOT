package com.amaguri.aibuddy.skill;

import com.amaguri.aibuddy.entity.FakeMember;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** W2 のスキル（移動・戦い）の生成口と、ツール仕様。SkillRunner.create から呼ばれる。 */
public final class MotionSkills {
	private MotionSkills() {
	}

	static final java.util.Set<String> HUNTABLE = java.util.Set.of("cow", "pig", "sheep", "chicken", "enderman", "blaze");

	/** 知らない名前は null。 */
	static Skill create(String name, FakeMember o, SkillRunner r, JsonObject in) {
		return switch (name) {
			case "goto" -> new GotoSkill(o, r, num(in, "x"), num(in, "y"), num(in, "z"));
			case "follow_player" -> new FollowSkill(o, r, str(in, "name"));
			case "explore" -> new ExploreSkill(o, r, str(in, "direction"));
			case "attack" -> new AttackSkill(o, r, str(in, "target"));
			case "hunt" -> {
				String t = str(in, "target");
				if (t == null || !HUNTABLE.contains(t.toLowerCase().trim())) {
					throw new IllegalArgumentException("hunt の target は cow|pig|sheep|chicken|enderman|blaze");
				}
				yield new AttackSkill(o, r, t, true, in.has("count") ? (int) num(in, "count") : 1);
			}
			case "bridge" -> new BridgeSkill(o, r, str(in, "direction"), in.has("length") ? (int) num(in, "length") : 5);
			case "pillar" -> new PillarSkill(o, r, in.has("height") ? (int) num(in, "height") : 4);
			case "barter" -> new BarterSkill(o, r, in.has("gold") ? (int) num(in, "gold") : 8);
			case "boat" -> new BoatSkill(o, r, num(in, "x"), num(in, "y"), num(in, "z"));
			case "fight_dragon" -> new DragonSkill(o, r);
			default -> null;
		};
	}

	static void specs(JsonArray out) {
		out.add(tool("goto", "指定座標まで走る（3ブロック以上はダッシュ、平地はダッシュジャンプ）。詰まったら掘る・柱・橋で進む。",
				props(num("x", "X座標"), num("y", "Y座標"), num("z", "Z座標")), "x", "y", "z"));
		out.add(tool("follow_player", "指定したプレイヤーの後ろをついて走る。", props(str("name", "プレイヤー名")), "name"));
		out.add(tool("explore", "方向を決めて探検して歩き回る。", props(str("direction", "north|south|east|west|northeast|northwest|southeast|southwest（省略可）"))));
		out.add(tool("attack", "敵を殴る。プレイヤーは殴らない。", props(str("target", "nearest_hostile またはエンティティ種ID")), "target"));
		out.add(tool("hunt", "獲物を探して狩り、落とし物を拾う。",
				props(enumStr("target", "獲物", "cow", "pig", "sheep", "chicken", "enderman", "blaze"), integer("count", "倒す数（既定1）")), "target"));
		out.add(tool("bridge", "指定方向に足場を置きながら橋を渡る。ブロックが要る。",
				props(str("direction", "north|south|east|west（省略で向いている方向）"), integer("length", "マス数（既定5）"))));
		out.add(tool("pillar", "真上に足場を積んで登る。ブロックが要る。", props(integer("height", "高さ（既定4）"))));
		out.add(tool("barter", "金インゴットをピグリンに投げて取引し、落ちた物を拾う。", props(integer("gold", "投げる個数（既定8）"))));
		out.add(tool("boat", "ボートを作って（板材5）水を渡り、目標の座標へ行く。作れなければ泳ぐ。陸に着いたらボートは回収する。",
				props(num("x", "X座標"), num("y", "Y座標"), num("z", "Z座標")), "x", "y", "z"));
		out.add(tool("fight_dragon", "エンドでエンドラと戦う。クリスタルを壊し、止まり木の竜を殴り、ブレスをよける。倒したら知らせる。", props()));
	}

	// ---- 小道具（Tools と同じ形式） ----

	static String str(JsonObject in, String key) {
		JsonElement e = in.get(key);
		return e == null || e.isJsonNull() ? null : e.getAsString();
	}

	static double num(JsonObject in, String key) {
		JsonElement e = in.get(key);
		if (e == null || e.isJsonNull()) {
			throw new IllegalArgumentException(key + " が必要");
		}
		return e.getAsDouble();
	}

	private static JsonObject tool(String name, String desc, JsonObject properties, String... required) {
		JsonObject t = new JsonObject();
		t.addProperty("name", name);
		t.addProperty("description", desc);
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", properties);
		JsonArray req = new JsonArray();
		for (String r : required) {
			req.add(r);
		}
		schema.add("required", req);
		t.add("input_schema", schema);
		return t;
	}

	private static JsonObject props(JsonObject... ps) {
		JsonObject o = new JsonObject();
		for (JsonObject p : ps) {
			String key = p.remove("_key").getAsString();
			o.add(key, p);
		}
		return o;
	}

	private static JsonObject prop(String key, String type, String desc) {
		JsonObject p = new JsonObject();
		p.addProperty("_key", key);
		p.addProperty("type", type);
		p.addProperty("description", desc);
		return p;
	}

	private static JsonObject str(String key, String desc) {
		return prop(key, "string", desc);
	}

	private static JsonObject num(String key, String desc) {
		return prop(key, "number", desc);
	}

	private static JsonObject integer(String key, String desc) {
		return prop(key, "integer", desc);
	}

	private static JsonObject enumStr(String key, String desc, String... values) {
		JsonObject p = prop(key, "string", desc);
		JsonArray e = new JsonArray();
		for (String v : values) {
			e.add(v);
		}
		p.add("enum", e);
		return p;
	}
}
