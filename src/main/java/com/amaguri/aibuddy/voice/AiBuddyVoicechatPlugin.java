package com.amaguri.aibuddy.voice;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;

import com.amaguri.aibuddy.AiBuddyMod;

/**
 * Simple Voice Chat の "voicechat" エントリポイント。SVC のクラスを触るのはこのクラスだけ。
 * SVC が入っていなければ Fabric がこのクラスを読み込まないので、MOD は落ちない。
 * （SVC API のメソッド名は ref-svc の api ソースで確認済み）
 */
public class AiBuddyVoicechatPlugin implements VoicechatPlugin {
	private VoicechatApi api;
	private VoiceProcessor processor;
	private final Map<UUID, OpusDecoder> decoders = new ConcurrentHashMap<>();

	@Override
	public String getPluginId() {
		return "aibuddy";
	}

	@Override
	public void initialize(VoicechatApi api) {
		this.api = api;
		VoiceConfig cfg = VoiceConfig.load();
		if (!cfg.enabled) {
			AiBuddyMod.LOGGER.info("[voice] voice.json で無効になっている");
			return;
		}
		processor = new VoiceProcessor(cfg);
		AiBuddyMod.LOGGER.info("[voice] 有効: {}", cfg.whisperUrl);
	}

	@Override
	public void registerEvents(EventRegistration registration) {
		registration.registerEvent(MicrophonePacketEvent.class, this::onMic);
	}

	private void onMic(MicrophonePacketEvent e) {
		if (processor == null) return;
		VoicechatConnection con = e.getSenderConnection();
		if (con == null || con.getPlayer() == null) return;
		UUID id = con.getPlayer().getUuid();
		if (!processor.isNear(id)) return; // 近くにAIボットが居ない人の声は処理しない
		byte[] opus = e.getPacket().getOpusEncodedData();
		if (opus == null || opus.length == 0) return; // 空パケットは送信終了の合図
		OpusDecoder dec = decoders.computeIfAbsent(id, k -> api.createDecoder());
		short[] pcm;
		synchronized (dec) {
			pcm = dec.decode(opus);
		}
		if (pcm != null && pcm.length > 0) processor.accept(id, pcm);
	}
}
