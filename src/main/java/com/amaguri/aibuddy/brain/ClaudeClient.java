package com.amaguri.aibuddy.brain;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Claude Messages API への非同期クライアント。ゲーム側の状態には触らない（どのスレッドからでも呼べる）。 */
final class ClaudeClient {
	private static final URI ENDPOINT = URI.create("https://api.anthropic.com/v1/messages");
	private static final String API_VERSION = "2023-06-01";
	private static final Gson GSON = new Gson();

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	/** HTTP 200 以外。status が 429 / 5xx ならしばらく待てば直る可能性が高い。 */
	static final class ApiException extends RuntimeException {
		final int status;

		ApiException(int status, String body) {
			super("HTTP " + status + ": " + (body.length() > 300 ? body.substring(0, 300) + "..." : body));
			this.status = status;
		}
	}

	/** Messages API のリクエスト本文を組む。tools / tool_use / tool_result は API の仕様どおり。 */
	static JsonObject buildBody(BrainSettings s, String system, JsonArray tools, JsonArray messages) {
		JsonObject body = new JsonObject();
		body.addProperty("model", s.model);
		body.addProperty("max_tokens", s.maxTokens);
		// プロンプトキャッシュ: tools の最後と system のブロックに ephemeral を付ける（毎回同じ内容なので読み取りが効く）
		if (tools.size() > 0 && tools.get(tools.size() - 1).isJsonObject()) {
			tools.get(tools.size() - 1).getAsJsonObject().add("cache_control", ephemeral());
		}
		JsonObject sysBlock = new JsonObject();
		sysBlock.addProperty("type", "text");
		sysBlock.addProperty("text", system);
		sysBlock.add("cache_control", ephemeral());
		JsonArray sysArr = new JsonArray();
		sysArr.add(sysBlock);
		body.add("system", sysArr);
		body.add("tools", tools);
		body.add("messages", messages);
		return body;
	}

	private static JsonObject ephemeral() {
		JsonObject c = new JsonObject();
		c.addProperty("type", "ephemeral");
		return c;
	}

	/** 成功時はレスポンス JSON、失敗時は例外（ApiException / IOException / TimeoutException など）で完了する。 */
	CompletableFuture<JsonObject> send(String apiKey, BrainSettings s, JsonObject body) {
		HttpRequest req = HttpRequest.newBuilder(ENDPOINT)
				.timeout(Duration.ofSeconds(s.requestTimeoutSec))
				.header("x-api-key", apiKey)
				.header("anthropic-version", API_VERSION)
				.header("content-type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
				.build();
		return http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
				.thenApply(res -> {
					if (res.statusCode() != 200) throw new ApiException(res.statusCode(), res.body());
					return GSON.fromJson(res.body(), JsonObject.class);
				})
				// 本文の読み込みが止まった場合の保険
				.orTimeout(s.requestTimeoutSec + 5L, TimeUnit.SECONDS);
	}
}
