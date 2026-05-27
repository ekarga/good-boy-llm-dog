package ai.orca.llmdog.goal;

import ai.orca.llmdog.entity.LlmDogEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.EnumSet;
import java.util.WeakHashMap;

public class DogComeGoal extends Goal {
    private static final WeakHashMap<LlmDogEntity, Long> requests = new WeakHashMap<>();
    private static final int MAX_RANGE = 16;
    private static final int STUCK_TICKS = 60; // 3 seconds at 20 tps

    private final LlmDogEntity dog;
    private final double speed;
    private LivingEntity owner;
    private int ticksRunning;
    private Vec3d lastPos;
    private int stuckTicks;

    public DogComeGoal(LlmDogEntity dog, double speed) {
        this.dog = dog;
        this.speed = speed;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    public static void requestCome(LlmDogEntity dog) {
        requests.put(dog, dog.getWorld().getTime());
    }

    @Override
    public boolean canStart() {
        if (dog.isSitting()) return false;
        Long req = requests.get(dog);
        if (req == null) return false;
        if (dog.getWorld().getTime() - req > 5) {
            requests.remove(dog);
            return false;
        }
        LivingEntity o = dog.getOwner();
        if (o == null) return false;
        if (dog.squaredDistanceTo(o) > (double) MAX_RANGE * MAX_RANGE) {
            // far -- still try, will teleport if stuck
            this.owner = o;
            return true;
        }
        this.owner = o;
        return true;
    }

    @Override
    public boolean shouldContinue() {
        if (owner == null) return false;
        if (dog.isSitting()) return false;
        double d = dog.squaredDistanceTo(owner);
        return d > 6.0;
    }

    @Override
    public void start() {
        ticksRunning = 0;
        stuckTicks = 0;
        lastPos = dog.getPos();
        requests.remove(dog);
        dog.getNavigation().startMovingTo(owner, speed);
    }

    @Override
    public void stop() {
        dog.getNavigation().stop();
        owner = null;
    }

    @Override
    public void tick() {
        ticksRunning++;
        dog.getLookControl().lookAt(owner, 30.0F, 30.0F);
        if (ticksRunning % 10 == 0) {
            dog.getNavigation().startMovingTo(owner, speed);
        }
        Vec3d cur = dog.getPos();
        if (lastPos != null && cur.squaredDistanceTo(lastPos) < 0.02) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }
        lastPos = cur;
        if (stuckTicks >= STUCK_TICKS) {
            // teleport adjacent to owner
            teleportNear(owner);
            stuckTicks = 0;
        }
    }

    private void teleportNear(LivingEntity target) {
        World w = dog.getWorld();
        double x = target.getX() + (dog.getRandom().nextDouble() - 0.5) * 2;
        double y = target.getY();
        double z = target.getZ() + (dog.getRandom().nextDouble() - 0.5) * 2;
        dog.requestTeleport(x, y, z);
        dog.getNavigation().stop();
    }
}
