package ai.orca.llmdog.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class LlmDogConfig {
    // Default to local Ollama (OpenAI-compatible). Works out of the box if Ollama is running.
    public String proxyUrl = "http://localhost:11434/v1";
    public String proxyKey = "ollama";
    public String model = "llama3.2:3b";
    public int listenRadius = 32;
    public boolean enableLlm = true;

    // Speech to text -- local whisper.cpp server (`whisper-server`). Open source.
    public boolean sttEnabled = true;
    public String sttUrl = "http://localhost:8732/inference";
    public int micSampleRateHz = 16000;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static LlmDogConfig load() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        Path file = configDir.resolve("llm_dog.json5");
        try {
            if (!Files.exists(file)) {
                LlmDogConfig defaults = new LlmDogConfig();
                Files.createDirectories(configDir);
                Files.writeString(file, GSON.toJson(defaults), StandardOpenOption.CREATE_NEW);
                return defaults;
            }
            String text = Files.readString(file);
            JsonReader reader = new JsonReader(new StringReader(text));
            reader.setLenient(true);
            LlmDogConfig cfg = GSON.fromJson(reader, LlmDogConfig.class);
            return cfg != null ? cfg : new LlmDogConfig();
        } catch (Exception e) {
            return new LlmDogConfig();
        }
    }
}
