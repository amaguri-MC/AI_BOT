package com.amaguri.aibuddy.brain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletionException;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.server.MinecraftServer;

import com.amaguri.aibuddy.AiBuddyMod;
import com.amaguri.aibuddy.entity.FakeMember;
import com.amaguri.aibuddy.skill.SkillRunner;

/**
 * 1体ぶんの脳。フィールドはすべてサーバースレッドからだけ触る
 * （HTTP は別スレッドだが、結果は server.execute でサーバースレッドに戻して処理する）。
 */
final class Brain {
	private static final int MAX_CHATS = 10;
	private static final int MAX_EVENTS = 12;
	private static final int SAY_HARD_LIMIT = 120;
	private static final Random RANDOM = new Random();

	private record Chat(int tick, String name, String text) {
	}

	private final FakeMember member;
	private final String personaId;

	private final List<JsonObject> history = new ArrayList<>();
	private final List<JsonObject> pendingResults = new ArrayList<>();
	private final List<String> pendingEvents = new ArrayList<>();
	private final Deque<Chat> chats = new ArrayDeque<>();

	private boolean chatPending;
	private boolean inFlight;
	private int lastThink;
	private int blockedUntil;
	private int nextFallback;
	private int nextPuddleLine = -1;
	private boolean warnedNoKey;

	Brain(FakeMember member, int now, BrainSettings s) {
		this.member = member;
		this.personaId = member.getPersonaId();
		// 湧いて2秒後くらいに最初の思考をする
		this.lastThink = now - s.thinkIntervalSec * 20 + 40;
	}

	FakeMember member() {
		return member;
	}

	String personaIdLower() {
		return personaId.toLowerCase(java.util.Locale.ROOT);
	}

	// ---------------------------------------------------------------- 入力

	/** チャットを受け取る。near=半径内、called=名前を呼ばれた、fromBot=他のAIの発言。 */
	void onChat(int now, String name, String text, boolean near, boolean called, boolean fromBot) {
		if (!near && !called) return;
		chats.addLast(new Chat(now, name, text));
		while (chats.size() > MAX_CHATS) chats.removeFirst();
		if (!fromBot || called) chatPending = true;
	}

	// ---------------------------------------------------------------- tick

	void tick(MinecraftServer server, int now) {
		for (String e : member.drainEvents()) {
			if (pendingEvents.size() >= MAX_EVENTS) pendingEvents.remove(0);
			pendingEvents.add(e);
		}

		BrainSettings s = BrainManager.settings();

		if (member.isPuddle()) {
			puddleTick(now);
			return;
		}
		nextPuddleLine = -1;

		if (inFlight) return;
		if (now - lastThink < s.minGapSec * 20) return;

		String trigger = null;
		if (!pendingEvents.isEmpty()) trigger = "event";
		else if (chatPending) trigger = "chat";
		else if (now - lastThink >= s.thinkIntervalSec * 20 && !member.skills().isBusy()) trigger = "interval";
		if (trigger == null) return;

		think(server, now, s, trigger);
	}

	private void puddleTick(int now) {
		pendingEvents.clear();
		chatPending = false;
		if (nextPuddleLine < 0) nextPuddleLine = now + 200 + RANDOM.nextInt(400);
		if (now < nextPuddleLine) return;
		nextPuddleLine = now + 800 + RANDOM.nextInt(1600);
		Persona p = persona();
		List<String> lines = p == null ? List.of() : p.fallbackLines();
		// say 側で溶け具合に応じた Slur がかかる
		sayOut(lines.isEmpty() ? "……" : lines.get(RANDOM.nextInt(lines.size())));
	}

	// ---------------------------------------------------------------- 思考

	private void think(MinecraftServer server, int now, BrainSettings s, String trigger) {
		lastThink = now;
		Persona p = persona();
		if (p == null) return;

		String key = s.resolveKey();
		if (key.isEmpty()) {
			if (!warnedNoKey) {
				warnedNoKey = true;
				AiBuddyMod.LOGGER.warn("[brain] APIキーが無い（config.json の apiKey か環境変数 ANTHROPIC_API_KEY）。定型文だけで動く: {}", personaId);
			}
			pendingEvents.clear();
			chatPending = false;
			maybeFallback(now, 1.0);
			return;
		}
		if (now < blockedUntil) {
			chatPending = false;
			maybeFallback(now, 0.5);
			return;
		}

		String userText = buildUserText(now);
		JsonArray userContent = new JsonArray();
		for (JsonObject r : pendingResults) userContent.add(r.deepCopy());
		userContent.add(textBlock(userText));
		JsonObject userMsg = message("user", userContent);

		JsonArray messages = new JsonArray();
		for (JsonObject m : history) messages.add(m);
		messages.add(userMsg);

		JsonArray tools = buildTools();
		String system = BrainManager.systemPrompt(p);
		JsonObject body = ClaudeClient.buildBody(s, system, tools, messages);

		List<String> sentEvents = new ArrayList<>(pendingEvents);
		List<JsonObject> sentResults = new ArrayList<>(pendingResults);
		pendingEvents.clear();
		chatPending = false;
		inFlight = true;
		long t0 = System.nanoTime();

		BrainManager.client().send(key, s, body).whenComplete((resp, err) -> server.execute(() -> {
			inFlight = false;
			long ms = (System.nanoTime() - t0) / 1_000_000L;
			int tickNow = server.getTickCount();
			if (err != null) {
				onFailure(tickNow, s, trigger, userText, sentEvents, unwrap(err), ms);
			} else {
				try {
					onResponse(tickNow, s, trigger, userText, userMsg, sentResults, resp, ms);
				} catch (RuntimeException e) {
					AiBuddyMod.LOGGER.error("[brain] 応答の処理に失敗: " + personaId, e);
					onFailure(tickNow, s, trigger, userText, sentEvents, e, ms);
				}
			}
		}));
	}

