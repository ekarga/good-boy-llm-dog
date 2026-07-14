package ai.orca.llmdog.llm;

import ai.orca.llmdog.LlmDogMod;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Natural-language intent parsing via Mercury (Inception Labs' diffusion LLM,
 * OpenAI-compatible chat completions). Called ONLY when the local
 * {@link IntentParser} finds nothing, so exact phrases ("sit", "good boy")
 * stay instant and offline while paraphrases ("would you kindly take a seat")
 * resolve through the model.
 *
 * Mercury quirks the request accounts for:
 *   - temperature below 0.5 is rejected/clamped, so none is sent (server default)
 *   - the model spends "reasoning" tokens before emitting content, so
 *     max_tokens must be generous or content comes back null
 */
public final class MercuryIntentClient {

    /** The dog's toolset — the only intents the model may emit, anything else is dropped. */
    private static final Set<String> CANONICAL = Set.of(
        "sit", "attack", "follow", "come", "jump", "spin", "bark", "good_boy", "bad_boy");

    private static final int MAX_SEQUENCE = 6;
    private static final int MAX_TOKENS = 600; // reasoning + answer

    private static final String SYSTEM_PROMPT =
        "You convert a spoken utterance into dog commands (the dog's tools). Reply with ONLY a JSON array "
      + "(no prose, no code fence) of tools in execution order, each from exactly this set: "
      + "\"sit\",\"attack\",\"follow\",\"come\",\"jump\",\"spin\",\"bark\",\"good_boy\",\"bad_boy\". "
      + "\"sit\"=sit/lie down/settle, \"follow\"=follow/heel/stop sitting, "
      + "\"come\"=come here/come back, \"jump\"=jump/hop, \"spin\"=spin/twirl, "
      + "\"bark\"=bark/speak/woof/make noise, \"good_boy\"=any praise (show hearts), "
      + "\"bad_boy\"=any scolding or disappointment (broken heart). "
      + "\"attack\"=attack/bite/get him; when the speaker names WHAT to attack, append the Minecraft entity "
      + "type after a colon in singular snake_case: \"attack the slimes\" -> \"attack:slime\", "
      + "\"kill that zombie\" -> \"attack:zombie\", \"get the iron golem\" -> \"attack:iron_golem\". "
      + "Plain \"attack\" = whatever the owner is looking at. "
      + "If the utterance contains no dog command, reply []. Max 6 intents.";

    private static final Gson GSON = new Gson();
    private static volatile HttpClient http;

    private MercuryIntentClient() {}

    public static boolean enabled() {
        return LlmDogMod.config.llmEnabled && !apiKey().isEmpty();
    }

    /**
     * Cheap gate so ambient rambling picked up by the always-on mic doesn't
     * burn credits. Single-word utterances are excluded too: real one-word
     * commands ("sit", "bark") always hit the local parser first, so a lone
     * word reaching this point is a whisper hallucination ("You", "Bye.").
     */
    public static boolean worthAsking(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (t.length() < 2) return false;
        int words = t.split("\\s+").length;
        return words >= 2 && words <= LlmDogMod.config.llmMaxWords;
    }

    // Only 1-2 word utterances ("sit", "good boy") take the instant local
    // path. Anything longer may carry meaning the keyword matcher can't see —
    // negation ("don't attack"), targets ("attack the slimes") — so it goes
    // through the model.
    private static final int LOCAL_FAST_PATH_MAX_WORDS = 2;

    /**
     * Resolve an utterance to intents. Short utterances ("sit", "good boy")
     * take the instant offline keyword path. Full sentences ALWAYS go through
     * Mercury when it's enabled, because the keyword matcher can't understand
     * negation — "you don't want to attack" must not trigger attack. If the
     * model call fails (timeout, non-2xx), falls back to the keyword parse.
     */
    public static CompletableFuture<List<String>> resolve(String text) {
        if (text == null || text.isBlank()) return CompletableFuture.completedFuture(List.of());
        List<String> local = IntentParser.parseSequence(text);
        boolean shortUtterance = text.trim().split("\\s+").length <= LOCAL_FAST_PATH_MAX_WORDS;
        if (shortUtterance && !local.isEmpty()) return CompletableFuture.completedFuture(local);
        if (!enabled() || !worthAsking(text)) return CompletableFuture.completedFuture(local);
        return parseAsync(text).exceptionally(t -> {
            LlmDogMod.LOGGER.warn("[Good Boy] mercury failed ({}), falling back to keyword parse", t.getMessage());
            return local;
        });
    }

