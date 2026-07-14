package ai.orca.llmdog.client;

import ai.orca.llmdog.LlmDogMod;
import ai.orca.llmdog.client.ai.LocalWhisper;
import ai.orca.llmdog.client.anim.DogPoses;
import ai.orca.llmdog.client.voice.MicCapture;
import ai.orca.llmdog.llm.IntentParser;
import ai.orca.llmdog.llm.MercuryIntentClient;
import ai.orca.llmdog.net.DogCommandPayload;
import ai.orca.llmdog.net.DogPosePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class LlmDogClient implements ClientModInitializer {
    private static KeyBinding pushToTalk;
    private static MicCapture mic;
    // Always-on mode: ensures the VAD capture is started exactly once, the first
    // time the player is actually in a world (not at the title screen).
    private final AtomicBoolean continuousStarted = new AtomicBoolean(false);
    // Single-flight guard so overlapping utterances don't pile up on whisper.
    private final AtomicBoolean transcribing = new AtomicBoolean(false);

    @Override
    public void onInitializeClient() {
        // Pose packets (paw / down / shake) drive the wolf model mixin. Register
        // these regardless of voice settings so typed commands animate too.
        ClientPlayNetworking.registerGlobalReceiver(DogPosePayload.ID, (payload, context) ->
            context.client().execute(() -> DogPoses.set(payload.entityId(), payload.pose(), payload.duration())));
        ClientTickEvents.END_CLIENT_TICK.register(client -> DogPoses.tick());

        if (!LlmDogMod.config.sttEnabled) {
            LlmDogMod.LOGGER.info("[Good Boy] client init complete (voice OFF)");
            return;
        }

        mic = new MicCapture(LlmDogMod.config);
        // Init whisper off-thread so client startup isn't blocked
        new Thread(LocalWhisper::init, "good-boy-whisper-init").start();

        if (LlmDogMod.config.alwaysListen) {
            ClientTickEvents.END_CLIENT_TICK.register(this::onTickAlwaysOn);
            LlmDogMod.LOGGER.info("[Good Boy] client init complete (voice on, ALWAYS-LISTENING VAD, in-process whisper)");
        } else {
            pushToTalk = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.llm_dog.push_to_talk",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                "category.llm_dog"
            ));
            ClientTickEvents.END_CLIENT_TICK.register(this::onTickPushToTalk);
            LlmDogMod.LOGGER.info("[Good Boy] client init complete (voice on, push-to-talk = V, in-process whisper)");
        }
    }

    /** Always-on: start the VAD capture once the player is in-world; utterances flow via callback. */
    private void onTickAlwaysOn(MinecraftClient client) {
        if (client == null || client.player == null) return;
        if (continuousStarted.compareAndSet(false, true)) {
            boolean ok = mic.startContinuous(this::handleUtterance);
            if (ok) {
                client.player.sendMessage(Text.literal("§7[dog] always listening — just talk"), true);
            } else {
                continuousStarted.set(false); // allow retry next tick if the line wasn't ready
            }
        }
    }

    /** Called off the mic thread for each detected utterance. */
    private void handleUtterance(byte[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        // Drop overlapping utterances while one is still transcribing.
        if (!transcribing.compareAndSet(false, true)) return;
        LocalWhisper.transcribeAsync(pcm)
            .thenAccept(this::dispatchTranscript)
            .exceptionally(t -> {
                LlmDogMod.LOGGER.warn("[Good Boy] STT error: {}", t.getMessage());
                return null;
            })
            .whenComplete((v, t) -> transcribing.set(false));
    }

    /**
     * Send a transcript to the dog ONLY if it actually contains a command.
     * Exact command words resolve through the local parser instantly; anything
     * else goes to Mercury (dLLM), which maps paraphrases like "would you
     * kindly take a seat" to intents. Non-command speech (ambient noise,
     * whisper hallucinations, filler) is dropped silently — the player only
     * sees feedback when they actually gave an order.
     */
    private void dispatchTranscript(String text) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || text == null) return;
        String clean = text.trim();
        if (clean.isEmpty()) return;
        LlmDogMod.LOGGER.info("[Voice] heard: \"{}\"", clean);

        // Short utterances resolve instantly offline; sentences go through
        // Mercury (which understands negation). See MercuryIntentClient.resolve.
        MercuryIntentClient.resolve(clean)
            .thenAccept(intents -> sendIntents(client, clean, intents));
    }

    private void sendIntents(MinecraftClient client, String transcript, List<String> intents) {
        if (intents.isEmpty()) return;
        client.execute(() -> {
            if (client.player == null || client.player.networkHandler == null) return;
            client.player.sendMessage(Text.literal("§a🐶 §f" + transcript + " §7→ " + String.join(", ", intents)), true);
            ClientPlayNetworking.send(new DogCommandPayload(String.join(",", intents)));
        });
    }

    private void onTickPushToTalk(MinecraftClient client) {
        if (client == null || client.player == null || pushToTalk == null) return;
        boolean held = pushToTalk.isPressed();
        if (held && !mic.isRecording()) {
            mic.start();
        } else if (!held && mic.isRecording()) {
            byte[] pcm = mic.stopAndGetPcm();
            if (pcm == null) return; // too short — say nothing
            LocalWhisper.transcribeAsync(pcm)
                .thenAccept(this::dispatchTranscript)
                .exceptionally(t -> {
                    LlmDogMod.LOGGER.warn("[Good Boy] STT error: {}", t.getMessage());
                    return null;
                });
        }
    }
}