	private String buildUserText(int now) {
		SkillRunner sk = member.skills();
		StringBuilder sb = new StringBuilder();
		sb.append("状況: ").append(safe(sk::observe)).append('\n');
		sb.append("今やっていること: ").append(safe(sk::status)).append('\n');
		sb.append("新しい出来事:");
		if (pendingEvents.isEmpty()) sb.append(" なし");
		for (String e : pendingEvents) sb.append("\n- ").append(e);
		sb.append("\n最近のチャット:");
		if (chats.isEmpty()) sb.append(" なし");
		for (Chat c : chats) {
			sb.append("\n- [").append(Math.max(0, (now - c.tick) / 20)).append("秒前] ")
					.append(c.name).append(": ").append(c.text);
		}
		return sb.toString();
	}

	private static String safe(java.util.function.Supplier<String> f) {
		try {
			String v = f.get();
			return v == null ? "不明" : v;
		} catch (RuntimeException e) {
			return "取得失敗: " + e.getMessage();
		}
	}

	/** D の toolSpecs() に say / wait を足したもの。 */
	static JsonArray buildTools() {
		JsonArray tools = new JsonArray();
		try {
			for (JsonElement e : SkillRunner.toolSpecs()) tools.add(e.deepCopy());
		} catch (RuntimeException e) {
			AiBuddyMod.LOGGER.error("[brain] toolSpecs() が失敗", e);
		}

		JsonObject say = new JsonObject();
		say.addProperty("name", "say");
		say.addProperty("description", "チャットで発言する。1回40字以内で短く。");
		JsonObject sayProps = new JsonObject();
		JsonObject text = new JsonObject();
		text.addProperty("type", "string");
		text.addProperty("description", "発言内容（40字以内）");
		sayProps.add("text", text);
		say.add("input_schema", schema(sayProps, "text"));
		tools.add(say);

		JsonObject wait = new JsonObject();
		wait.addProperty("name", "wait");
		wait.addProperty("description", "何もせず様子を見る。今の行動を続けるときや、やることが無いときに使う。");
		JsonObject waitProps = new JsonObject();
		JsonObject reason = new JsonObject();
		reason.addProperty("type", "string");
		reason.addProperty("description", "待つ理由（任意）");
		waitProps.add("reason", reason);
		wait.add("input_schema", schema(waitProps));
		tools.add(wait);
		return tools;
	}

	private static JsonObject schema(JsonObject props, String... required) {
		JsonObject o = new JsonObject();
		o.addProperty("type", "object");
		o.add("properties", props);
		if (required.length > 0) {
			JsonArray req = new JsonArray();
			for (String r : required) req.add(r);
			o.add("required", req);
		}
		return o;
	}

	// ---------------------------------------------------------------- 応答

	private void onResponse(int now, BrainSettings s, String trigger, String userText, JsonObject userMsg,
			List<JsonObject> sentResults, JsonObject resp, long ms) {
		// 履歴に積めるブロック（text / tool_use）だけ残す
		JsonArray content = new JsonArray();
		JsonElement raw = resp.get("content");
		if (raw != null && raw.isJsonArray()) {
			for (JsonElement el : raw.getAsJsonArray()) {
				if (!el.isJsonObject()) continue;
				JsonObject b = el.getAsJsonObject();
				String type = str(b, "type");
				if ("tool_use".equals(type)) content.add(b);
				else if ("text".equals(type) && !str(b, "text").isBlank()) content.add(b);
			}
		}
		if (content.isEmpty()) content.add(textBlock("……"));

		history.add(userMsg);
		history.add(message("assistant", content));
		pendingResults.removeAll(sentResults);
		trimHistory(s);

		List<JsonObject> results = new ArrayList<>();
		JsonArray toolLog = new JsonArray();
		StringBuilder plainText = new StringBuilder();
		for (JsonElement el : content) {
			JsonObject b = el.getAsJsonObject();
			if ("text".equals(str(b, "type"))) {
				plainText.append(str(b, "text"));
				continue;
			}
			String id = str(b, "id");
			String name = str(b, "name");
			JsonObject input = b.has("input") && b.get("input").isJsonObject() ? b.getAsJsonObject("input") : new JsonObject();
			String result = execute(name, input);
			JsonObject tr = new JsonObject();
			tr.addProperty("type", "tool_result");
			tr.addProperty("tool_use_id", id);
			tr.addProperty("content", result);
			results.add(tr);

			JsonObject tl = new JsonObject();
			tl.addProperty("name", name);
			tl.add("input", input);
			tl.addProperty("result", result);
			toolLog.add(tl);
		}
		pendingResults.addAll(results);

		JsonObject log = new JsonObject();
		log.addProperty("trigger", trigger);
		log.addProperty("tick", now);
		log.addProperty("model", s.model);
		log.addProperty("latencyMs", ms);
		log.addProperty("input", clip(userText, 3000));
		log.addProperty("text", clip(plainText.toString(), 1000));
		log.add("tools", toolLog);
		log.addProperty("stopReason", str(resp, "stop_reason"));
		if (resp.has("usage")) log.add("usage", resp.get("usage"));
		BrainLog.append(personaId, log);
	}

