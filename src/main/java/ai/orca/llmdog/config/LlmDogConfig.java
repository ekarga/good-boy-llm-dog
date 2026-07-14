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
    public int listenRadius = 32;

    // Natural-language understanding -- Mercury dLLM (Inception Labs),
    // OpenAI-compatible API. Only consulted when the local keyword parser
    // finds no command, so exact phrases stay instant and offline. Key comes
    // from llmApiKey here or the MERCURY_API_KEY environment variable; with
    // neither set the feature is silently off.
    public boolean llmEnabled = true;
    public String llmBaseUrl = "https://api.inceptionlabs.ai/v1";
    public String llmModel = "mercury-2";
    public String llmApiKey = "";
    public int llmTimeoutMs = 6000;
    // Utterances longer than this many words skip the model (ambient speech
    // picked up by the always-on mic shouldn't burn credits).
    public int llmMaxWords = 24;

    // Speech to text -- local whisper.cpp server (`whisper-server`). Open source.
    public boolean sttEnabled = true;
    public String sttUrl = "http://localhost:8732/inference";
    public int micSampleRateHz = 16000;

    // Always-on listening: when true the mic stays open and a voice-activity
    // detector (VAD) auto-segments each utterance instead of holding the
    // push-to-talk key. Set false to fall back to hold-V push-to-talk.
    public boolean alwaysListen = true;
    public int vadStartRms = 600;        // RMS that triggers "speech started" (lower = more sensitive)
    public int vadKeepRms = 300;         // RMS below this counts as silence
    public int vadSilenceMs = 500;       // trailing silence that ends an utterance
    public int vadMinUtteranceMs = 250;  // ignore blips shorter than this
    public int vadMaxUtteranceMs = 12000;// hard cap so one utterance can't run forever
    public int vadPrerollMs = 400;       // audio kept just before the trigger (avoids clipped first word)

    // Streaming early-fire: while an utterance is still being spoken, partial
    // snapshots of the audio are transcribed and resolved; when two consecutive
    // partials agree on the same non-empty command list, the dog acts
    // immediately instead of waiting for end-of-speech. The final transcript
    // reconciles afterwards (fires only what the stream didn't already fire).
    public boolean streamPartials = true;
    public int streamPartialStrideMs = 300; // new audio required between partial decodes
    public int streamMinPartialMs = 350;    // don't decode partials shorter than this

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
