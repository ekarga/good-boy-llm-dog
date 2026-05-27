package ai.orca.llmdog.client.model;

import ai.orca.llmdog.entity.LlmDogEntity;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/**
 * Vanilla 1.21.1 WolfEntityModel geometry, with floppy ears swapped in for
 * the upright pointed ears. All other dimensions, pivots, hierarchy (head /
 * real_head, body, upper_body, tail / real_tail, legs) are exactly vanilla.
 */
public class LlmDogEntityModel extends EntityModel<LlmDogEntity> {
    public static final EntityModelLayer LAYER = new EntityModelLayer(Identifier.of("llm_dog", "llm_dog"), "main");

    private final ModelPart root;
    private final ModelPart head;
    private final ModelPart realHead;
    private final ModelPart body;
    private final ModelPart upperBody;
    private final ModelPart legFrontLeft;
    private final ModelPart legFrontRight;
    private final ModelPart legBackLeft;
    private final ModelPart legBackRight;
    private final ModelPart tail;
    private final ModelPart realTail;

    public LlmDogEntityModel(ModelPart root) {
        this.root = root;
        this.head = root.getChild("head");
        this.realHead = this.head.getChild("real_head");
        this.body = root.getChild("body");
        this.upperBody = root.getChild("upper_body");
        this.legBackRight = root.getChild("right_hind_leg");
        this.legBackLeft = root.getChild("left_hind_leg");
        this.legFrontRight = root.getChild("right_front_leg");
        this.legFrontLeft = root.getChild("left_front_leg");
        this.tail = root.getChild("tail");
        this.realTail = this.tail.getChild("real_tail");
    }

    public static TexturedModelData getTexturedModelData() {
        ModelData modelData = new ModelData();
        ModelPartData rootData = modelData.getRoot();

        // head wraps real_head (vanilla pattern: pivot on head, cuboids on real_head)
        ModelPartData head = rootData.addChild(
            "head",
            ModelPartBuilder.create(),
            ModelTransform.pivot(-1.0F, 13.5F, -7.0F));

        // real_head holds skull + snout; ears are SEPARATE children so we can
        // rotate them independently for the "floppy" look. Texture is the
        // vanilla wolf.png so all UVs must match vanilla cube dimensions
        // exactly (2x2x1 ears, 6x6x4 head, 3x3x4 snout).
        ModelPartData realHead = head.addChild(
            "real_head",
            ModelPartBuilder.create()
                .uv(0, 0).cuboid(-2.0F, -3.0F, -2.0F, 6.0F, 6.0F, 4.0F)
                .uv(0, 10).cuboid(-0.5F, -0.001F, -5.0F, 3.0F, 3.0F, 4.0F),
            ModelTransform.NONE);

        // Floppy ears: vanilla 2x2x1 geometry/UVs, but pitched forward+down
        // (0.6 rad ≈ 34°) and rolled outward 0.25 rad so they droop instead of
        // standing upright.
        realHead.addChild(
            "ear_left",
            ModelPartBuilder.create().uv(16, 14).cuboid(-1.0F, -1.0F, 0.0F, 2.0F, 2.0F, 1.0F),
            ModelTransform.of(-1.0F, -3.5F, 0.5F, 0.6F, 0.0F, -0.25F));
        realHead.addChild(
            "ear_right",
            ModelPartBuilder.create().uv(16, 14).cuboid(-1.0F, -1.0F, 0.0F, 2.0F, 2.0F, 1.0F),
            ModelTransform.of(3.0F, -3.5F, 0.5F, 0.6F, 0.0F, 0.25F));

        // body (exactly vanilla)
        rootData.addChild(
            "body",
            ModelPartBuilder.create().uv(18, 14).cuboid(-3.0F, -2.0F, -3.0F, 6.0F, 9.0F, 6.0F),
            ModelTransform.of(0.0F, 14.0F, 2.0F, (float)(Math.PI / 2.0), 0.0F, 0.0F));

        // upper_body / mane / neck (exactly vanilla -- THIS is what bridges head and body)
        rootData.addChild(
            "upper_body",
            ModelPartBuilder.create().uv(21, 0).cuboid(-3.0F, -3.0F, -3.0F, 8.0F, 6.0F, 7.0F),
            ModelTransform.of(-1.0F, 14.0F, -3.0F, (float)(Math.PI / 2.0), 0.0F, 0.0F));

        // legs (exactly vanilla -- cuboid starts at x=0, not x=-1)
        ModelPartBuilder legBuilder = ModelPartBuilder.create()
            .uv(0, 18).cuboid(0.0F, 0.0F, -1.0F, 2.0F, 8.0F, 2.0F);
        rootData.addChild("right_hind_leg", legBuilder, ModelTransform.pivot(-2.5F, 16.0F, 7.0F));
        rootData.addChild("left_hind_leg", legBuilder, ModelTransform.pivot(0.5F, 16.0F, 7.0F));
        rootData.addChild("right_front_leg", legBuilder, ModelTransform.pivot(-2.5F, 16.0F, -4.0F));
        rootData.addChild("left_front_leg", legBuilder, ModelTransform.pivot(0.5F, 16.0F, -4.0F));

        // tail wraps real_tail (vanilla pattern)
        ModelPartData tail = rootData.addChild(
            "tail",
            ModelPartBuilder.create(),
            ModelTransform.of(-1.0F, 12.0F, 8.0F, (float)(Math.PI / 5.0), 0.0F, 0.0F));
        tail.addChild(
            "real_tail",
            ModelPartBuilder.create().uv(9, 18).cuboid(0.0F, 0.0F, -1.0F, 2.0F, 8.0F, 2.0F),
            ModelTransform.NONE);

        return TexturedModelData.of(modelData, 64, 32);
    }