    /**
     * Map an utterance to canonical intents via Mercury. Completes
     * exceptionally on transport failure (timeout, non-2xx) so callers can
     * fall back; completes with an empty list when the model finds no command.
     */
    public static CompletableFuture<List<String>> parseAsync(String utterance) {
        String key = apiKey();
        if (key.isEmpty()) return CompletableFuture.completedFuture(List.of());

        JsonObject body = new JsonObject();
        body.addProperty("model", LlmDogMod.config.llmModel);
        body.addProperty("max_tokens", MAX_TOKENS);
        JsonArray messages = new JsonArray();
        messages.add(message("system", SYSTEM_PROMPT));
        messages.add(message("user", utterance));
        body.add("messages", messages);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(LlmDogMod.config.llmBaseUrl + "/chat/completions"))
            .timeout(Duration.ofMillis(LlmDogMod.config.llmTimeoutMs))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + key)
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
            .build();

        long t0 = System.currentTimeMillis();
        return client().sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(resp -> {
                if (resp.statusCode() / 100 != 2) {
                    LlmDogMod.LOGGER.warn("[Good Boy] mercury non-2xx {}: {}", resp.statusCode(), trim(resp.body()));
                    throw new IllegalStateException("mercury HTTP " + resp.statusCode());
                }
                List<String> intents = extractIntents(resp.body());
                LlmDogMod.LOGGER.info("[Good Boy] mercury {}ms \"{}\" -> {}",
                    System.currentTimeMillis() - t0, utterance, intents);
                return intents;
            });
    }

    /** Pull choices[0].message.content, then parse the JSON array inside it. */
    private static List<String> extractIntents(String responseBody) {
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonElement content = root.getAsJsonArray("choices").get(0)
                .getAsJsonObject().getAsJsonObject("message").get("content");
            if (content == null || content.isJsonNull()) return List.of();
            String text = content.getAsString();

            int start = text.indexOf('[');
            int end = text.lastIndexOf(']');
            if (start < 0 || end <= start) return List.of();

            List<String> result = new ArrayList<>();
            String prev = null;
            for (JsonElement e : JsonParser.parseString(text.substring(start, end + 1)).getAsJsonArray()) {
                String intent = e.getAsString().toLowerCase(Locale.ROOT).trim();
                // Optional target argument, e.g. "attack:slime" — validate the
                // base against the toolset and sanitize the argument.
                String base = intent, arg = null;
                int colon = intent.indexOf(':');
                if (colon > 0) {
                    base = intent.substring(0, colon).trim();
                    arg = intent.substring(colon + 1).replaceAll("[^a-z_ ]", "").trim().replace(' ', '_');
                }
                if (!CANONICAL.contains(base)) continue;
                String canonical = (arg != null && !arg.isEmpty() && base.equals("attack")) ? base + ":" + arg : base;
                if (canonical.equals(prev)) continue;
                result.add(canonical);
                prev = canonical;
                if (result.size() >= MAX_SEQUENCE) break;
            }
            return result;
        } catch (Exception e) {
            LlmDogMod.LOGGER.warn("[Good Boy] mercury response unparseable: {}", trim(responseBody));
            return List.of();
        }
    }

    private static String apiKey() {
        String k = LlmDogMod.config.llmApiKey;
        if (k == null || k.isBlank()) k = System.getenv("MERCURY_API_KEY");
        return k == null ? "" : k.trim();
    }

    private static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    private static HttpClient client() {
        HttpClient c = http;
        if (c == null) {
            synchronized (MercuryIntentClient.class) {
                if (http == null) {
                    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                }
                c = http;
            }
        }
        return c;
    }

    private static String trim(String s) {
        if (s == null) return "null";
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
