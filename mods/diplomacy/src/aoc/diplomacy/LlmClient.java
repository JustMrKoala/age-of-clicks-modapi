package aoc.diplomacy;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal client for any OpenAI-compatible {@code /chat/completions} endpoint. */
final class LlmClient {
    record Message(String role, String content) {}

    /** Thrown for HTTP 429 so the caller can wait and try again. */
    static final class RateLimited extends IllegalStateException {
        final double retryAfterSeconds;

        RateLimited(String message, double retryAfterSeconds) {
            super(message);
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    private static final int RETRIES = 3;
    private static final double MAX_WAIT_SECONDS = 30;
    private static final Pattern TRY_AGAIN = Pattern.compile("try again in ([0-9.]+)\\s*(ms|s)");

    private final Config config;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    LlmClient(Config config) {
        this.config = config;
    }

    /** Completes with the assistant's text, or fails with a readable error. Rate limits are retried. */
    CompletableFuture<String> chat(List<Message> messages) {
        return attempt(messages, 0);
    }

    private CompletableFuture<String> attempt(List<Message> messages, int tries) {
        return send(messages).handle((text, err) -> {
            Throwable cause = err != null && err.getCause() != null ? err.getCause() : err;
            if (cause instanceof RateLimited r && tries < RETRIES) {
                long waitMs = (long) (Math.min(MAX_WAIT_SECONDS, r.retryAfterSeconds + 0.5) * 1000);
                return CompletableFuture.supplyAsync(() -> null, CompletableFuture.delayedExecutor(waitMs, TimeUnit.MILLISECONDS))
                        .thenCompose(x -> attempt(messages, tries + 1));
            }
            return err != null ? CompletableFuture.<String>failedFuture(cause) : CompletableFuture.completedFuture(text);
        }).thenCompose(f -> f);
    }

    private CompletableFuture<String> send(List<Message> messages) {
        StringBuilder body = new StringBuilder();
        body.append("{\"model\":").append(quote(config.model()))
            .append(",\"temperature\":").append(config.temperature())
            .append(",\"max_tokens\":").append(config.maxTokens());
        if (config.jsonMode()) body.append(",\"response_format\":{\"type\":\"json_object\"}");
        body.append(",\"messages\":[");
        for (int i = 0; i < messages.size(); i++) {
            Message m = messages.get(i);
            if (i > 0) body.append(',');
            body.append("{\"role\":").append(quote(m.role())).append(",\"content\":").append(quote(m.content())).append('}');
        }
        body.append("]}");

        HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/chat/completions"))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(LlmClient::extract);
    }

    private static String extract(HttpResponse<String> response) {
        JsonValue json;
        try {
            json = new JsonReader().parse(response.body());
        } catch (Exception e) {
            throw new IllegalStateException("HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
        }
        if (response.statusCode() != 200 || json.has("error")) {
            JsonValue err = json.get("error");
            String msg = err == null ? response.body() : err.isString() ? err.asString() : err.getString("message", err.toString());
            if (response.statusCode() == 429) {
                Matcher m = TRY_AGAIN.matcher(msg);
                double wait = m.find() ? Double.parseDouble(m.group(1)) / (m.group(2).equals("ms") ? 1000 : 1) : 5;
                throw new RateLimited("The AI service is busy (rate limit). Try again in a moment.", wait);
            }
            throw new IllegalStateException("API error " + response.statusCode() + ": " + abbreviate(msg));
        }
        JsonValue choice = json.get("choices").child;
        return choice.get("message").getString("content", "");
    }

    static String quote(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private static String abbreviate(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
