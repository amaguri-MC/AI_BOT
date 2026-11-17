package com.amaguri.aibuddy.voice;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** whisper.cpp server (/inference) に WAV を multipart で投げる。 */
final class WhisperClient {
	private static final List<String> HALLUCINATIONS = List.of("ご視聴ありがとうございました", "ご視聴ありがとうございます",
			"チャンネル登録", "最後までご視聴", "字幕", "おやすみなさい", "ありがとうございました", "[音楽]", "(音楽)", "(拍手)", "[拍手]", "(笑)",
			"Thank you for watching", "Thanks for watching");

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

	/** 文字にして返す。失敗や幻聴は null。 */
	String transcribe(VoiceConfig cfg, byte[] wav) throws Exception {
		String boundary = "----aibuddy" + UUID.randomUUID().toString().replace("-", "");
		ByteArrayOutputStream b = new ByteArrayOutputStream(wav.length + 512);
		field(b, boundary, "language", cfg.language);
		field(b, boundary, "response_format", "json");
		b.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"voice.wav\"\r\n"
				+ "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		b.writeBytes(wav);
		b.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

		HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.whisperUrl)).timeout(Duration.ofSeconds(60))
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.POST(HttpRequest.BodyPublishers.ofByteArray(b.toByteArray())).build();
		HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (res.statusCode() / 100 != 2) throw new IllegalStateException("whisper HTTP " + res.statusCode());
		JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
		if (!o.has("text") || o.get("text").isJsonNull()) return null;
		return clean(o.get("text").getAsString());
	}

	static String clean(String raw) {
		String t = raw.replace('\n', ' ').trim();
		if (t.isEmpty()) return null;
		String stripped = t.replaceAll("[\\s\\p{Punct}。、！？…・「」『』（）()\\[\\]]+", "");
		if (stripped.isEmpty()) return null;
		for (String h : HALLUCINATIONS) {
			String hs = h.replaceAll("[\\s\\p{Punct}。、！？…・「」『』（）()\\[\\]]+", "");
			if (stripped.equalsIgnoreCase(hs)) return null;
		}
		return t;
	}

	private static void field(ByteArrayOutputStream b, String boundary, String name, String value) {
		b.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value
				+ "\r\n").getBytes(StandardCharsets.UTF_8));
	}
}
