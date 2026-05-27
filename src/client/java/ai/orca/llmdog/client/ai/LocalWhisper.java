package ai.orca.llmdog.client.ai;

import ai.orca.llmdog.LlmDogMod;
import ai.orca.llmdog.ai.Bootstrap;
import io.github.givimad.whisperjni.WhisperContext;
import io.github.givimad.whisperjni.WhisperFullParams;
import io.github.givimad.whisperjni.WhisperJNI;

import java.util.concurrent.CompletableFuture;

/**
 * In-process whisper.cpp inference. Takes raw 16-bit PCM mono at 16kHz
 * (what MicCapture produces) and returns the transcript.
 */
public class LocalWhisper {
    private static volatile WhisperJNI jni;
    private static volatile WhisperContext ctx;
    private static volatile boolean attemptedInit = false;

    public static synchronized void init() {
        if (attemptedInit) return;
        attemptedInit = true;
        Bootstrap.ensureExtracted();
        try {
            WhisperJNI.loadLibrary();
            jni = new WhisperJNI();
            ctx = jni.init(Bootstrap.whisperModel);
            LlmDogMod.LOGGER.info("[Good Boy] whisper loaded ({})", Bootstrap.whisperModel.getFileName());
        } catch (Throwable t) {
            LlmDogMod.LOGGER.error("[Good Boy] failed to init whisper", t);
        }
    }

    /**
     * @param pcm16le little-endian signed 16-bit PCM mono at 16kHz
     * @return transcript or null on failure
     */
    public static CompletableFuture<String> transcribeAsync(byte[] pcm16le) {
        return CompletableFuture.supplyAsync(() -> {
            if (!attemptedInit) init();
            if (jni == null || ctx == null) return null;
            try {
                float[] samples = new float[pcm16le.length / 2];
                for (int i = 0; i < samples.length; i++) {
                    int lo = pcm16le[i * 2] & 0xff;
                    int hi = pcm16le[i * 2 + 1];
                    short s = (short) ((hi << 8) | lo);
                    samples[i] = s / 32768.0f;
                }
                WhisperFullParams params = new WhisperFullParams();
                int result;
                synchronized (LocalWhisper.class) {
                    result = jni.full(ctx, params, samples, samples.length);
                }
                if (result != 0) {
                    LlmDogMod.LOGGER.warn("[Good Boy] whisper full() returned {}", result);
                    return null;
                }
                int n = jni.fullNSegments(ctx);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < n; i++) {
                    sb.append(jni.fullGetSegmentText(ctx, i));
                }
                return sb.toString().trim();
            } catch (Throwable t) {
                LlmDogMod.LOGGER.warn("[Good Boy] whisper infer failed: {}", t.getMessage());
                return null;
            }
        });
    }
}
