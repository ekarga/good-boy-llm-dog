package ai.orca.llmdog.entity;

import ai.orca.llmdog.LlmDogMod;
import ai.orca.llmdog.goal.DogComeGoal;
import ai.orca.llmdog.goal.DogSpinGoal;
import net.minecraft.entity.AnimationState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.ai.goal.FollowOwnerGoal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.goal.SitGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

public class LlmDogEntity extends TameableEntity {
    public final AnimationState idleAnimationState = new AnimationState();
    public final AnimationState walkAnimationState = new AnimationState();

    private int spinTicksRemaining = 0;
    private float spinPerTick = 0f;
    private int forcedJumpTicks = 0;

    public LlmDogEntity(EntityType<? extends TameableEntity> entityType, World world) {
        super(entityType, world);
        this.setTamed(false, false);
    }

    @Override
    public boolean isBreedingItem(ItemStack stack) {
        return false;
    }

    public static DefaultAttributeContainer.Builder createDogAttributes() {
        return MobEntity.createMobAttributes()
            .add(EntityAttributes.GENERIC_MAX_HEALTH, 22.0)
            .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.32)
            .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 4.0)
            .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 32.0);
    }

    @Override
    protected void initGoals() {
        this.goalSelector.add(0, new SwimGoal(this));
        this.goalSelector.add(1, new SitGoal(this));
        this.goalSelector.add(2, new MeleeAttackGoal(this, 1.2, true));
        this.goalSelector.add(3, new DogComeGoal(this, 1.3));
        this.goalSelector.add(4, new DogSpinGoal(this));
        this.goalSelector.add(5, new FollowOwnerGoal(this, 1.1, 8.0F, 2.5F));
        this.goalSelector.add(6, new WanderAroundFarGoal(this, 0.9));
        this.goalSelector.add(7, new LookAtEntityGoal(this, PlayerEntity.class, 8.0F));
        this.goalSelector.add(8, new LookAroundGoal(this));
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
    }

    @Nullable
    @Override
    public PassiveEntity createChild(ServerWorld world, PassiveEntity entity) {
        return null;
    }

    @Override
    public ActionResult interactMob(PlayerEntity player, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        if (!this.isTamed() && stack.isOf(Items.BONE)) {
            if (!player.getAbilities().creativeMode) stack.decrement(1);
            if (this.getRandom().nextInt(3) == 0) {
                this.setOwner(player);
                this.getNavigation().stop();
                this.setTarget(null);
                this.setSitting(true);
                this.setHealth(this.getMaxHealth());
                if (!this.getWorld().isClient) {
                    this.getWorld().sendEntityStatus(this, (byte) 7);
                }
            } else if (!this.getWorld().isClient) {
                this.getWorld().sendEntityStatus(this, (byte) 6);
            }
            return ActionResult.SUCCESS;
        }
        if (this.isOwner(player) && !this.getWorld().isClient) {
            this.setSitting(!this.isSitting());
            this.jumping = false;
            this.getNavigation().stop();
            this.setTarget(null);
            return ActionResult.SUCCESS;
        }
        return super.interactMob(player, hand);
    }

    @Override
    public void tickMovement() {
        super.tickMovement();
        if (spinTicksRemaining > 0) {
            this.setBodyYaw(this.getBodyYaw() + spinPerTick);
            this.setYaw(this.getYaw() + spinPerTick);
            this.headYaw = this.getBodyYaw();
            spinTicksRemaining--;
        }
        if (forcedJumpTicks > 0) {
            if (this.isOnGround()) {
                this.jumping = true;
            }
            forcedJumpTicks--;
        } else if (!isSpinning()) {
            // keep jumping flag off unless we explicitly asked
        }
    }

    public boolean isSpinning() {
        return spinTicksRemaining > 0;
    }

    public void startSpin(int ticks, float totalDegrees) {
        this.spinTicksRemaining = Math.max(1, ticks);
        this.spinPerTick = totalDegrees / this.spinTicksRemaining;
        this.setSitting(false);
        this.getNavigation().stop();
    }

    public void triggerJump() {
        this.setSitting(false);
        this.forcedJumpTicks = 1;
    }

    public void executeIntent(String intent) {
        if (intent == null) return;
        String it = intent.trim().toLowerCase();
        World world = this.getWorld();
        if (!(world instanceof ServerWorld serverWorld)) return;

        LlmDogMod.LOGGER.info("[LLM Dog] intent fired: {} (dog {})", it, this.getId());

        switch (it) {
            case "sit" -> {
                this.setSitting(true);
                this.getNavigation().stop();
                this.setTarget(null);
                feedback(serverWorld);
            }
            case "stand", "get up", "getup", "up" -> {
                this.setSitting(false);
                feedback(serverWorld);
            }
            case "follow" -> {
                this.setSitting(false);
                this.setTarget(null);
                feedback(serverWorld);
            }
            case "come" -> {
                this.setSitting(false);
                if (this.getOwner() != null) {
                    DogComeGoal.requestCome(this);
                }
                feedback(serverWorld);
            }
            case "attack" -> {
                this.setSitting(false);
                LivingEntity target = pickAttackTarget();
                if (target != null) {
                    this.setTarget(target);
                    feedback(serverWorld);
                } else {
                    whineNoOp();
                }
            }
            case "jump" -> {
                triggerJump();
                feedback(serverWorld);
            }
            case "spin" -> {
                startSpin(20, 360f);
                feedback(serverWorld);
            }
            case "good_boy", "goodboy", "praise" -> {
                hearts(serverWorld);
            }
            case "diamonds", "diamond" -> {
                dropDiamonds(serverWorld);
                feedback(serverWorld);
            }
            case "none", "" -> {
                // intentionally silent
            }
            default -> {
                LlmDogMod.LOGGER.debug("[LLM Dog] unknown intent: {}", it);
            }
        }
    }

    @Nullable
    private LivingEntity pickAttackTarget() {
        PlayerEntity owner = (PlayerEntity) this.getOwner();
        if (owner == null) return null;
        World w = this.getWorld();
        Vec3d eye = owner.getCameraPosVec(1.0F);
        Vec3d look = owner.getRotationVec(1.0F);
        Vec3d end = eye.add(look.x * 24, look.y * 24, look.z * 24);
        net.minecraft.util.math.Box scan = owner.getBoundingBox().stretch(look.multiply(24)).expand(1.0);
        EntityHitResult ehr = net.minecraft.entity.projectile.ProjectileUtil.raycast(
            owner, eye, end, scan,
            e -> e instanceof LivingEntity && e != this && e != owner && !e.isSpectator(),
            24 * 24
        );
        if (ehr != null && ehr.getEntity() instanceof LivingEntity le) {
            HitResult block = w.raycast(new RaycastContext(eye, ehr.getPos(), RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, owner));
            if (block.getType() == HitResult.Type.MISS || block.getPos().distanceTo(eye) >= ehr.getPos().distanceTo(eye) - 0.5) {
                return le;
            }
        }
        // fallback: nearest hostile within 12 blocks
        LivingEntity nearest = null;
        double bestDist = 12 * 12;
        for (HostileEntity h : w.getEntitiesByClass(HostileEntity.class, this.getBoundingBox().expand(12.0), e -> true)) {
            double d = h.squaredDistanceTo(this);
            if (d < bestDist) { bestDist = d; nearest = h; }
        }
        return nearest;
    }

    public void whineNoOp() {
        World w = this.getWorld();
        if (w instanceof ServerWorld sw) {
            sw.playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.ENTITY_WOLF_WHINE, SoundCategory.NEUTRAL, 0.5f, 1.0f);
        }
    }

    private void feedback(ServerWorld world) {
        world.playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.ENTITY_WOLF_AMBIENT, SoundCategory.NEUTRAL, 0.6f, 1.2f);
        world.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
            this.getX(), this.getY() + 0.7, this.getZ(),
            6, 0.3, 0.3, 0.3, 0.02);
    }

    private void hearts(ServerWorld world) {
        world.playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.ENTITY_WOLF_AMBIENT, SoundCategory.NEUTRAL, 0.7f, 1.4f);
        world.spawnParticles(ParticleTypes.HEART,
            this.getX(), this.getY() + 0.9, this.getZ(),
            10, 0.4, 0.4, 0.4, 0.02);
    }

    private void dropDiamonds(ServerWorld world) {
        LivingEntity owner = this.getOwner();
        if (owner == null) { whineNoOp(); return; }
        for (int i = 0; i < 3; i++) {
            ItemEntity drop = new ItemEntity(world,
                this.getX(), this.getY() + 0.9, this.getZ(),
                new ItemStack(Items.DIAMOND, 1));
            Vec3d toOwner = owner.getPos().subtract(this.getPos()).normalize().multiply(0.35);
            drop.setVelocity(toOwner.x + (this.getRandom().nextDouble() - 0.5) * 0.15,
                             0.30,
                             toOwner.z + (this.getRandom().nextDouble() - 0.5) * 0.15);
            drop.setPickupDelay(10);
            world.spawnEntity(drop);
        }
        world.playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.ENTITY_WOLF_AMBIENT, SoundCategory.NEUTRAL, 0.8f, 1.2f);
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.ENTITY_WOLF_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.ENTITY_WOLF_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.ENTITY_WOLF_DEATH;
    }

    @Override
    protected float getSoundVolume() {
        return 0.5F;
    }
}
