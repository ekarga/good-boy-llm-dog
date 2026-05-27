package ai.orca.llmdog;

import ai.orca.llmdog.ai.Bootstrap;
import ai.orca.llmdog.ai.LocalLlm;
import ai.orca.llmdog.config.LlmDogConfig;
import ai.orca.llmdog.entity.LlmDogEntity;
import ai.orca.llmdog.llm.IntentParser;
import ai.orca.llmdog.registry.ModEntities;
import ai.orca.llmdog.registry.ModItems;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class LlmDogMod implements ModInitializer {
    public static final String MOD_ID = "llm_dog";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static LlmDogConfig config;

    @Override
    public void onInitialize() {
        config = LlmDogConfig.load();
        Bootstrap.ensureExtracted();
        // Init the in-process LLM off-thread so server boot isn't blocked
        new Thread(LocalLlm::init, "good-boy-llm-init").start();

        ModEntities.register();
        ModItems.register();

        FabricDefaultAttributeRegistry.register(ModEntities.LLM_DOG, LlmDogEntity.createDogAttributes());

        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            String text = message.getContent().getString();
            handleChat(sender, text);
        });

        LOGGER.info("[LLM Dog] initialized. llm enabled={} model={}", config.enableLlm, config.model);
    }

    private static void handleChat(ServerPlayerEntity sender, String rawText) {
        if (sender == null || rawText == null) return;
        String text = rawText.trim();
        if (text.isEmpty()) return;

        boolean hasPrefix = text.toLowerCase().startsWith("dog:");
        String payload = hasPrefix ? text.substring(4).trim() : text;

        ServerWorld world = sender.getServerWorld();
        LlmDogEntity dog = findOwnedDog(world, sender, config.listenRadius);
        if (dog == null) {
            int total = world.getEntitiesByClass(LlmDogEntity.class, sender.getBoundingBox().expand(config.listenRadius), d -> true).size();
            int tamed = world.getEntitiesByClass(LlmDogEntity.class, sender.getBoundingBox().expand(config.listenRadius), d -> d.isTamed()).size();
            int owned = world.getEntitiesByClass(LlmDogEntity.class, sender.getBoundingBox().expand(config.listenRadius), d -> d.isTamed() && d.isOwner(sender)).size();
            LOGGER.info("[LLM Dog] chat from {} but no owned dog nearby (within {} blocks: total={}, tamed={}, owned-by-you={}): \"{}\"",
                sender.getName().getString(), config.listenRadius, total, tamed, owned, text);
            return;
        }
        if (!hasPrefix && dog.squaredDistanceTo(sender) > (double) config.listenRadius * config.listenRadius) return;
        LOGGER.info("[LLM Dog] chat -> dog: from={} prefix={} text={}", sender.getName().getString(), hasPrefix, payload);

        if (!config.enableLlm) {
            String intent = IntentParser.keywordFallback(payload);
            if (intent != null) dog.executeIntent(intent);
            else dog.whineNoOp();
            return;
        }

        LocalLlm.getIntentAsync(payload).thenAccept(intent -> {
            // Fall back to keyword match if LLM didn't produce a usable intent
            String finalIntent = intent != null ? intent : IntentParser.keywordFallback(payload);
            world.getServer().execute(() -> {
                if (finalIntent == null) dog.whineNoOp();
                else dog.executeIntent(finalIntent);
            });
        }).exceptionally(t -> {
            LOGGER.warn("[Good Boy] LLM call failed: {}", t.getMessage());
            String kw = IntentParser.keywordFallback(payload);
            world.getServer().execute(() -> {
                if (kw == null) dog.whineNoOp();
                else dog.executeIntent(kw);
            });
            return null;
        });
    }

    private static LlmDogEntity findOwnedDog(ServerWorld world, PlayerEntity owner, int radius) {
        double r = radius;
        Box box = new Box(
            owner.getX() - r, owner.getY() - r, owner.getZ() - r,
            owner.getX() + r, owner.getY() + r, owner.getZ() + r
        );
        List<LlmDogEntity> dogs = world.getEntitiesByClass(LlmDogEntity.class, box, d -> d.isTamed() && d.isOwner(owner));
        LlmDogEntity closest = null;
        double bestDist = Double.MAX_VALUE;
        for (LlmDogEntity d : dogs) {
            double dist = d.squaredDistanceTo(owner);
            if (dist < bestDist) { bestDist = dist; closest = d; }
        }
        return closest;
    }
}
