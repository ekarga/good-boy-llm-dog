package ai.orca.llmdog.ai;

import ai.orca.llmdog.LlmDogMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Extracts the bundled GGUF + whisper model files from the jar to the
 * .minecraft/llm_dog/ data directory on first launch. Idempotent: skips
 * extraction if files already exist with the expected size.
 */
public class Bootstrap {
    public static Path dataDir;
    public static Path intentModel;
    public static Path whisperModel;

    public static synchronized void ensureExtracted() {
        if (dataDir != null) return;
        dataDir = FabricLoader.getInstance().getGameDir().resolve("llm_dog");
        try {
            Files.createDirectories(dataDir);
            intentModel = extract("/assets/llm_dog/ai/intent-llm.gguf", dataDir.resolve("intent-llm.gguf"));
            whisperModel = extract("/assets/llm_dog/ai/whisper-tiny.en.bin", dataDir.resolve("whisper-tiny.en.bin"));
            LlmDogMod.LOGGER.info("[Good Boy] bundled models ready: {} and {}", intentModel, whisperModel);
        } catch (Exception e) {
            LlmDogMod.LOGGER.error("[Good Boy] failed to extract bundled models", e);
        }
    }

    private static Path extract(String resourcePath, Path target) throws Exception {
        if (Files.exists(target) && Files.size(target) > 1024 * 1024) {
            return target;
        }
        try (InputStream in = Bootstrap.class.getResourceAsStream(resourcePath)) {
            if (in == null) throw new RuntimeException("missing bundled resource: " + resourcePath);
            try (OutputStream out = Files.newOutputStream(target)) {
                in.transferTo(out);
            }
        }
        LlmDogMod.LOGGER.info("[Good Boy] extracted {} ({} bytes)", target, Files.size(target));
        return target;
    }
}
