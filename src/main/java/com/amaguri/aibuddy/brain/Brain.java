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
 * 1体ぶんの脳（W4: 喋り役）。ゲームの進行は Autopilot が決めるので、ここは「出来事・チャット・声」に反応して
 * 喋るのと、たまに follow_player / resume_run で割り込むだけ。「次どうする？」と聞いたり指示を待ったりしない。
 *
 * 入力トークンを抑える作り（実機ログで input_tokens が毎回 9千 まで膨らんだ原因への対策）:
 *  - 原因1: 会話履歴を12往復ぶん毎回送っていた（各往復に状況文＋チャット全文が入っていた）→ 履歴は持たず毎回1通だけ送る
 *  - 原因2: 返事済みのチャットを10件ずっと再送していた → 未返信のチャットだけを最大6件送り、送ったら印を付ける
 *  - 原因3: 「暇だから8秒ごと」に呼んでいた → 出来事/チャット/声のときだけ。暇つぶしの独り言は45秒に1回まで、
 *    近くに人がいない・早送り中は呼ばない
 *  - system と tools は毎回同じ内容にして cache_control(ephemeral) を付け、キャッシュが効けば読み取り単価になる
 * 1回の入力は system(約700) + tools(約350) + user(約250〜500) で概ね3千トークン以下に収まる。
 * フィールドはすべてサーバースレッドからだけ触る（HTTP は別スレッドだが、結果は server.execute で戻して処理する）。
 */
final class Brain {
	/** 未返信チャットを送る最大件数。 */
	private static final int MAX_CHATS = 6;
	private static final int MAX_EVENTS = 8;
	private static final int MAX_MY_LINES = 3;
	private static final int SAY_HARD_LIMIT = 120;
	private static final int FOLLOW_PAUSE_TICKS = 60 * 20;
	private static final Random RANDOM = new Random();

	private static final class Chat {
		final int tick;
		final String name;
		final String text;
		final boolean voice;
		boolean sent;

		Chat(int tick, String name, String text, boolean voice) {
			this.tick = tick;
			this.name = name;
			this.text = text;
			this.voice = voice;
		}
	}

	private final FakeMember member;
	private final String personaId;

	private final List<String> pendingEvents = new ArrayList<>();
	private final Deque<Chat> chats = new ArrayDeque<>();
	/** 自分が最近言ったこと（言い直し防止用。履歴の代わり）。 */
	private final Deque<String> myLines = new ArrayDeque<>();
	/** 前回のツール結果。次の入力に1度だけ載せる。 */
	private final List<String> lastToolResults = new ArrayList<>();

	private boolean chatPending;
	private boolean inFlight;
	private int lastThink;
	private int lastIdleTalk;
	private int blockedUntil;
	private int nextFallback;
	private int nextPuddleLine = -1;
	private boolean warnedNoKey;

	Brain(FakeMember member, int now, BrainSettings s) {
		this.member = member;
		this.personaId = member.getPersonaId();
		// 湧いて2秒後くらいに最初の挨拶をする
		this.lastThink = now - s.minGapSec * 20 + 40;
		this.lastIdleTalk = now - s.idleTalkSec * 20 + 40;
	}

	FakeMember member() {
		return member;
	}

	String personaIdLower() {
		return personaId.toLowerCase(java.util.Locale.ROOT);
	}

	// ---------------------------------------------------------------- 入力

	/** チャット/声を受け取る。near=半径内、called=名前を呼ばれた、fromBot=他のAIの発言、voice=声で聞こえた。 */
	void onChat(int now, String name, String text, boolean near, boolean called, boolean fromBot, boolean voice) {
		if (!near && !called) return;
		// スタートの合図は LLM を待たずに即開始（近くのプレイヤーの声・チャットのみ。他のAIの発言では始めない）
		if (!fromBot && near && StartCue.isStartCue(text)) startRun();
		chats.addLast(new Chat(now, name, text, voice));
		while (chats.size() > MAX_CHATS) chats.removeFirst();
		if (!fromBot || called) chatPending = true;
	}

