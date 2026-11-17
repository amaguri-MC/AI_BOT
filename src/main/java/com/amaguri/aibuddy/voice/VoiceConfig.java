package com.amaguri.aibuddy.voice;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

import com.amaguri.aibuddy.AiBuddyMod;

/** config/aibuddy/voice.json。無ければ既定値で書き出す。 */
public final class VoiceConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	public boolean enabled = true;
	public String whisperUrl = "http://127.0.0.1:8080/inference";
	public String language = "ja";
	/** この半径(ブロック)内に偽物が居るプレイヤーの声だけ処理する。 */
	public double radius = 64;

	public static VoiceConfig load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("aibuddy").resolve("voice.json");
		VoiceConfig c = new VoiceConfig();
		try {
			if (Files.exists(file)) {
				try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
					VoiceConfig read = GSON.fromJson(r, VoiceConfig.class);
					if (read != null) c = read;
				}
			} else {
				Files.createDirectories(file.getParent());
				Files.writeString(file, GSON.toJson(c), StandardCharsets.UTF_8);
			}
		} catch (IOException | RuntimeException e) {
			AiBuddyMod.LOGGER.warn("[voice] voice.json を読めない。既定値を使う: {}", e.toString());
		}
		if (c.whisperUrl == null || c.whisperUrl.isBlank()) c.whisperUrl = "http://127.0.0.1:8080/inference";
		if (c.language == null || c.language.isBlank()) c.language = "ja";
		if (c.radius < 1) c.radius = 64;
		return c;
	}
}
