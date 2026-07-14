package ai.orca.llmdog;

import ai.orca.llmdog.ai.Bootstrap;
import ai.orca.llmdog.config.LlmDogConfig;
import ai.orca.llmdog.entity.WolfCommander;
import ai.orca.llmdog.llm.IntentParser;
import ai.orca.llmdog.llm.MercuryIntentClient;
import ai.orca.llmdog.net.DogCommandPayload;
import ai.orca.llmdog.net.DogPosePayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class LlmDogMod implements ModInitializer {
    public static final String MOD_ID = "llm_dog";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static LlmDogConfig config;

    @Override
    public void onInitialize() {
        config = LlmDogConfig.load();
        Bootstrap.ensureExtracted();

        // S2C pose packet (paw / down / shake) — registered on both sides here.
        PayloadTypeRegistry.playS2C().register(DogPosePayload.ID, DogPosePayload.CODEC);

        // C2S resolved-command packet from the voice path (handler runs on the
        // server thread). Intents are re-validated by the WolfCommander switch,
        // which ignores anything it doesn't know.
        PayloadTypeRegistry.playC2S().register(DogCommandPayload.ID, DogCommandPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(DogCommandPayload.ID, (payload, context) -> {
            List<String> intents = new ArrayList<>();
            for (String s : payload.intentsCsv().split(",")) {
                if (!s.isBlank()) intents.add(s.trim());
                if (intents.size() >= 6) break;
            }
            if (!intents.isEmpty()) commandWolves(context.player(), intents);
        });

        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            String text = message.getContent().getString();
            handleChat(sender, text);
        });

        // Advances time-based wolf commands (spin, come) each tick.
        ServerTickEvents.END_SERVER_TICK.register(WolfCommander::tick);

        LOGGER.info("[Good Boy] initialized. Trained vanilla wolves obey (local parser + Mercury dLLM fallback{}).",
            MercuryIntentClient.enabled() ? "" : " — NO API KEY, model OFF");
    }

    private static void handleChat(ServerPlayerEntity sender, String rawText) {
        if (sender == null || rawText == null) return;
        String text = rawText.trim();
        if (text.isEmpty()) return;

        boolean hasPrefix = text.toLowerCase().startsWith("dog:");
        String payload = hasPrefix ? text.substring(4).trim() : text;

        // Short utterances resolve instantly offline; sentences go through
        // Mercury, which understands negation ("you don't want to attack").
        // No command either way -> silent no-op.
        MercuryIntentClient.resolve(payload).thenAccept(intents -> {
            if (intents.isEmpty()) return;
            // Hop back onto the server thread before touching the world.
            sender.getServer().execute(() -> {
                if (!sender.isRemoved()) commandWolves(sender, intents);
            });
        });
    }

    /** Run an intent sequence on ALL trained (tamed + owned) wolves in range — the whole pack. */
    private static void commandWolves(ServerPlayerEntity sender, List<String> intents) {
        ServerWorld world = sender.getServerWorld();
        List<WolfEntity> wolves = findOwnedWolves(world, sender, config.listenRadius);
        if (wolves.isEmpty()) {
            int total = world.getEntitiesByClass(WolfEntity.class, sender.getBoundingBox().expand(config.listenRadius), d -> true).size();
            LOGGER.info("[Good Boy] command from {} but no trained wolf nearby (within {} blocks; wolves seen={}): {}",
                sender.getName().getString(), config.listenRadius, total, intents);
            return;
        }

        List<UUID> ids = new ArrayList<>(wolves.size());
        for (WolfEntity w : wolves) ids.add(w.getUuid());
        LOGGER.info("[Good Boy] {} trained wolf(s) <- {}", ids.size(), intents);
        WolfCommander.runSequence(world.getServer(), sender.getUuid(), ids, intents);
    }

    /** ALL vanilla wolves the player has tamed (= trained) and owns, within radius. */
    private static List<WolfEntity> findOwnedWolves(ServerWorld world, PlayerEntity owner, int radius) {
        double r = radius;
        Box box = new Box(
            owner.getX() - r, owner.getY() - r, owner.getZ() - r,
            owner.getX() + r, owner.getY() + r, owner.getZ() + r
        );
        return world.getEntitiesByClass(WolfEntity.class, box, d -> d.isTamed() && d.isOwner(owner));
    }
}
