package ai.orca.llmdog.llm;

import ai.orca.llmdog.LlmDogMod;
import ai.orca.llmdog.config.LlmDogConfig;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Talks to an OpenAI-compatible chat completions endpoint (works with Ollama,
 * LiteLLM, OpenAI, any drop-in compatible). Returns a parsed intent string.
 */
public class OrcaLlmClient {
    private static final Gson GSON = new Gson();
    private static final String SYSTEM_PROMPT =
        "You translate a player's chat message into a single dog action.\n" +
        "Valid intents: good_boy, sit, stand, come, follow, attack, spin, jump, diamonds, none.\n" +
        "Notes:\n" +
        "- come is one-shot: the dog pathfinds to the owner and stops. It does NOT keep following.\n" +
        "- follow is continuous: the dog tags along until told to sit or given another command.\n" +
        "- stand only matters if the dog is currently sitting (i.e. 'get up').\n" +
        "- attack targets whatever the owner is looking at, or the nearest hostile mob nearby.\n" +
        "- diamonds means the dog should give the owner diamonds (it'll drop a few at their feet).\n" +
        "- good_boy means the owner is praising the dog (hearts appear above it).\n" +
        "Synonyms / examples:\n" +
        "  'good boy'/'good dog'/'who's a good boy'='good_boy';\n" +
        "  'sit'/'lie down'/'have a seat'='sit';\n" +
        "  'get up'/'stand'/'on your feet'='stand';\n" +
        "  'come'/'here boy'/'call'/'come back'='come';\n" +
        "  'follow'/'heel'/'come with me'/'stay with me'='follow';\n" +
        "  'attack'/'get him'/'kill'/'sic em'/'go murder that'='attack';\n" +
        "  'jump'/'hop'/'leap'='jump';\n" +
        "  'spin'/'twirl'/'spin around'/'do a dance'='spin';\n" +
        "  'give me diamonds'/'gimme diamonds'/'drop diamonds'='diamonds'.\n" +
        "Reply with ONLY a compact JSON object: " +
        "{\"intent\": \"<one of the valid intents>\", \"reason\": \"<short>\"}.\n" +
        "If the message clearly isn't a dog command, return none. The dog does not speak back; do not invent dialogue.";

    private final LlmDogConfig config;
    private final HttpClient http;

    public OrcaLlmClient(LlmDogConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
        warmup();
    }

    /** Fires a small request at startup so the model is hot before the user speaks. */
    private void warmup() {
        try {
            JsonObject body = buildBody("hello");
            String endpoint = config.proxyUrl.replaceAll("/$", "") + "/chat/completions";
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.proxyKey)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();
            http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenAccept(r -> LlmDogMod.LOGGER.info("[LLM Dog] warmup done, status={}", r.statusCode()))
                .exceptionally(t -> { LlmDogMod.LOGGER.warn("[LLM Dog] warmup failed: {}", t.getMessage()); return null; });
        } catch (Exception e) {
            LlmDogMod.LOGGER.warn("[LLM Dog] warmup setup failed: {}", e.getMessage());
        }
    }

    public CompletableFuture<String> getIntentAsync(String userText) {
        return sendOnce(userText, 0);
    }

    private CompletableFuture<String> sendOnce(String userText, int attempt) {
        JsonObject body = buildBody(userText);
        String endpoint = config.proxyUrl.replaceAll("/$", "") + "/chat/completions";
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + config.proxyKey)
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
            .build();

        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
            .thenCompose(resp -> {
                int status = resp.statusCode();
                String bodyStr = resp.body() != null ? resp.body() : "";
                boolean loading = bodyStr.toLowerCase().contains("loading model")
                                || bodyStr.toLowerCase().contains("model not ready");
                if (status / 100 != 2) {
                    if ((loading || status == 503) && attempt < 3) {
                        long delay = 1500L * (attempt + 1);
                        LlmDogMod.LOGGER.info("[LLM Dog] LLM warming up, retry {} in {}ms", attempt + 1, delay);
                        return retryAfter(userText, attempt + 1, delay);
                    }
                    LlmDogMod.LOGGER.warn("[LLM Dog] non-2xx {} (attempt {}): {}", status, attempt, trim(bodyStr, 200));
                    return CompletableFuture.completedFuture(null);
                }
                String text = extractText(bodyStr);
                if (text == null) return CompletableFuture.completedFuture(null);
                return CompletableFuture.completedFuture(IntentParser.parseIntent(text));
            });
    }

    private CompletableFuture<String> retryAfter(String userText, int attempt, long delayMs) {
        CompletableFuture<String> f = new CompletableFuture<>();
        java.util.concurrent.CompletableFuture.delayedExecutor(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            .execute(() -> sendOnce(userText, attempt).whenComplete((r, t) -> {
                if (t != null) f.completeExceptionally(t); else f.complete(r);
            }));
        return f;
    }

    private JsonObject buildBody(String userText) {
        JsonObject body = new JsonObject();
        body.addProperty("model", config.model);
        body.addProperty("max_tokens", 64);
        body.addProperty("temperature", 0.0);

        JsonArray messages = new JsonArray();
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", SYSTEM_PROMPT);
        messages.add(sys);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", userText);
        messages.add(user);
        body.add("messages", messages);

        return body;
    }

    private String extractText(String responseJson) {
        try {
            JsonObject obj = JsonParser.parseString(responseJson).getAsJsonObject();
            JsonArray choices = obj.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) return null;
            JsonObject first = choices.get(0).getAsJsonObject();
            JsonObject message = first.getAsJsonObject("message");
            if (message == null || !message.has("content")) return null;
            return message.get("content").getAsString();
        } catch (Exception e) {
            LlmDogMod.LOGGER.warn("[LLM Dog] failed to parse response: {}", e.getMessage());
            return null;
        }
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
