package ai.orca.llmdog.client.render;

import ai.orca.llmdog.LlmDogMod;
import ai.orca.llmdog.client.model.LlmDogEntityModel;
import ai.orca.llmdog.entity.LlmDogEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.util.Identifier;

public class LlmDogRenderer extends MobEntityRenderer<LlmDogEntity, LlmDogEntityModel> {
    private static final Identifier TEXTURE = Identifier.of(LlmDogMod.MOD_ID, "textures/entity/llm_dog/llm_dog.png");

    public LlmDogRenderer(EntityRendererFactory.Context context) {
        super(context, new LlmDogEntityModel(context.getPart(LlmDogEntityModel.LAYER)), 0.5F);
    }

    @Override
    public Identifier getTexture(LlmDogEntity entity) {
        return TEXTURE;
    }
}