	/** サーバースレッドで tool_use を1つ実行して結果文を返す。 */
	private String execute(String name, JsonObject input) {
		if (member.isRemoved()) return "できない: もういない";
		if (member.isPuddle()) return "できない: 水たまりになっている";
		try {
			switch (name) {
				case "say" -> {
					String text = str(input, "text").strip();
					if (text.isEmpty()) return "できない: 発言が空";
					sayOut(text);
					return "発言した";
				}
				case "wait" -> {
					return "待機した";
				}
				default -> {
					String r = member.skills().start(name, input);
					return r == null ? "開始した" : r;
				}
			}
		} catch (RuntimeException e) {
			AiBuddyMod.LOGGER.warn("[brain] ツール実行で例外 {}: {}", name, e.toString());
			return "できない: エラー " + e.getMessage();
		}
	}

	private void sayOut(String text) {
		if (text.length() > SAY_HARD_LIMIT) text = text.substring(0, SAY_HARD_LIMIT);
		member.say(text);
		BrainManager.broadcastBotChat(this, text);
	}

	// ---------------------------------------------------------------- 失敗

	private void onFailure(int now, BrainSettings s, String trigger, String userText, List<String> sentEvents,
			Throwable err, long ms) {
		// 出来事は失われないよう戻す
		pendingEvents.addAll(0, sentEvents);
		while (pendingEvents.size() > MAX_EVENTS) pendingEvents.remove(0);

		int status = err instanceof ClaudeClient.ApiException api ? api.status : -1;
		int backoff = s.backoffSec;
		if (status == 400 || status == 401 || status == 403 || status == 404) backoff = Math.max(backoff, 300);
		blockedUntil = now + backoff * 20;
		AiBuddyMod.LOGGER.warn("[brain] API失敗 {} ({}): {} / {}秒休む", personaId, trigger, err.toString(), backoff);

		JsonObject log = new JsonObject();
		log.addProperty("trigger", trigger);
		log.addProperty("tick", now);
		log.addProperty("latencyMs", ms);
		log.addProperty("input", clip(userText, 3000));
		log.addProperty("error", err.toString());
		if (status > 0) log.addProperty("status", status);
		BrainLog.append(personaId, log);

		maybeFallback(now, 0.5);
	}

	/** 定型文を時々言う。連発しないよう 30〜60 秒のクールダウンを入れる。 */
	private void maybeFallback(int now, double probability) {
		if (now < nextFallback) return;
		if (RANDOM.nextDouble() > probability) return;
		Persona p = persona();
		if (p == null || p.fallbackLines().isEmpty()) return;
		nextFallback = now + 600 + RANDOM.nextInt(600);
		String line = p.fallbackLines().get(RANDOM.nextInt(p.fallbackLines().size()));
		sayOut(line);
		JsonObject log = new JsonObject();
		log.addProperty("tick", now);
		log.addProperty("fallback", line);
		BrainLog.append(personaId, log);
	}

	// ---------------------------------------------------------------- 履歴

	/** 直近 historyTurns 往復だけ残す。先頭が孤立した tool_result を指さないよう取り除く。 */
	private void trimHistory(BrainSettings s) {
		int max = s.historyTurns * 2;
		boolean trimmed = false;
		while (history.size() > max) {
			history.remove(0);
			history.remove(0);
			trimmed = true;
		}
		if (trimmed && !history.isEmpty()) {
			JsonObject first = history.get(0);
			JsonArray kept = new JsonArray();
			for (JsonElement el : first.getAsJsonArray("content")) {
				if (!"tool_result".equals(str(el.getAsJsonObject(), "type"))) kept.add(el);
			}
			history.set(0, message("user", kept));
		}
	}

	// ---------------------------------------------------------------- 補助

	private Persona persona() {
		return BrainManager.persona(personaId);
	}

	private static Throwable unwrap(Throwable t) {
		while ((t instanceof CompletionException) && t.getCause() != null) t = t.getCause();
		return t;
	}

	private static JsonObject textBlock(String text) {
		JsonObject b = new JsonObject();
		b.addProperty("type", "text");
		b.addProperty("text", text);
		return b;
	}

	private static JsonObject message(String role, JsonArray content) {
		JsonObject m = new JsonObject();
		m.addProperty("role", role);
		m.add("content", content);
		return m;
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
	}

	private static String clip(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max) + "...";
	}
}
