package ai.orca.llmdog.registry;

import ai.orca.llmdog.LlmDogMod;
import ai.orca.llmdog.entity.LlmDogEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class ModEntities {
    public static final EntityType<LlmDogEntity> LLM_DOG;

    static {
        Identifier id = Identifier.of(LlmDogMod.MOD_ID, "llm_dog");
        LLM_DOG = Registry.register(
            Registries.ENTITY_TYPE,
            id,
            EntityType.Builder.create(LlmDogEntity::new, SpawnGroup.CREATURE)
                .dimensions(0.7f, 0.95f)
                .maxTrackingRange(10)
                .build("llm_dog")
        );
    }

    public static void register() {
        // Static initializer handles registration. This method exists to force class init.
    }
}
