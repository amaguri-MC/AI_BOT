package com.amaguri.aibuddy.voice;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

import com.amaguri.aibuddy.AiBuddyMod;
import com.amaguri.aibuddy.brain.BrainManager;
import com.amaguri.aibuddy.entity.FakeMember;

/**
 * 声の PCM を溜めて発話に区切り、Whisper に投げて BrainManager.onHeard に渡す。
 * SVC のクラスは一切触らない（SVC 無しでもロードできる）。
 */
final class VoiceProcessor {
	static final int RATE = 48000;
	private static final long SILENCE_MS = 600;
	private static final int MIN_SAMPLES = (int) (RATE * 0.4);
	private static final int MAX_SAMPLES = RATE * 20;

	private static final class Buf {
		short[] data = new short[RATE * 2];
		int len;
		long lastMs;
	}

	private final VoiceConfig cfg;
	private final WhisperClient whisper = new WhisperClient();
	private final Map<UUID, Buf> bufs = new ConcurrentHashMap<>();
	/** 近くにAIボットが居るプレイヤー。メインスレッドで更新し、音声スレッドから読む。 */
	private final Map<UUID, Boolean> nearPlayers = new ConcurrentHashMap<>();
	private final ExecutorService worker = Executors.newSingleThreadExecutor(daemon("aibuddy-whisper"));
	private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(daemon("aibuddy-voice-timer"));
	private volatile MinecraftServer server;
	private boolean warned;

	VoiceProcessor(VoiceConfig cfg) {
		this.cfg = cfg;
		ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			server = null;
			bufs.clear();
		});
		ServerTickEvents.END_SERVER_TICK.register(this::updateNear);
		timer.scheduleWithFixedDelay(this::flushSilent, 100, 100, TimeUnit.MILLISECONDS);
	}

	private static java.util.concurrent.ThreadFactory daemon(String name) {
		return r -> {
			Thread t = new Thread(r, name);
			t.setDaemon(true);
			return t;
		};
	}

	private void updateNear(MinecraftServer s) {
		if (s.getTickCount() % 20 != 0) return;
		double r = cfg.radius;
		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			AABB box = p.getBoundingBox().inflate(r);
			boolean near = !p.level().getEntitiesOfClass(FakeMember.class, box).isEmpty();
			if (near) nearPlayers.put(p.getUUID(), Boolean.TRUE);
			else nearPlayers.remove(p.getUUID());
		}
	}

	boolean isNear(UUID id) {
		return nearPlayers.containsKey(id);
	}

	/** 復号済み PCM(48kHz mono) を溜める。音声スレッドから呼ばれる。 */
	void accept(UUID id, short[] pcm) {
		Buf b = bufs.computeIfAbsent(id, k -> new Buf());
		synchronized (b) {
			if (b.len + pcm.length > b.data.length) {
				b.data = Arrays.copyOf(b.data, Math.max(b.data.length * 2, b.len + pcm.length));
			}
			System.arraycopy(pcm, 0, b.data, b.len, pcm.length);
			b.len += pcm.length;
			b.lastMs = System.currentTimeMillis();
			if (b.len >= MAX_SAMPLES) cut(id, b);
		}
	}

	void forget(UUID id) {
		bufs.remove(id);
		nearPlayers.remove(id);
	}

	private void flushSilent() {
		try {
			long now = System.currentTimeMillis();
			for (Map.Entry<UUID, Buf> e : bufs.entrySet()) {
				Buf b = e.getValue();
				synchronized (b) {
					if (b.len > 0 && now - b.lastMs >= SILENCE_MS) cut(e.getKey(), b);
				}
			}
		} catch (RuntimeException ex) {
			AiBuddyMod.LOGGER.warn("[voice] 区切り処理で例外: {}", ex.toString());
		}
	}

	/** b のロックを持った状態で呼ぶ。発話を切り出して Whisper に送る。 */
	private void cut(UUID id, Buf b) {
		int len = b.len;
		b.len = 0;
		if (len < MIN_SAMPLES) return; // 0.4秒未満は捨てる
		short[] pcm16 = WavUtil.downsample48to16(b.data, len);
		byte[] wav = WavUtil.toWav(pcm16, 16000);
		worker.execute(() -> transcribeAndDeliver(id, wav));
	}

	private void transcribeAndDeliver(UUID id, byte[] wav) {
		String text;
		try {
			text = whisper.transcribe(cfg, wav);
		} catch (Exception e) {
			if (!warned) {
				warned = true;
				AiBuddyMod.LOGGER.warn("[voice] Whisper に繋げない ({}): {}", cfg.whisperUrl, e.toString());
			}
			return;
		}
		warned = false;
		if (text == null) return;
		MinecraftServer s = server;
		if (s == null) return;
		final String heard = text;
		s.execute(() -> {
			ServerPlayer p = s.getPlayerList().getPlayer(id);
			if (p == null) return;
			AiBuddyMod.LOGGER.info("[voice] {}: {}", p.getPlainTextName(), heard);
			BrainManager.onHeard(p, heard);
		});
	}
}
