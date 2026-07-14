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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class LlmDogClient implements ClientModInitializer {
    private static KeyBinding pushToTalk;
    private static MicCapture mic;
    // Always-on mode: ensures the VAD capture is started exactly once, the first
    // time the player is actually in a world (not at the title screen).
    private final AtomicBoolean continuousStarted = new AtomicBoolean(false);
    // Single-flight guard so overlapping utterances don't pile up on whisper.
    private final AtomicBoolean transcribing = new AtomicBoolean(false);

    // --- Streaming early-fire state -------------------------------------
    // Partials are decoded while the player is still talking; when two
    // consecutive partials resolve to the same non-empty command list, the
    // dog acts immediately. The generation counter invalidates in-flight
    // partial results the moment the utterance ends, and the final transcript
    // reconciles against what already fired.
    private final AtomicBoolean partialBusy = new AtomicBoolean(false);
    private final AtomicLong utteranceGen = new AtomicLong(0);
    private final Object streamLock = new Object();
    private String lastPartialTranscript = "";
    private List<String> lastPartialIntents = List.of();
    private List<String> streamFired = List.of();

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
            boolean ok = mic.startContinuous(this::handleUtterance, this::handlePartial);
            if (ok) {
                client.player.sendMessage(Text.literal("§7[dog] always listening — just talk"), true);
            } else {
                continuousStarted.set(false); // allow retry next tick if the line wasn't ready
            }
        }
    }

    /**
     * Streaming path — called on the VAD thread with a snapshot of the
     * utterance-so-far, while the player is STILL talking. Hands off to the
     * async pipeline immediately (never blocks the mic). One partial decodes
     * at a time; strides arriving while busy are simply dropped — the next
     * snapshot supersedes them anyway.
     */
    private void handlePartial(byte[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        if (!partialBusy.compareAndSet(false, true)) return;
        final long gen = utteranceGen.get();
        LocalWhisper.transcribeAsync(pcm, true)
            .thenAccept(text -> {
                String clean = text == null ? "" : text.trim();
                if (clean.isEmpty() || utteranceGen.get() != gen) {
                    partialBusy.set(false);
                    return;
                }
                // Same transcript as the previous partial — the speech is
                // stable; re-evaluate agreement without another Mercury call.
                String prevText;
                List<String> prevIntents;
                synchronized (streamLock) {
                    prevText = lastPartialTranscript;
                    prevIntents = lastPartialIntents;
                }
                if (clean.equalsIgnoreCase(prevText)) {
                    evaluatePartial(gen, clean, prevIntents);
                    partialBusy.set(false);
                    return;
                }
                MercuryIntentClient.resolve(clean).whenComplete((intents, t) -> {
                    if (t == null && intents != null) evaluatePartial(gen, clean, intents);
                    partialBusy.set(false);
                });
            })
            .exceptionally(t -> {
                partialBusy.set(false);
                return null;
            });
    }

    /**
     * Fire when two consecutive partials agree on the same non-empty command
     * list — that agreement is what makes acting on half-heard audio safe. If
     * the chain keeps growing ("sit" already fired, now "sit, spin"), only the
     * new suffix fires.
     */
    private void evaluatePartial(long gen, String transcript, List<String> intents) {
        List<String> toFire = null;
        synchronized (streamLock) {
            if (utteranceGen.get() != gen) return;
            boolean agreed = !intents.isEmpty() && intents.equals(lastPartialIntents);
            lastPartialTranscript = transcript;
            lastPartialIntents = intents;
            if (agreed && !intents.equals(streamFired)) {
                if (streamFired.isEmpty()) {
                    toFire = List.copyOf(intents);
                } else if (intents.size() > streamFired.size()
                        && intents.subList(0, streamFired.size()).equals(streamFired)) {
                    toFire = List.copyOf(intents.subList(streamFired.size(), intents.size()));
                }
                if (toFire != null) streamFired = List.copyOf(intents);
            }
        }
        if (toFire != null && !toFire.isEmpty()) {
            LlmDogMod.LOGGER.info("[Voice] stream-fired \"{}\" -> {}", transcript, toFire);
            sendIntents(MinecraftClient.getInstance(), "⚡ " + transcript, toFire);
        }
    }

    /** Called off the mic thread for each detected utterance. */
    private void handleUtterance(byte[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        // The utterance is over: bump the generation so in-flight partial
        // results go stale, and take what the stream already fired so the
        // final transcript only dispatches the remainder.
        final List<String> alreadyFired;
        synchronized (streamLock) {
            utteranceGen.incrementAndGet();
            alreadyFired = streamFired;
            lastPartialTranscript = "";
            lastPartialIntents = List.of();
            streamFired = List.of();
        }
        // Drop overlapping utterances while one is still transcribing.
        if (!transcribing.compareAndSet(false, true)) return;
        LocalWhisper.transcribeAsync(pcm)
            .thenAccept(text -> dispatchTranscript(text, alreadyFired))
            .exceptionally(t -> {
                LlmDogMod.LOGGER.warn("[Good Boy] STT error: {}", t.getMessage());
                return null;
            })
            .whenComplete((v, t) -> transcribing.set(false));
    }

    private void dispatchTranscript(String text) {
        dispatchTranscript(text, List.of());
    }

    /**
     * Send a transcript to the dog ONLY if it actually contains a command.
     * Exact command words resolve through the local parser instantly; anything
     * else goes to Mercury (dLLM), which maps paraphrases like "would you
     * kindly take a seat" to intents. Non-command speech (ambient noise,
     * whisper hallucinations, filler) is dropped silently — the player only
     * sees feedback when they actually gave an order.
     */
    private void dispatchTranscript(String text, List<String> alreadyFired) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || text == null) return;
        String clean = text.trim();
        if (clean.isEmpty()) return;
        LlmDogMod.LOGGER.info("[Voice] heard: \"{}\"", clean);

        // Short utterances resolve instantly offline; sentences go through
        // Mercury (which understands negation). See MercuryIntentClient.resolve.
        MercuryIntentClient.resolve(clean)
            .thenAccept(intents -> {
                List<String> remaining = reconcile(intents, alreadyFired);
                if (remaining.isEmpty()) {
                    if (!alreadyFired.isEmpty()) {
                        LlmDogMod.LOGGER.info("[Voice] final \"{}\" -> {} already handled by stream", clean, alreadyFired);
                    }
                    return;
                }
                sendIntents(client, clean, remaining);
            });
    }

    /**
     * What the final transcript should still dispatch, given what the stream
     * already fired mid-utterance. Exact match or a fired-prefix means only
     * the tail (if any) runs; on disagreement, fire just the intents the
     * stream never issued — a correction without replaying the whole chain.
     */
    private static List<String> reconcile(List<String> finalIntents, List<String> fired) {
        if (fired.isEmpty()) return finalIntents;
        if (finalIntents.equals(fired)) return List.of();
        if (finalIntents.size() > fired.size()
                && finalIntents.subList(0, fired.size()).equals(fired)) {
            return finalIntents.subList(fired.size(), finalIntents.size());
        }
        List<String> out = new ArrayList<>();
        for (String i : finalIntents) if (!fired.contains(i)) out.add(i);
        return out;
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