	/** 実行中＝開始済みで有効。再起動後・/aibuddy run on|off でも Autopilot の実状態を見るのでずれない。 */
	private boolean running() {
		try {
			return member.autopilot().isStarted() && member.autopilot().isEnabled();
		} catch (RuntimeException e) {
			return false;
		}
	}

	/** 実行中なら何もしない。run off 後の再スタートも start() が有効化する。 */
	private String startRun() {
		if (running()) return "もう始めている";
		member.autopilot().start();
		return "スタートした。自動進行を始めた";
	}

	// ---------------------------------------------------------------- tick

	void tick(MinecraftServer server, int now) {
		for (String e : member.drainEvents()) {
			if (pendingEvents.size() >= MAX_EVENTS) pendingEvents.remove(0);
			pendingEvents.add(e);
		}

		BrainSettings s = BrainManager.settings();

		if (member.isPuddle()) {
			puddleTick(server, now, s);
			return;
		}
		nextPuddleLine = -1;

		// 早送り中は誰も見ていない。出来事が溜まって一斉に喋らないよう捨てる（チャット/声が来たら早送りは解けている）
		if (!chatPending && isFastForwarding()) {
			pendingEvents.clear();
			return;
		}

		if (inFlight) return;
		if (now - lastThink < s.minGapSec * 20) return;

		String trigger = null;
		if (pendingEvents.stream().anyMatch(Brain::isReportWorthy)) trigger = "event";
		else if (chatPending) trigger = "chat";
		else if (now - lastIdleTalk >= s.idleTalkSec * 20 && playerNearby(s)) trigger = "interval";
		if (trigger == null) return;

		think(server, now, s, trigger);
	}

	// UNVERIFIED: FakeMember.autopilot() / Autopilot の各メソッドは W3 担当が実装中（CONTRACT の署名どおりに呼ぶ）。
	private boolean isFastForwarding() {
		try {
			return member.autopilot().isFastForwarding();
		} catch (RuntimeException e) {
			return false;
		}
	}

	private boolean playerNearby(BrainSettings s) {
		// hasNearbyAlivePlayer は apidump 確認済み（EntityGetter の default メソッド、Level が実装）
		return member.level().hasNearbyAlivePlayer(member.getX(), member.getY(), member.getZ(), s.chatRadius);
	}

	/** 水たまり中: 話しかけられたとき＋90秒に1回までの独り言だけ LLM を呼ぶ。出来事は無視する。 */
	private void puddleTick(MinecraftServer server, int now, BrainSettings s) {
		pendingEvents.clear();
		if (nextPuddleLine < 0) nextPuddleLine = now + 600 + RANDOM.nextInt(600);
		if (inFlight || now - lastThink < s.minGapSec * 20) return;
		String trigger = null;
		if (chatPending) trigger = "puddle_chat";
		else if (now >= nextPuddleLine && playerNearby(s)) trigger = "puddle_idle";
		if (trigger == null) return;
		nextPuddleLine = now + 1800 + RANDOM.nextInt(1200);
		think(server, now, s, trigger);
	}

	private boolean puddleMode() {
		return member.isPuddle();
	}

	// ---------------------------------------------------------------- 思考

	private void think(MinecraftServer server, int now, BrainSettings s, String trigger) {
		lastThink = now;
		if ("interval".equals(trigger)) lastIdleTalk = now;
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
			markChatsSent();
			maybeFallback(now, 1.0);
			return;
		}
		if (now < blockedUntil) {
			chatPending = false;
			markChatsSent();
			maybeFallback(now, 0.5);
			return;
		}

		boolean puddle = puddleMode();
		String userText = puddle ? buildPuddleText(now, trigger) : buildUserText(now, trigger);
		// 履歴は持たず毎回この1通だけ（トークン節約）
		JsonArray messages = new JsonArray();
		JsonArray content = new JsonArray();
		content.add(textBlock(userText));
		messages.add(message("user", content));

