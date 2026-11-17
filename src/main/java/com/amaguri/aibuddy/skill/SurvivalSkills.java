package com.amaguri.aibuddy.skill;

import com.amaguri.aibuddy.entity.FakeMember;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * サバイバルの手（掘る・作る・焼く・食べる）のスキルを名前から作る。SkillRunner が最初に問い合わせる。
 * 知らない名前は null を返す。
 */
public final class SurvivalSkills {
	private SurvivalSkills() {
	}

	static Skill create(String name, FakeMember o, SkillRunner r, JsonObject in) {
		if (name == null) {
			return null;
		}
		return switch (name) {
			case "mine" -> new MineSkill(o, r, SkillRunner.str(in, "block"), count(in, 5));
			case "craft" -> new CraftSkill(o, r, SkillRunner.str(in, "item"), count(in, 1));
			case "smelt" -> new SmeltSkill(o, r, SkillRunner.str(in, "item"), count(in, 1));
			case "eat" -> new EatSkill(o, r);
			default -> null;
		};
	}

	private static int count(JsonObject in, int def) {
		return in.has("count") && !in.get("count").isJsonNull() ? (int) in.get("count").getAsDouble() : def;
	}

	/** 脳・自動進行に見せるツール定義（name / description / input_schema）を out に足す。 */
	static void specs(JsonArray out) {
		out.add(tool("mine", "近くのブロックを探して歩いて行き、持っている道具で掘る。掘る速さ・ドロップ・道具の消耗はプレイヤーと同じで、"
				+ "素手の石はドロップ無し。鉱石は適正なツルハシが要る（石炭=木、鉄・ラピス=石、ダイヤ・金・レッドストーン=鉄、黒曜石=ダイヤ）。落ちた物は拾う。",
				prop("block", "string", "ブロックID（iron_ore, oak_log など）か通称（鉄, 石炭, 木, 石, ore=鉱石全般）"),
				prop("count", "integer", "掘る個数（既定5）"), "block"));
		out.add(tool("craft", "持ち物の材料でクラフトする。3x3 は近くの作業台（無ければ作って置く）。板材・棒・作業台は原木から自動で作る。"
				+ "例: wooden_pickaxe, stone_pickaxe, iron_sword, furnace, bucket, flint_and_steel, shield, blaze_powder, ender_eye, bread, iron_chestplate, white_bed。",
				prop("item", "string", "作る物のID（wooden_pickaxe など）か日本語名（木のツルハシ）"),
				prop("count", "integer", "作る個数（既定1）"), "item"));
		out.add(tool("smelt", "かまどで焼く・精錬する（無ければ置く）。燃料は石炭・木炭・板材・原木。1個200tick、かまどの前で待つ。"
				+ "例: raw_iron（鉄インゴットになる）, beef（ステーキになる）, sand, cobblestone。",
				prop("item", "string", "焼く物（入力でも出力でも可）。raw_iron, 鉄, beef, 生牛肉 など"),
				prop("count", "integer", "焼く個数（既定1）"), "item"));
		out.add(tool("eat", "持っている食べ物を食べて体力を回復する。", null, null, null));
	}

	private static JsonObject prop(String key, String type, String desc) {
		JsonObject p = new JsonObject();
		p.addProperty("_key", key);
		p.addProperty("type", type);
		p.addProperty("description", desc);
		return p;
	}

	private static JsonObject tool(String name, String desc, JsonObject a, JsonObject b, String required) {
		JsonObject t = new JsonObject();
		t.addProperty("name", name);
		t.addProperty("description", desc);
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		JsonObject props = new JsonObject();
		for (JsonObject p : new JsonObject[] { a, b }) {
			if (p != null) {
				String key = p.remove("_key").getAsString();
				props.add(key, p);
			}
		}
		schema.add("properties", props);
		JsonArray req = new JsonArray();
		if (required != null) {
			req.add(required);
		}
		schema.add("required", req);
		t.add("input_schema", schema);
		return t;
	}
}
