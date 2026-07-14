package ai.orca.llmdog.client.ai;

import ai.orca.llmdog.LlmDogMod;
import ai.orca.llmdog.ai.Bootstrap;
import io.github.givimad.whisperjni.WhisperContext;
import io.github.givimad.whisperjni.WhisperFullParams;
import io.github.givimad.whisperjni.WhisperJNI;
import io.github.givimad.whisperjni.WhisperSamplingStrategy;

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
        return transcribeAsync(pcm16le, false);
    }

    /**
     * @param fast greedy single-pass decode for streaming partials — roughly
     *             half the latency of the beam search used on final utterances.
     *             The stream's two-partials-must-agree rule absorbs the
     *             accuracy loss.
     */
    public static CompletableFuture<String> transcribeAsync(byte[] pcm16le, boolean fast) {
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
                WhisperFullParams params = buildParams(fast);
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
                return cleanup(sb.toString());
            } catch (Throwable t) {
                LlmDogMod.LOGGER.warn("[Good Boy] whisper infer failed: {}", t.getMessage());
                return null;
            }
        });
    }

    /**
     * Tuned params for a SMALL fixed command vocabulary spoken in short bursts.
     * The biggest lever is {@code initialPrompt}: it primes whisper toward the
     * dog-command words so "sit"/"come"/"attack" are far less likely to be
     * mis-transcribed (and then silently dropped by the command gate).
     */
    private static WhisperFullParams buildParams(boolean fast) {
        WhisperFullParams p = new WhisperFullParams(
            fast ? WhisperSamplingStrategy.GREEDY : WhisperSamplingStrategy.BEAN_SEARCH);
        p.nThreads = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
        p.beamSearchBeamSize = fast ? 1 : 5;
        p.greedyBestOf = fast ? 1 : 5;
        p.language = "en";
        p.detectLanguage = false;
        p.translate = false;
        p.noContext = true;       // each utterance is independent
        p.singleSegment = true;   // commands are one short segment
        p.suppressBlank = true;
        p.suppressNonSpeechTokens = true;
        p.noTimestamps = true;
        p.printProgress = false;
        p.printRealtime = false;
        p.printTimestamps = false;
        p.printSpecial = false;
        p.temperature = 0.0f;
        p.initialPrompt =
            "Dog obedience commands: sit, stay, stand, get up, come, here, heel, follow, "
          + "attack, kill, fetch, spin, twirl, jump, hop, good boy, good dog, fetch diamonds.";
        return p;
    }

    /** Strip whisper non-speech artifacts like [BLANK_AUDIO], (wind), *music*. */
    private static String cleanup(String raw) {
        if (raw == null) return null;
        String out = raw
            .replaceAll("\\[[^\\]]*\\]", " ")
            .replaceAll("\\([^\\)]*\\)", " ")
            .replaceAll("\\*[^\\*]*\\*", " ")
            .replaceAll("\\s+", " ")
            .trim();
        return out;
    }
}
