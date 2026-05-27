package ai.orca.llmdog.client;

import ai.orca.llmdog.LlmDogMod;
import ai.orca.llmdog.client.model.LlmDogEntityModel;
import ai.orca.llmdog.client.render.LlmDogRenderer;
import ai.orca.llmdog.client.ai.LocalWhisper;
import ai.orca.llmdog.client.voice.MicCapture;
import ai.orca.llmdog.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public class LlmDogClient implements ClientModInitializer {
    private static KeyBinding pushToTalk;
    private static MicCapture mic;

    @Override
    public void onInitializeClient() {
        EntityModelLayerRegistry.registerModelLayer(LlmDogEntityModel.LAYER, LlmDogEntityModel::getTexturedModelData);
        EntityRendererRegistry.register(ModEntities.LLM_DOG, LlmDogRenderer::new);

        if (LlmDogMod.config.sttEnabled) {
            mic = new MicCapture(LlmDogMod.config);
            // Init whisper off-thread so client startup isn't blocked
            new Thread(LocalWhisper::init, "good-boy-whisper-init").start();
            pushToTalk = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.llm_dog.push_to_talk",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                "category.llm_dog"
            ));
            ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
            LlmDogMod.LOGGER.info("[Good Boy] client init complete (voice on, push-to-talk = V, in-process whisper)");
        } else {
            LlmDogMod.LOGGER.info("[Good Boy] client init complete (voice OFF)");
        }
    }

    private void onTick(MinecraftClient client) {
        if (client == null || client.player == null || pushToTalk == null) return;
        boolean held = pushToTalk.isPressed();
        if (held && !mic.isRecording()) {
            if (mic.start()) {
                client.player.sendMessage(Text.literal("§7[dog] listening..."), true);
            }
        } else if (!held && mic.isRecording()) {
            byte[] pcm = mic.stopAndGetPcm();
            if (pcm == null) {
                client.player.sendMessage(Text.literal("§7[dog] (too short)"), true);
                return;
            }
            client.player.sendMessage(Text.literal("§7[dog] transcribing..."), true);
            LocalWhisper.transcribeAsync(pcm).thenAccept(text -> {
                client.execute(() -> {
                    if (text == null || text.isEmpty()) {
                        if (client.player != null) {
                            client.player.sendMessage(Text.literal("§c[dog] couldn't hear that"), true);
                        }
                        return;
                    }
                    String clean = text.trim();
                    if (client.player != null && client.player.networkHandler != null) {
                        client.player.sendMessage(Text.literal("§a[dog] heard: §f" + clean), true);
                        client.player.networkHandler.sendChatMessage(clean);
                    }
                });
            }).exceptionally(t -> {
                LlmDogMod.LOGGER.warn("[LLM Dog] STT error: {}", t.getMessage());
                client.execute(() -> {
                    if (client.player != null) {
                        client.player.sendMessage(Text.literal("§c[dog] STT failed: " + t.getMessage()), true);
                    }
                });
                return null;
            });
        }
    }
}
