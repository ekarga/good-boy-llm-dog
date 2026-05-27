package ai.orca.llmdog.ai;

import ai.orca.llmdog.LlmDogMod;
import ai.orca.llmdog.llm.IntentParser;
import de.kherud.llama.InferenceParameters;
import de.kherud.llama.LlamaModel;
import de.kherud.llama.LlamaOutput;
import de.kherud.llama.ModelParameters;

import java.util.concurrent.CompletableFuture;

/**
 * In-process llama.cpp inference. Single shared model instance, used to
 * classify each chat into one of the 9 intent strings.
 */
public class LocalLlm {
    private static volatile LlamaModel model;

    private static final String SYSTEM_PROMPT =
        "You translate a player's chat into a single dog action.\n" +
        "Valid intents: good_boy, sit, stand, come, follow, attack, spin, jump, diamonds, none.\n" +
        "Synonyms: 'good boy'/'good dog'/'praise'='good_boy'; 'lie down'='sit'; 'get up'='stand'; " +
        "'here'/'come here'/'call'='come'; 'heel'/'stay with me'='follow'; 'kill'/'get him'/'sic'='attack'; " +
        "'hop'/'leap'='jump'; 'twirl'/'spin around'/'dance'='spin'; 'gimme diamonds'='diamonds'.\n" +
        "Reply with ONLY a compact JSON object: {\"intent\":\"<one of the valid intents>\"}.\n" +
        "If unclear, reply {\"intent\":\"none\"}.";

    public static synchronized void init() {
        if (model != null) return;
        Bootstrap.ensureExtracted();
        try {
            ModelParameters params = new ModelParameters()
                .setModel(Bootstrap.intentModel.toString())
                .setCtxSize(512)
                .setGpuLayers(0);
            model = new LlamaModel(params);
            LlmDogMod.LOGGER.info("[Good Boy] llama.cpp loaded ({})", Bootstrap.intentModel.getFileName());
        } catch (Throwable t) {
            LlmDogMod.LOGGER.error("[Good Boy] failed to init llama.cpp", t);
        }
    }

    public static CompletableFuture<String> getIntentAsync(String userText) {
        return CompletableFuture.supplyAsync(() -> {
            if (model == null) init();
            if (model == null) return null;
            String prompt = "<|im_start|>system\n" + SYSTEM_PROMPT + "<|im_end|>\n" +
                "<|im_start|>user\n" + userText + "<|im_end|>\n" +
                "<|im_start|>assistant\n";
            try {
                InferenceParameters infer = new InferenceParameters(prompt)
                    .setTemperature(0.0f)
                    .setNPredict(48)
                    .setStopStrings("<|im_end|>", "\n\n");
                StringBuilder out = new StringBuilder();
                for (LlamaOutput o : model.generate(infer)) {
                    out.append(o.text);
                    if (out.length() > 96) break;
                }
                return IntentParser.parseIntent(out.toString());
            } catch (Throwable t) {
                LlmDogMod.LOGGER.warn("[Good Boy] llama infer failed: {}", t.getMessage());
                return null;
            }
        });
    }

    public static synchronized void shutdown() {
        if (model != null) {
            try { model.close(); } catch (Throwable ignored) {}
            model = null;
        }
    }
}