    @Override
    public void setAngles(LlmDogEntity entity, float limbAngle, float limbDistance, float animationProgress, float headYaw, float headPitch) {
        // head yaw/pitch from looking-around state
        this.head.pitch = headPitch * (float)(Math.PI / 180.0);
        this.head.yaw = headYaw * (float)(Math.PI / 180.0);
        this.tail.pitch = animationProgress;

        if (entity.isInSittingPose()) {
            // vanilla wolf sit pose (exact pivots + rotations)
            this.upperBody.setPivot(-1.0F, 16.0F, -3.0F);
            this.upperBody.pitch = (float)(Math.PI * 2.0 / 5.0);
            this.upperBody.yaw = 0.0F;
            this.body.setPivot(0.0F, 18.0F, 0.0F);
            this.body.pitch = (float)(Math.PI / 4.0);
            this.tail.setPivot(-1.0F, 21.0F, 6.0F);
            this.legBackRight.setPivot(-2.5F, 22.7F, 2.0F);
            this.legBackRight.pitch = (float)(Math.PI * 3.0 / 2.0);
            this.legBackLeft.setPivot(0.5F, 22.7F, 2.0F);
            this.legBackLeft.pitch = (float)(Math.PI * 3.0 / 2.0);
            this.legFrontRight.pitch = 5.811947F;
            this.legFrontRight.setPivot(-2.49F, 17.0F, -4.0F);
            this.legFrontLeft.pitch = 5.811947F;
            this.legFrontLeft.setPivot(0.51F, 17.0F, -4.0F);
        } else {
            // standing/walking pose
            this.body.setPivot(0.0F, 14.0F, 2.0F);
            this.body.pitch = (float)(Math.PI / 2.0);
            this.upperBody.setPivot(-1.0F, 14.0F, -3.0F);
            this.upperBody.pitch = this.body.pitch;
            this.tail.setPivot(-1.0F, 12.0F, 8.0F);
            this.legBackRight.setPivot(-2.5F, 16.0F, 7.0F);
            this.legBackLeft.setPivot(0.5F, 16.0F, 7.0F);
            this.legFrontRight.setPivot(-2.5F, 16.0F, -4.0F);
            this.legFrontLeft.setPivot(0.5F, 16.0F, -4.0F);
            float speed = 0.6662F;
            this.legBackRight.pitch = MathHelper.cos(limbAngle * speed) * 1.4F * limbDistance;
            this.legBackLeft.pitch = MathHelper.cos(limbAngle * speed + (float)Math.PI) * 1.4F * limbDistance;
            this.legFrontRight.pitch = MathHelper.cos(limbAngle * speed + (float)Math.PI) * 1.4F * limbDistance;
            this.legFrontLeft.pitch = MathHelper.cos(limbAngle * speed) * 1.4F * limbDistance;
        }
    }

    @Override
    public void render(MatrixStack matrices, VertexConsumer vertices, int light, int overlay, int color) {
        root.render(matrices, vertices, light, overlay, color);
    }
}