		JsonArray tools = puddle ? buildPuddleTools() : buildTools();
		String system = BrainManager.systemPrompt(p) + (puddle ? BrainManager.PUDDLE_PROMPT : "");
		JsonObject body = ClaudeClient.buildBody(s, system, tools, messages);

		List<String> sentEvents = new ArrayList<>(pendingEvents);
		List<Chat> sentChats = new ArrayList<>();
		for (Chat c : chats) if (!c.sent) sentChats.add(c);
		pendingEvents.clear();
		lastToolResults.clear();
		chatPending = false;
		markChatsSent();
		inFlight = true;
		long t0 = System.nanoTime();

		BrainManager.client().send(key, s, body).whenComplete((resp, err) -> server.execute(() -> {
			inFlight = false;
			long ms = (System.nanoTime() - t0) / 1_000_000L;
			int tickNow = server.getTickCount();
			if (err != null) {
				onFailure(tickNow, s, trigger, userText, sentEvents, sentChats, unwrap(err), ms);
			} else {
				try {
					onResponse(tickNow, s, trigger, userText, resp, ms);
				} catch (RuntimeException e) {
					AiBuddyMod.LOGGER.error("[brain] 応答の処理に失敗: " + personaId, e);
					onFailure(tickNow, s, trigger, userText, sentEvents, sentChats, e, ms);
				}
			}
		}));
	}

	private void markChatsSent() {
		for (Chat c : chats) c.sent = true;
	}

	/** 入力文。状況は Autopilot の段階・目標を先頭に置く。チャット行には (声)/(チャット) を付ける。 */
	private String buildUserText(int now, String trigger) {
		SkillRunner sk = member.skills();
		StringBuilder sb = new StringBuilder();
		sb.append("進行状態: ").append(running() ? "進行中" : "スタート待ち（合図があるまで体は動かない。雑談して待つ）").append('\n');
		sb.append("今の段階: ").append(safe(() -> member.autopilot().stageName())).append('\n');
		sb.append("目標: ").append(safe(() -> member.autopilot().goalText())).append('\n');
		sb.append("状況: ").append(safe(sk::observe)).append('\n');
		sb.append("今やっていること: ").append(safe(sk::status)).append('\n');
		sb.append("新しい出来事:");
		if (pendingEvents.isEmpty()) sb.append(" なし");
		for (String e : pendingEvents) sb.append("\n- ").append(e);

		sb.append("\n未返信のチャット/声:");
		boolean any = false;
		for (Chat c : chats) {
			if (c.sent) continue;
			any = true;
			sb.append("\n- [").append(Math.max(0, (now - c.tick) / 20)).append("秒前] (")
					.append(c.voice ? "声" : "チャット").append(") ")
					.append(c.name).append(": ").append(c.text);
		}
		if (!any) sb.append(" なし");

		if (!myLines.isEmpty()) {
			sb.append("\n自分が最近言ったこと（繰り返さない）:");
			for (String l : myLines) sb.append("\n- ").append(l);
		}
		if (!lastToolResults.isEmpty()) {
			sb.append("\n前回のツール結果: ").append(String.join(" / ", lastToolResults));
		}
		if ("interval".equals(trigger)) {
			sb.append("\n(特に呼ばれていない。言いたいことがあれば短く独り言、無ければ wait)");
		}
		return sb.toString();
	}

	/** 水たまり中の入力。状況・目標は不要なので載せない（トークン節約）。 */
	private String buildPuddleText(int now, String trigger) {
		StringBuilder sb = new StringBuilder("完全に溶けて水たまり。体は動かない。声も途切れ途切れ\n未返信のチャット/声:");
		boolean any = false;
		for (Chat c : chats) {
			if (c.sent) continue;
			any = true;
			sb.append("\n- (").append(c.voice ? "声" : "チャット").append(") ").append(c.name).append(": ").append(c.text);
		}
		if (!any) sb.append(" なし（独り言を一言か、wait）");
		return sb.toString();
	}

	/** 水たまり中は say と wait だけ。 */
	static JsonArray buildPuddleTools() {
		JsonArray all = buildTools();
		JsonArray out = new JsonArray();
		for (JsonElement e : all) {
			String n = str(e.getAsJsonObject(), "name");
			if ("say".equals(n) || "wait".equals(n)) out.add(e);
		}
		return out;
	}

	private static String safe(java.util.function.Supplier<String> f) {
		try {
			String v = f.get();
			return v == null || v.isBlank() ? "不明" : v;
		} catch (RuntimeException e) {
			return "取得失敗: " + e.getMessage();
		}
	}

	/** 脳が使うツールは5つだけ（say / wait / start_run / follow_player / resume_run）。毎回同じ内容にしてキャッシュを効かせる。 */
	static JsonArray buildTools() {
		JsonArray tools = new JsonArray();

		JsonObject say = new JsonObject();
		say.addProperty("name", "say");
		say.addProperty("description", "チャットで発言する（声が聞こえている相手にもそのまま届く）。1回40字以内で短く。");
		JsonObject sayProps = new JsonObject();
		JsonObject text = new JsonObject();
		text.addProperty("type", "string");
		text.addProperty("description", "発言内容（40字以内）");
		sayProps.add("text", text);
		say.add("input_schema", schema(sayProps, "text"));
		tools.add(say);

		JsonObject wait = new JsonObject();
		wait.addProperty("name", "wait");
		wait.addProperty("description", "言うことが無いとき黙っているために使う。ゲームの進行は自動で続くので、これで止まることは無い。");
		wait.add("input_schema", schema(new JsonObject()));
		tools.add(wait);

		JsonObject follow = new JsonObject();
		follow.addProperty("name", "follow_player");
		follow.addProperty("description", "プレイヤーに頼まれたときだけ。60秒間、自動進行を止めてそのプレイヤーについていく。");
		JsonObject fp = new JsonObject();
		JsonObject player = new JsonObject();
		player.addProperty("type", "string");
		player.addProperty("description", "ついていくプレイヤー名（省略すると一番近い人）");
		fp.add("player", player);
		follow.add("input_schema", schema(fp));
		tools.add(follow);

		JsonObject startT = new JsonObject();
		startT.addProperty("name", "start_run");
		startT.addProperty("description", "スタート待ちのとき、プレイヤーが開始を頼んだら呼ぶ。自動進行（エンドラ討伐までの攻略）が始まる。");
		startT.add("input_schema", schema(new JsonObject()));
		tools.add(startT);

		JsonObject resume = new JsonObject();
		resume.addProperty("name", "resume_run");
		resume.addProperty("description", "ついていくのをやめて、自動進行（エンドラ討伐に向けた攻略）に戻る。");
		resume.add("input_schema", schema(new JsonObject()));
		tools.add(resume);
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

	private void onResponse(int now, BrainSettings s, String trigger, String userText, JsonObject resp, long ms) {
		JsonArray toolLog = new JsonArray();
		StringBuilder plainText = new StringBuilder();
		JsonElement raw = resp.get("content");
		if (raw != null && raw.isJsonArray()) {
			for (JsonElement el : raw.getAsJsonArray()) {
				if (!el.isJsonObject()) continue;
				JsonObject b = el.getAsJsonObject();
				String type = str(b, "type");
				if ("text".equals(type)) {
					plainText.append(str(b, "text"));
				} else if ("tool_use".equals(type)) {
					String name = str(b, "name");
					JsonObject input = b.has("input") && b.get("input").isJsonObject() ? b.getAsJsonObject("input") : new JsonObject();
					String result = execute(name, input);
					if (!"say".equals(name) && !"wait".equals(name)) lastToolResults.add(name + ": " + result);
					JsonObject tl = new JsonObject();
					tl.addProperty("name", name);
					tl.add("input", input);
					tl.addProperty("result", result);
					toolLog.add(tl);
				}
			}
		}

		JsonObject log = new JsonObject();
		log.addProperty("trigger", trigger);
		log.addProperty("tick", now);
		log.addProperty("model", s.model);
		log.addProperty("latencyMs", ms);
		log.addProperty("input", clip(userText, 3000));
		log.addProperty("text", clip(plainText.toString(), 1000));
		log.add("tools", toolLog);
		log.addProperty("stopReason", str(resp, "stop_reason"));
		if (resp.has("usage") && resp.get("usage").isJsonObject()) {
			JsonObject u = resp.getAsJsonObject("usage");
			log.add("usage", u);
			int in = num(u, "input_tokens");
			int cr = num(u, "cache_read_input_tokens");
			int cc = num(u, "cache_creation_input_tokens");
			log.addProperty("cacheRead", cr);
			log.addProperty("cacheCreate", cc);
			AiBuddyMod.LOGGER.info("[brain] {} ({}) input={} cache_read={} cache_create={} output={} {}ms",
					personaId, trigger, in, cr, cc, num(u, "output_tokens"), ms);
		}
		BrainLog.append(personaId, log);
	}

	/** サーバースレッドで tool_use を1つ実行して結果文を返す。 */
	private String execute(String name, JsonObject input) {
		if (member.isRemoved()) return "できない: もういない";
		if (member.isPuddle() && !"say".equals(name) && !"wait".equals(name)) return "できない: 水たまりになっている";
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
				case "follow_player" -> {
					// UNVERIFIED: W2 の follow_player スキルの入力は {player?}（無ければ一番近い人）と仮定
					// Autopilot を60秒止めてから、W2 の follow_player スキルを始める
					member.autopilot().pause(FOLLOW_PAUSE_TICKS, "follow_player");
					String r = member.skills().start("follow_player", input);
					if (r != null && r.startsWith("できない")) {
						member.autopilot().resume();
						return r;
					}
					return r == null ? "ついていく（60秒）" : r;
				}
				case "start_run" -> {
					return startRun();
				}
				case "resume_run" -> {
					member.skills().stop();
					member.autopilot().resume();
					return "自動進行に戻った";
				}
				default -> {
					return "できない: 知らないツール " + name;
				}
			}
		} catch (RuntimeException e) {
			AiBuddyMod.LOGGER.warn("[brain] ツール実行で例外 {}: {}", name, e.toString());
			return "できない: エラー " + e.getMessage();
		}
	}

	private void sayOut(String text) {
		if (text.length() > SAY_HARD_LIMIT) text = text.substring(0, SAY_HARD_LIMIT);
		// 水たまり中は15字以内に切り、「…」で区切る。崩し(Slur)は member.say が溶け具合に応じて最大でかける
		if (member.isPuddle()) text = com.amaguri.aibuddy.entity.Slur.fragment(text, member.getRandom());
		member.say(text);
		myLines.addLast(text);
		while (myLines.size() > MAX_MY_LINES) myLines.removeFirst();
		BrainManager.broadcastBotChat(this, text);
	}

	// ---------------------------------------------------------------- 失敗

	private void onFailure(int now, BrainSettings s, String trigger, String userText, List<String> sentEvents,
			List<Chat> sentChats, Throwable err, long ms) {
		// 出来事と未返信チャットは失われないよう戻す
		pendingEvents.addAll(0, sentEvents);
		while (pendingEvents.size() > MAX_EVENTS) pendingEvents.remove(0);
		for (Chat c : sentChats) c.sent = false;

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

	private static int num(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsInt() : 0;
	}

	private static String clip(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max) + "...";
	}

	/** 喋るきっかけにする大事な出来事か（原木・石・道具づくり等の細かい進みは、次に喋るときの文脈に回すだけ）。 */
	static boolean isReportWorthy(String e) {
		return java.util.regex.Pattern.compile(
				"あった|見つけ|座標|ゲート|ブレ|ロッド|パール|アイ|要塞|砦|村|沈没船|宝|ネザー|エンド|ドラ|クリスタル|死んだ|リスポーン|体力が少ない|たこ焼き|溶け|スタートした|移動した")
				.matcher(e).find();
	}
}
