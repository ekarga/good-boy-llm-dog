package ai.orca.llmdog.mixin;

import ai.orca.llmdog.client.anim.DogPoses;
import ai.orca.llmdog.net.DogPosePayload;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.WolfEntityModel;
import net.minecraft.entity.passive.WolfEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds Good Boy poses to the VANILLA wolf model. Runs at the tail of setAngles,
 * after vanilla has set every transform.
 *
 * IMPORTANT: for a wolf with NO active pose we touch nothing and return, so the
 * vanilla sit/stand/walk animations are 100% intact (the model instance is
 * shared across all wolves). We only ever ADD rotations for actively-posed
 * wolves; vanilla recomputes rotations from scratch each frame, so nothing
 * leaks. We never write pivots — vanilla repositions pivots for the sit pose,
 * and clobbering them was what broke sitting.
 */
@Mixin(WolfEntityModel.class)
public abstract class WolfEntityModelMixin {
    @Shadow @Final private ModelPart head;
    @Shadow @Final private ModelPart torso;
    @Shadow @Final private ModelPart neck;
    @Shadow @Final private ModelPart tail;
    @Shadow @Final private ModelPart rightFrontLeg;
    @Shadow @Final private ModelPart leftFrontLeg;
    @Shadow @Final private ModelPart rightHindLeg;
    @Shadow @Final private ModelPart leftHindLeg;

    @Inject(method = "setAngles(Lnet/minecraft/entity/passive/WolfEntity;FFFFF)V", at = @At("TAIL"))
    private void llmdog$pose(WolfEntity entity, float limbAngle, float limbDistance,
                             float age, float headYaw, float headPitch, CallbackInfo ci) {
        DogPoses.Entry e = DogPoses.get(entity.getId());
        if (e == null) return; // not posed -> leave the vanilla animation untouched
        float p = DogPoses.progress(e);

        switch (e.pose) {
            case DogPosePayload.PAW -> {
                // Lift the right front paw up, then back down over the duration.
                rightFrontLeg.pitch -= (float) Math.sin(p * Math.PI) * 1.4f;
            }
            case DogPosePayload.SHAKE -> {
                // Side-to-side body wiggle, eased in and out.
                float r = (float) (Math.sin(p * Math.PI * 8.0) * 0.30 * Math.sin(p * Math.PI));
                torso.roll += r; neck.roll += r; head.roll += r; tail.roll += r * 0.5f;
            }
            case DogPosePayload.DOWN -> {
                // Rotation-only lie-down: splay the legs flat and dip the head.
                // No pivot writes, so other wolves are unaffected.
                rightFrontLeg.pitch = -1.45f; leftFrontLeg.pitch = -1.45f;
                rightHindLeg.pitch = 1.45f;   leftHindLeg.pitch = 1.45f;
                head.pitch += 0.2f;
            }
            default -> { }
        }
    }
}
