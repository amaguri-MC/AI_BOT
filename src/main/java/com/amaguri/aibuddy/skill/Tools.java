package com.amaguri.aibuddy.skill;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Claude API の tools 形式（name / description / input_schema）。 */
final class Tools {
	private Tools() {
	}

	/**
	 * 脳用の少数のツール（say / wait は脳側で足す）。ゲームの進行は Autopilot が決めるので、
	 * 掘る・作る等は脳に渡さない（指示待ちで止まらないように）。
	 */
	static JsonArray specs() {
		JsonArray a = new JsonArray();
		a.add(tool("follow_player", "指定したプレイヤーについていく（自分の進行は60秒止まる）。頼まれたときだけ使う。",
				props(str("name", "ついていくプレイヤー名")), "name"));
		a.add(tool("stop_follow", "ついていくのをやめて、自分の進行（エンドラ討伐までの段階）に戻る。", props()));
		a.add(tool("resume_run", "止まっている自分の進行をすぐ再開する。", props()));
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
}
