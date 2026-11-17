package com.amaguri.aibuddy.skill;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Claude API の tools 形式（name / description / input_schema）。 */
final class Tools {
	private Tools() {
	}

	static JsonArray specs() {
		JsonArray a = new JsonArray();
		a.add(tool("follow_player", "指定したプレイヤーの後ろをついて歩く。stop するまで続く。",
				props(str("name", "ついていくプレイヤー名")), "name"));
		a.add(tool("goto", "指定座標まで歩いて行く。遠くても自分で経路を探し、詰まったら回り込む・掘る・足場を置く。着いたら終わる。",
				props(num("x", "X座標"), num("y", "Y座標（高さ）"), num("z", "Z座標")), "x", "y", "z"));
		a.add(tool("explore", "周囲を探検して歩き回る。方向を指定しなければ適当に進む。",
				props(str("direction", "north|south|east|west|northeast|northwest|southeast|southwest（省略可）"))));
		a.add(tool("stop", "今やっている行動をやめてその場で止まる。", props()));
		a.add(tool("mine", "近くのブロックを探して歩いて行き、ツルハシで掘る（ダイヤのツルハシ並みの速さ）。落ちた物は拾う。",
				props(str("block", "ブロックID（diamond_ore, iron_ore, oak_log など）か通称（ダイヤ, 鉄, 石炭, 木, ore=鉱石全般）"),
						integer("count", "掘る個数（既定5）")), "block"));
		a.add(tool("collect_items", "近くに落ちているアイテムを歩いて拾い集める。", props()));
		a.add(tool("attack", "敵を攻撃する。プレイヤーは攻撃できない。",
				props(str("target", "nearest_hostile（一番近い敵）またはエンティティ種ID（zombie, skeleton など）")), "target"));
		a.add(tool("find_structure", "構造物の場所を探して、そこへ歩いて向かう。着いたら知らせる。",
				props(enumStr("type", "探す構造物", "stronghold", "fortress", "village", "bastion")), "type"));
		a.add(tool("build_nether_portal", "足元付近に黒曜石の枠を1ブロックずつ置いて着火し、ネザーゲートを作る。", props()));
		a.add(tool("eat", "持っている食べ物を食べて体力を回復する。", props()));
		a.add(tool("emote", "ジャンプ・しゃがみ・プレイヤーを見る・手を振るなどの動作をする。",
				props(enumStr("kind", "動作の種類", "jump", "crouch", "look_at_player", "wave")), "kind"));
		return a;
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
