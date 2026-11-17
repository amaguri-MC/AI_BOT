package com.amaguri.aibuddy.brain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import net.fabricmc.loader.api.FabricLoader;

import com.amaguri.aibuddy.AiBuddyMod;

/** logs/aibuddy/<personaId>-<日付>.jsonl に1行1JSONで追記する。書き込みは専用スレッドで行いサーバーを止めない。 */
final class BrainLog {
	private static final Gson GSON = new Gson();
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "aibuddy-log");
		t.setDaemon(true);
		return t;
	});

	private BrainLog() {
	}

	static Path dir() {
		return FabricLoader.getInstance().getGameDir().resolve("logs").resolve("aibuddy");
	}

	static void append(String personaId, JsonObject entry) {
		entry.addProperty("time", OffsetDateTime.now().toString());
		String safeId = personaId.replaceAll("[^A-Za-z0-9_.-]", "_");
		String line = GSON.toJson(entry) + "\n";
		Path file = dir().resolve(safeId + "-" + LocalDate.now() + ".jsonl");
		IO.execute(() -> {
			try {
				Files.createDirectories(file.getParent());
				Files.writeString(file, line, StandardCharsets.UTF_8,
						StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			} catch (IOException e) {
				AiBuddyMod.LOGGER.warn("[brain] ログを書けない: {}", e.toString());
			}
		});
	}
}
