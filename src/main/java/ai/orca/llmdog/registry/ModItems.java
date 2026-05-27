package ai.orca.llmdog.registry;

import ai.orca.llmdog.LlmDogMod;
import ai.orca.llmdog.item.GoodBoyEggItem;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class ModItems {
    public static final Item GOOD_BOY_SPAWN_EGG = new GoodBoyEggItem(
        ModEntities.LLM_DOG,
        0xC9A37A,
        0x4A3526,
        new Item.Settings()
    );

    public static void register() {
        Registry.register(Registries.ITEM, Identifier.of(LlmDogMod.MOD_ID, "good_boy_spawn_egg"), GOOD_BOY_SPAWN_EGG);
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.SPAWN_EGGS).register(entries -> entries.add(GOOD_BOY_SPAWN_EGG));
    }
}
