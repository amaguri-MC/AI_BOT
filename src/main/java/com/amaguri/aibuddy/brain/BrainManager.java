package com.amaguri.aibuddy.brain;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import com.amaguri.aibuddy.AiBuddyMod;
import com.amaguri.aibuddy.entity.FakeMember;

/** 脳みその入口。設定・ペルソナ・共通プロンプトの読み込みと、全 Brain の tick / チャット配信を担当する。 */
public final class BrainManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	static final String DEFAULT_COMMON_PROMPT = """
			あなたは Minecraft の世界で、他のプレイヤーと一緒に遊んでいる「{name}」というキャラクターです。上の人格設定に従って振る舞ってください。

			## 話し方
			- とにかく短く話す。チャット1回は40字以内。長文・説明口調は禁止。
			- 毎回しゃべらなくてよい。黙っていたほうが自然なときは say を使わない。
			- 数値や座標をそのまま読み上げず、人間らしい言葉にする。

			## 実況者として
			- 発見（鉱石・洞窟・敵・景色・アイテム）や状況の変化を、見つけたその場で短く報告する。
			- 近くのプレイヤーに話しかけられたら、まず反応する。

			## 行動
			- 行動は必ずツールで行う。話すときは say、何もしないときは wait。
			- 今やっていることを続けてよいときは wait でよい。
			- ツールの結果が「できない」だったら、理由を踏まえて別の手を考える。
			- 1回の応答で呼ぶツールは多くても3つまで。
			""";

	private static volatile BrainSettings settings = new BrainSettings();
	private static volatile Map<String, Persona> personas = Map.of();
	private static volatile String commonPrompt = DEFAULT_COMMON_PROMPT;

	private static final ClaudeClient CLIENT = new ClaudeClient();
	private static final Map<UUID, Brain> BRAINS = new ConcurrentHashMap<>();
	private static volatile int lastTick;
	private static boolean initialized;

	private BrainManager() {
	}

	// ---------------------------------------------------------------- 公開API（CONTRACT）

	/** 設定読込・tick登録・チャット購読。MOD初期化時に1回だけ呼ぶ。 */
	public static void init() {
		if (initialized) return;
		initialized = true;
		reload();
		ServerTickEvents.END_SERVER_TICK.register(BrainManager::onServerTick);
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) ->
				onPlayerChat(sender, message.signedContent()));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> BRAINS.clear());
	}

	/** config.json / personas / prompt_common.txt を読み直す。動作中の Brain は次の思考から新しい内容を使う。 */
	public static void reload() {
		Path dir = configDir();
		try {
			Files.createDirectories(dir.resolve("personas"));
		} catch (IOException e) {
			AiBuddyMod.LOGGER.warn("[brain] 設定フォルダを作れない: {}", e.toString());
		}
		settings = loadSettings(dir.resolve("config.json"));
		commonPrompt = loadCommonPrompt(dir.resolve("prompt_common.txt"));
		personas = loadPersonas(dir.resolve("personas"));
		AiBuddyMod.LOGGER.info("[brain] 読み込み完了: model={} persona={} key={}", settings.model, personas.keySet(),
				settings.resolveKey().isEmpty() ? "なし(定型文モード)" : "あり");
	}

	/** 無ければ null。 */
	public static Persona persona(String id) {
		return id == null ? null : personas.get(id);
	}

	public static void attach(FakeMember m) {
		BRAINS.put(m.getUUID(), new Brain(m, lastTick, settings));
	}

	public static void detach(FakeMember m) {
		BRAINS.remove(m.getUUID());
	}

	/** ボイスチャットで聞き取った言葉を、チャットで言われたのと同じ経路に流す。 */
	public static void onHeard(net.minecraft.server.level.ServerPlayer speaker, String text) {
		if (speaker == null || text == null || text.isBlank()) return;
		onPlayerChat(speaker, text);
	}

	// ---------------------------------------------------------------- パッケージ内部

	static BrainSettings settings() {
		return settings;
	}

	static ClaudeClient client() {
		return CLIENT;
	}

	/** 人格の systemPrompt に共通ルールを足したもの。 */
	static String systemPrompt(Persona p) {
		String common = commonPrompt.replace("{name}", p.displayName()).replace("{id}", p.id());
		return p.systemPrompt() + "\n\n" + common;
	}

	/** AIの発言を、近くや呼ばれた他のAIに聞かせる。 */
	static void broadcastBotChat(Brain from, String text) {
		FakeMember speaker = from.member();
		for (Brain b : BRAINS.values()) {
			if (b == from) continue;
			deliver(b, speaker.getDisplayNameText(), text, speaker, true);
		}
	}

	// ---------------------------------------------------------------- tick / チャット

	private static void onServerTick(MinecraftServer server) {
		int now = server.getTickCount();
		lastTick = now;
		for (Brain b : new ArrayList<>(BRAINS.values())) {
			if (b.member().isRemoved()) {
				BRAINS.remove(b.member().getUUID(), b);
				continue;
			}
			try {
				b.tick(server, now);
			} catch (RuntimeException e) {
				AiBuddyMod.LOGGER.error("[brain] tick で例外", e);
			}
		}
	}

	private static void onPlayerChat(ServerPlayer sender, String text) {
		if (BRAINS.isEmpty()) return;
		String name = sender.getPlainTextName();
		for (Brain b : BRAINS.values()) {
			deliver(b, name, text, sender, false);
		}
	}

	private static void deliver(Brain b, String speakerName, String text, net.minecraft.world.entity.Entity speaker,
			boolean fromBot) {
		FakeMember m = b.member();
		if (m.isRemoved()) return;
		int r = settings.chatRadius;
		boolean near = m.level() == speaker.level() && m.distanceToSqr(speaker) <= (double) r * r;
		String lower = text.toLowerCase(Locale.ROOT);
		String dn = m.getDisplayNameText();
		boolean called = (dn != null && !dn.isBlank() && lower.contains(dn.toLowerCase(Locale.ROOT)))
				|| lower.contains(b.personaIdLower());
		b.onChat(lastTick, speakerName, text, near, called, fromBot);
	}

	// ---------------------------------------------------------------- 読み込み

	private static Path configDir() {
		return FabricLoader.getInstance().getConfigDir().resolve("aibuddy");
	}

	private static BrainSettings loadSettings(Path file) {
		BrainSettings s = new BrainSettings();
		if (Files.exists(file)) {
			try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				BrainSettings read = GSON.fromJson(r, BrainSettings.class);
				if (read != null) s = read;
			} catch (IOException | RuntimeException e) {
				AiBuddyMod.LOGGER.error("[brain] config.json を読めない。既定値を使う: {}", e.toString());
			}
		} else {
			try {
				Files.writeString(file, GSON.toJson(s), StandardCharsets.UTF_8);
			} catch (IOException e) {
				AiBuddyMod.LOGGER.warn("[brain] config.json を書き出せない: {}", e.toString());
			}
		}
		s.clamp();
		return s;
	}

	private static String loadCommonPrompt(Path file) {
		if (!Files.exists(file)) {
			try {
				Files.writeString(file, DEFAULT_COMMON_PROMPT, StandardCharsets.UTF_8);
			} catch (IOException e) {
				AiBuddyMod.LOGGER.warn("[brain] prompt_common.txt を書き出せない: {}", e.toString());
			}
			return DEFAULT_COMMON_PROMPT;
		}
		try {
			String t = Files.readString(file, StandardCharsets.UTF_8);
			return t.isBlank() ? DEFAULT_COMMON_PROMPT : t;
		} catch (IOException e) {
			AiBuddyMod.LOGGER.error("[brain] prompt_common.txt を読めない。既定文を使う: {}", e.toString());
			return DEFAULT_COMMON_PROMPT;
		}
	}

	private static Map<String, Persona> loadPersonas(Path dir) {
		Map<String, Persona> out = new LinkedHashMap<>();
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
			List<Path> files = new ArrayList<>();
			ds.forEach(files::add);
			Collections.sort(files);
			for (Path f : files) {
				try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
					Persona p = parsePersona(f, GSON.fromJson(r, JsonObject.class));
					out.put(p.id(), p);
				} catch (IOException | RuntimeException e) {
					AiBuddyMod.LOGGER.error("[brain] ペルソナを読めない {}: {}", f.getFileName(), e.toString());
				}
			}
		} catch (IOException e) {
			AiBuddyMod.LOGGER.warn("[brain] personas フォルダを読めない: {}", e.toString());
		}
		return Map.copyOf(out);
	}

	/** id は省略するとファイル名。systemPrompt は文字列でも文字列の配列（行ごと）でもよい。 */
	private static Persona parsePersona(Path file, JsonObject o) {
		String fileName = file.getFileName().toString();
		String id = str(o, "id", fileName.substring(0, fileName.length() - ".json".length()));
		String displayName = str(o, "displayName", id);
		String skin = str(o, "skin", displayName);

		String prompt = "";
		JsonElement sp = o.get("systemPrompt");
		if (sp != null && sp.isJsonArray()) {
			List<String> lines = new ArrayList<>();
			for (JsonElement e : sp.getAsJsonArray()) lines.add(e.getAsString());
			prompt = String.join("\n", lines);
		} else if (sp != null && sp.isJsonPrimitive()) {
			prompt = sp.getAsString();
		}

		List<String> fallback = new ArrayList<>();
		JsonElement fl = o.get("fallbackLines");
		if (fl != null && fl.isJsonArray()) {
			for (JsonElement e : fl.getAsJsonArray()) {
				if (!e.getAsString().isBlank()) fallback.add(e.getAsString());
			}
		}
		return new Persona(id, displayName, skin, prompt, List.copyOf(fallback));
	}

	private static String str(JsonObject o, String key, String def) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() && !e.getAsString().isBlank() ? e.getAsString() : def;
	}
}
