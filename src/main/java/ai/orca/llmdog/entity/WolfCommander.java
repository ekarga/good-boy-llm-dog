package ai.orca.llmdog.entity;

import ai.orca.llmdog.LlmDogMod;
import ai.orca.llmdog.net.DogPosePayload;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Drives a VANILLA tamed {@link WolfEntity} in response to voice/chat intents.
 * No custom entity required: any wolf the player has tamed (= "trained") obeys.
 *
 * Immediate effects (sit, stand, attack, jump, praise, diamonds) apply right
 * away. Time-based effects (spin, come) register transient state advanced each
 * server tick from {@link #tick}.
 */
public final class WolfCommander {
    private WolfCommander() {}

    private static final Map<UUID, State> ACTIVE = new ConcurrentHashMap<>();

    private static final class State {
        int spinTicks = 0;
        float spinPerTick = 0f;
        int comeTicks = 0;
        UUID ownerId;
    }

    /** Ticks between commands in a chained utterance (~0.7s) so they read as a sequence. */
    private static final int SEQUENCE_GAP_TICKS = 14;

    private static final List<Pending> QUEUE = new CopyOnWriteArrayList<>();

    private static final class Pending {
        int delay;
        final UUID ownerId;
        final List<UUID> wolfIds;
        final String intent;
        Pending(int delay, UUID ownerId, List<UUID> wolfIds, String intent) {
            this.delay = delay; this.ownerId = ownerId; this.wolfIds = wolfIds; this.intent = intent;
        }
    }

    /**
     * Run an ordered sequence of intents against the given wolves. The first
     * fires (almost) immediately; each subsequent one is spaced by
     * {@link #SEQUENCE_GAP_TICKS} so "good boy, sit" plays as praise THEN sit.
     */
    public static void runSequence(MinecraftServer server, UUID ownerId, List<UUID> wolfIds, List<String> intents) {
        if (intents == null || intents.isEmpty() || wolfIds == null || wolfIds.isEmpty()) return;
        int delay = 0;
        for (String intent : intents) {
            QUEUE.add(new Pending(delay, ownerId, wolfIds, intent));
            delay += SEQUENCE_GAP_TICKS;
        }
    }

    private static void fire(MinecraftServer server, Pending p) {
        ServerPlayerEntity owner = server.getPlayerManager().getPlayer(p.ownerId);
        if (owner == null) return; // owner logged out — drop the rest gracefully
        for (UUID id : p.wolfIds) {
            WolfEntity wolf = resolveWolf(server, id);
            if (wolf != null && wolf.isAlive()) execute(wolf, owner, p.intent);
        }
    }

    @Nullable
    private static WolfEntity resolveWolf(MinecraftServer server, UUID id) {
        for (ServerWorld w : server.getWorlds()) {
            if (w.getEntity(id) instanceof WolfEntity found) return found;
        }
        return null;
    }

    public static void execute(WolfEntity wolf, ServerPlayerEntity owner, String intent) {
        if (wolf == null || intent == null) return;
        if (!(wolf.getWorld() instanceof ServerWorld world)) return;
        String it = intent.trim().toLowerCase();
        LlmDogMod.LOGGER.info("[Good Boy] wolf intent: {} (wolf {})", it, wolf.getId());

        // Optional argument after a colon, e.g. "attack:slime" from Mercury.
        String arg = null;
        int colon = it.indexOf(':');
        if (colon > 0) {
            arg = it.substring(colon + 1).trim();
            it = it.substring(0, colon);
        }

        switch (it) {
            case "sit" -> { clearPose(wolf); sit(wolf); clear(wolf); feedback(world, wolf); }
            case "down", "lie_down" -> {
                // Lie flat: freeze like sit, but send the DOWN pose (held) so the
                // client model mixin draws a lie-down instead of the sit pose.
                clearPose(wolf); sit(wolf); clear(wolf);
                sendPose(wolf, DogPosePayload.DOWN, -1);
                feedback(world, wolf);
            }
            case "stand", "stay", "follow", "heel" -> {
                clearPose(wolf); stand(wolf); clear(wolf); feedback(world, wolf);
            }
            case "come", "here" -> {
                clearPose(wolf); stand(wolf);
                State s = ACTIVE.computeIfAbsent(wolf.getUuid(), k -> new State());
                s.comeTicks = 100; // ~5s window to reach the owner
                s.ownerId = owner.getUuid();
                feedback(world, wolf);
            }
            case "attack", "kill", "sic" -> {
                clearPose(wolf); stand(wolf);
                LivingEntity target = (arg != null && !arg.isEmpty())
                    ? pickNamedTarget(wolf, owner, arg)
                    : pickAttackTarget(wolf, owner);
                if (target != null) {
                    wolf.setTarget(target);
                    feedback(world, wolf);
                } else {
                    whine(world, wolf);
                }
            }
            case "jump", "hop" -> { clearPose(wolf); stand(wolf); jump(wolf); feedback(world, wolf); }
            case "spin", "twirl" -> {
                clearPose(wolf); stand(wolf);
                State s = ACTIVE.computeIfAbsent(wolf.getUuid(), k -> new State());
                s.spinTicks = 20;
                s.spinPerTick = 360f / 20f;
                wolf.getNavigation().stop();
                feedback(world, wolf);
            }
            case "paw" -> {
                // One-shot: lift a front paw for ~1s, then back down.
                stand(wolf); wolf.getNavigation().stop();
                sendPose(wolf, DogPosePayload.PAW, 20);
                feedback(world, wolf);
            }
            case "shake" -> {
                // One-shot body shake with the vanilla wet-shake sound.
                stand(wolf); wolf.getNavigation().stop();
                sendPose(wolf, DogPosePayload.SHAKE, 18);
                world.playSound(null, wolf.getX(), wolf.getY(), wolf.getZ(),
                    SoundEvents.ENTITY_WOLF_SHAKE, SoundCategory.NEUTRAL, 0.7f, 1.0f);
                feedback(world, wolf);
            }
            case "bark", "speak", "woof" -> {
                // The bark IS the feedback — no extra confirmation sound.
                world.playSound(null, wolf.getX(), wolf.getY(), wolf.getZ(),
                    SoundEvents.ENTITY_WOLF_AMBIENT, SoundCategory.NEUTRAL, 1.2f, 1.0f);
            }
            case "good_boy", "goodboy", "good boy", "praise" -> hearts(world, wolf);
            case "bad_boy", "badboy", "bad boy", "scold" -> brokenHeart(world, wolf);
            case "diamonds", "diamond" -> { dropDiamonds(world, wolf, owner); feedback(world, wolf); }
            case "none", "" -> { /* intentionally silent */ }
            default -> LlmDogMod.LOGGER.debug("[Good Boy] unknown intent: {}", it);
        }
    }

    /** Advance scheduled command sequences + time-based commands. Registered on END_SERVER_TICK. */
    public static void tick(MinecraftServer server) {
        // 1) Fire any due commands from chained sequences.
        if (!QUEUE.isEmpty()) {
            List<Pending> fired = new ArrayList<>();
            for (Pending p : QUEUE) {
                if (p.delay-- <= 0) { fire(server, p); fired.add(p); }
            }
            if (!fired.isEmpty()) QUEUE.removeAll(fired);
        }

        // 2) Advance spin/come state.
        if (ACTIVE.isEmpty()) return;
        ACTIVE.entrySet().removeIf(entry -> {
            State s = entry.getValue();
            WolfEntity wolf = null;
            for (ServerWorld w : server.getWorlds()) {
                if (w.getEntity(entry.getKey()) instanceof WolfEntity found) { wolf = found; break; }
            }
            if (wolf == null || !wolf.isAlive()) return true;

            boolean done = true;

            if (s.spinTicks > 0) {
                float ny = wolf.getBodyYaw() + s.spinPerTick;
                wolf.setBodyYaw(ny);
                wolf.setYaw(wolf.getYaw() + s.spinPerTick);
                wolf.setHeadYaw(ny);
                wolf.getNavigation().stop();
                s.spinTicks--;
                if (s.spinTicks > 0) done = false;
            }

            if (s.comeTicks > 0) {
                PlayerEntity owner = wolf.getWorld().getPlayerByUuid(s.ownerId);
                if (owner != null && wolf.squaredDistanceTo(owner) > 4.0) {
                    if (wolf.getNavigation().isIdle()) {
                        wolf.getNavigation().startMovingTo(owner, 1.3);
                    }
                    s.comeTicks--;
                    if (s.comeTicks > 0) done = false;
                } else {
                    s.comeTicks = 0; // arrived or owner gone
                }
            }

            return done;
        });
    }

    // --- vanilla-wolf primitives ---

    private static void sit(WolfEntity w) {
        w.setSitting(true);
        w.setInSittingPose(true);
        w.getNavigation().stop();
        w.setTarget(null);
        w.setJumping(false);
    }

    private static void stand(WolfEntity w) {
        w.setSitting(false);
        w.setInSittingPose(false);
    }

    private static void jump(WolfEntity w) {
        if (w.isOnGround()) {
            Vec3d v = w.getVelocity();
            w.setVelocity(v.x, 0.42, v.z);
            w.velocityDirty = true;
        }
    }

    private static void clear(WolfEntity w) {
        ACTIVE.remove(w.getUuid());
    }

    /** Push a pose (paw/down/shake) to every client tracking this wolf. */
    private static void sendPose(WolfEntity w, int pose, int duration) {
        DogPosePayload payload = new DogPosePayload(w.getId(), pose, duration);
        for (ServerPlayerEntity p : PlayerLookup.tracking(w)) {
            ServerPlayNetworking.send(p, payload);
        }
    }

    /** Clear any held pose (e.g. leaving DOWN) on tracking clients. */
    private static void clearPose(WolfEntity w) {
        sendPose(w, DogPosePayload.NONE, 0);
    }

    @Nullable
    private static LivingEntity pickAttackTarget(WolfEntity wolf, PlayerEntity owner) {
        if (owner == null) return null;
        var w = wolf.getWorld();
        Vec3d eye = owner.getCameraPosVec(1.0F);
        Vec3d look = owner.getRotationVec(1.0F);
        Vec3d end = eye.add(look.x * 24, look.y * 24, look.z * 24);
        Box scan = owner.getBoundingBox().stretch(look.multiply(24)).expand(1.0);
        EntityHitResult ehr = ProjectileUtil.raycast(
            owner, eye, end, scan,
            e -> e instanceof LivingEntity && e != wolf && e != owner && !e.isSpectator(),
            24 * 24
        );
        if (ehr != null && ehr.getEntity() instanceof LivingEntity le) {
            HitResult block = w.raycast(new RaycastContext(eye, ehr.getPos(),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, owner));
            if (block.getType() == HitResult.Type.MISS
                || block.getPos().distanceTo(eye) >= ehr.getPos().distanceTo(eye) - 0.5) {
                return le;
            }
        }
        // fallback: nearest hostile within 12 blocks of the wolf
        LivingEntity nearest = null;
        double bestDist = 12 * 12;
        for (HostileEntity h : w.getEntitiesByClass(HostileEntity.class, wolf.getBoundingBox().expand(12.0), e -> true)) {
            double d = h.squaredDistanceTo(wolf);
            if (d < bestDist) { bestDist = d; nearest = h; }
        }
        return nearest;
    }

    /**
     * Nearest living entity within 24 blocks whose entity type matches a
     * spoken name ("slime", "zombies", "iron golem"). Never the owner or a
     * tamed wolf. Substring match on the registry path, so "zombie" also
     * catches zombie_villager.
     */
    @Nullable
    private static LivingEntity pickNamedTarget(WolfEntity wolf, PlayerEntity owner, String name) {
        String want = name.toLowerCase().trim().replace(' ', '_');
        if (want.endsWith("s") && want.length() > 3) want = want.substring(0, want.length() - 1);
        final String needle = want;
        LivingEntity nearest = null;
        double bestDist = 24 * 24;
        for (LivingEntity e : wolf.getWorld().getEntitiesByClass(LivingEntity.class,
                wolf.getBoundingBox().expand(24.0),
                c -> c != wolf && c != owner && c.isAlive() && !c.isSpectator()
                     && !(c instanceof WolfEntity ww && ww.isTamed()))) {
            String path = EntityType.getId(e.getType()).getPath();
            if (!path.contains(needle)) continue;
            double d = e.squaredDistanceTo(wolf);
            if (d < bestDist) { bestDist = d; nearest = e; }
        }
        return nearest;
    }

    // --- feedback effects ---

    public static void whine(ServerWorld world, WolfEntity w) {
        world.playSound(null, w.getX(), w.getY(), w.getZ(),
            SoundEvents.ENTITY_WOLF_WHINE, SoundCategory.NEUTRAL, 0.5f, 1.0f);
    }

    /** Scolded: sad whine + dark broken-heart particles. */
    private static void brokenHeart(ServerWorld world, WolfEntity w) {
        world.playSound(null, w.getX(), w.getY(), w.getZ(),
            SoundEvents.ENTITY_WOLF_WHINE, SoundCategory.NEUTRAL, 0.8f, 0.8f);
        world.spawnParticles(ParticleTypes.DAMAGE_INDICATOR,
            w.getX(), w.getY() + 0.9, w.getZ(), 8, 0.3, 0.3, 0.3, 0.02);
    }

    private static void feedback(ServerWorld world, WolfEntity w) {
        world.playSound(null, w.getX(), w.getY(), w.getZ(),
            SoundEvents.ENTITY_WOLF_AMBIENT, SoundCategory.NEUTRAL, 0.6f, 1.2f);
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
            w.getX(), w.getY() + 0.7, w.getZ(), 6, 0.3, 0.3, 0.3, 0.02);
    }

    private static void hearts(ServerWorld world, WolfEntity w) {
        world.playSound(null, w.getX(), w.getY(), w.getZ(),
            SoundEvents.ENTITY_WOLF_AMBIENT, SoundCategory.NEUTRAL, 0.7f, 1.4f);
        world.spawnParticles(ParticleTypes.HEART,
            w.getX(), w.getY() + 0.9, w.getZ(), 10, 0.4, 0.4, 0.4, 0.02);
    }

    private static void dropDiamonds(ServerWorld world, WolfEntity w, PlayerEntity owner) {
        if (owner == null) { whine(world, w); return; }
        for (int i = 0; i < 3; i++) {
            ItemEntity drop = new ItemEntity(world,
                w.getX(), w.getY() + 0.9, w.getZ(), new ItemStack(Items.DIAMOND, 1));
            Vec3d toOwner = owner.getPos().subtract(w.getPos()).normalize().multiply(0.35);
            drop.setVelocity(toOwner.x + (w.getRandom().nextDouble() - 0.5) * 0.15, 0.30,
                             toOwner.z + (w.getRandom().nextDouble() - 0.5) * 0.15);
            drop.setPickupDelay(10);
            world.spawnEntity(drop);
        }
        world.playSound(null, w.getX(), w.getY(), w.getZ(),
            SoundEvents.ENTITY_WOLF_AMBIENT, SoundCategory.NEUTRAL, 0.8f, 1.2f);
    }
}
