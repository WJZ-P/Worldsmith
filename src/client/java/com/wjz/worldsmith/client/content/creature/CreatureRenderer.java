package com.wjz.worldsmith.client.content.creature;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wjz.worldsmith.content.creature.CreatureEntity;
import com.wjz.worldsmith.content.creature.CreatureRuntime;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;

/** Models are baked once per immutable definition, not generated or reparsed during entity ticks. */
public final class CreatureRenderer extends MobRenderer<CreatureEntity, CreatureRenderState, CreatureModel> {
    private final CreatureModel missing;
    private final Map<String, CreatureModel> models = new HashMap<>();
    private CreatureRuntime.Snapshot cachedSnapshot;

    public CreatureRenderer(EntityRendererProvider.Context context) {
        super(context, CreatureModel.missing(), .5F);
        missing = model;
    }

    public static void register() {
        EntityRenderers.register(CreatureRuntime.passiveType(), CreatureRenderer::new);
        EntityRenderers.register(CreatureRuntime.hostileType(), CreatureRenderer::new);
    }

    @Override public CreatureRenderState createRenderState() { return new CreatureRenderState(); }

    @Override public void extractRenderState(CreatureEntity entity, CreatureRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        state.definition = entity.definition(); state.bundleHash = entity.bundleHash();
        state.action = entity.action(); state.appearanceSeed = entity.appearanceSeed();
    }

    @Override public Identifier getTextureLocation(CreatureRenderState state) {
        return state.definition == null ? MissingTextureAtlasSprite.getLocation()
            : Identifier.fromNamespaceAndPath("worldsmith", "textures/content/" + state.definition.getModel().getTexture() + ".png");
    }

    @Override public void submit(CreatureRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        var snapshot = CreatureRuntime.clientSnapshot();
        if (cachedSnapshot != snapshot) { models.clear(); cachedSnapshot = snapshot; }
        model = state.definition == null ? missing : models.computeIfAbsent(state.bundleHash + "/" + state.definition.getId(), key -> CreatureModel.bake(state.definition.getModel()));
        super.submit(state, poseStack, collector, camera);
    }

    @Override protected float getShadowRadius(CreatureRenderState state) {
        return state.definition == null ? .5F : state.definition.getAttributes().getWidth() * .6F;
    }

    @Override protected AABB getBoundingBoxForCulling(CreatureEntity entity) {
        // Schema validation bounds accumulated pivot+cube radius at 256 model units (16 blocks).
        return super.getBoundingBoxForCulling(entity).inflate(16);
    }

    @Override protected boolean shouldShowName(CreatureEntity entity, double distanceSquared) {
        return entity.definition() == null || super.shouldShowName(entity, distanceSquared);
    }
}
