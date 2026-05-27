package ai.orca.llmdog.item;

import ai.orca.llmdog.entity.LlmDogEntity;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.world.event.GameEvent;

import java.util.Objects;

/**
 * Spawn egg that auto-tames the Good Boy to the player who places it.
 * Same machinery as vanilla SpawnEggItem.useOnBlock but with a tame hook.
 */
public class GoodBoyEggItem extends SpawnEggItem {
    public GoodBoyEggItem(EntityType<? extends MobEntity> type, int primary, int secondary, Item.Settings settings) {
        super(type, primary, secondary, settings);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        World world = context.getWorld();
        if (!(world instanceof ServerWorld serverWorld)) return ActionResult.SUCCESS;

        ItemStack stack = context.getStack();
        BlockPos pos = context.getBlockPos();
        Direction direction = context.getSide();
        BlockState state = world.getBlockState(pos);
        BlockPos spawnPos = state.getCollisionShape(world, pos).isEmpty() ? pos : pos.offset(direction);

        EntityType<?> entityType = this.getEntityType(stack);
        Entity spawned = entityType.spawnFromItemStack(
            serverWorld,
            stack,
            context.getPlayer(),
            spawnPos,
            SpawnReason.SPAWN_EGG,
            true,
            !Objects.equals(pos, spawnPos) && direction == Direction.UP
        );

        if (spawned != null) {
            stack.decrement(1);
            world.emitGameEvent(context.getPlayer(), GameEvent.ENTITY_PLACE, pos);
            if (spawned instanceof LlmDogEntity dog && context.getPlayer() != null) {
                dog.setOwner(context.getPlayer());
                dog.setTamed(true, true);
                dog.setHealth(dog.getMaxHealth());
                dog.setSitting(false);
                serverWorld.sendEntityStatus(dog, (byte) 7); // heart particles
            }
        }
        return ActionResult.SUCCESS;
    }
}
