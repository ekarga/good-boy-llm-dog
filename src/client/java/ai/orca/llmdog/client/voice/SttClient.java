package ai.orca.llmdog.client.voice;

import ai.orca.llmdog.LlmDogMod;
import ai.orca.llmdog.config.LlmDogConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Posts WAV audio to a local whisper.cpp server (open source) and returns
 * the transcribed text.
 */
public class SttClient {
    private final LlmDogConfig config;
    private final HttpClient http;

    public SttClient(LlmDogConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    }

    public CompletableFuture<String> transcribeAsync(byte[] wav) {
        String boundary = "----LlmDog" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = buildMultipart(boundary, wav);
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(config.sttUrl))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();

        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
            .thenApply(resp -> {
                if (resp.statusCode() / 100 != 2) {
                    LlmDogMod.LOGGER.warn("[Voice] whisper non-2xx {}: {}", resp.statusCode(), trim(resp.body(), 200));
                    return null;
                }
                return parseText(resp.body());
            });
    }

    private static byte[] buildMultipart(String boundary, byte[] wav) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(wav.length + 512);
        try {
            // file part
            writeAscii(out, "--" + boundary + "\r\n");
            writeAscii(out, "Content-Disposition: form-data; name=\"file\"; filename=\"clip.wav\"\r\n");
            writeAscii(out, "Content-Type: audio/wav\r\n\r\n");
            out.write(wav);
            writeAscii(out, "\r\n");
            // response_format=json
            writeAscii(out, "--" + boundary + "\r\n");
            writeAscii(out, "Content-Disposition: form-data; name=\"response_format\"\r\n\r\n");
            writeAscii(out, "json\r\n");
            // temperature=0
            writeAscii(out, "--" + boundary + "\r\n");
            writeAscii(out, "Content-Disposition: form-data; name=\"temperature\"\r\n\r\n");
            writeAscii(out, "0\r\n");
            // close
            writeAscii(out, "--" + boundary + "--\r\n");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) throws Exception {
        out.write(s.getBytes("UTF-8"));
    }

    private static String parseText(String body) {
        if (body == null) return null;
        String trimmed = body.trim();
        // Try JSON first
        try {
            JsonObject obj = JsonParser.parseString(trimmed).getAsJsonObject();
            if (obj.has("text")) return obj.get("text").getAsString().trim();
        } catch (Exception ignored) {}
        // Plain text fallback
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
