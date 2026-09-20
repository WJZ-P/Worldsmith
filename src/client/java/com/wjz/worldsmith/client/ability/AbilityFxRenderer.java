package com.wjz.worldsmith.client.ability;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wjz.worldsmith.ability.AbilityFxEntity;
import com.wjz.worldsmith.ability.AbilityVisualData;
import com.wjz.worldsmith.client.content.WorldContentClientRuntime;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.DisplayEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;

/** Arbitrary bounded paths use native custom-geometry submission, not a fixed attack renderer. */
public final class AbilityFxRenderer extends DisplayRenderer<AbilityFxEntity, AbilityVisualData, AbilityFxRenderer.State> {
    public AbilityFxRenderer(EntityRendererProvider.Context context) { super(context); }
    public static final class State extends DisplayEntityRenderState {
        AbilityVisualData visual = AbilityVisualData.EMPTY;
        @Override public boolean hasSubState() { return visual.kind() == AbilityVisualData.Kind.PATH; }
    }
    @Override public State createRenderState() { return new State(); }
    @Override public void extractRenderState(AbilityFxEntity entity, State state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        var data = entity.visual();
        state.visual = data.scope().equals(WorldContentClientRuntime.activeScope()) && data.expiresAt() > entity.level().getGameTime()
            ? data : AbilityVisualData.EMPTY;
    }
    @Override protected void submitInner(State state, PoseStack poseStack, SubmitNodeCollector collector, int light, float progress) {
        var points = state.visual.points(); float half = state.visual.size() * .5F; int rgb = state.visual.rgb();
        collector.submitCustomGeometry(poseStack, RenderTypes.lightning(), (pose, vertices) -> {
            for (int i = 1; i < points.size(); i++) {
                Vec3 a = points.get(i - 1), b = points.get(i), axis = b.subtract(a);
                if (axis.lengthSqr() < 1e-10) continue;
                axis = axis.normalize();
                Vec3 side = axis.cross(Math.abs(axis.y) < .9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0)).normalize().scale(half);
                Vec3 up = axis.cross(side).normalize().scale(half);
                Vec3[] offsets = {side.add(up), side.subtract(up), side.scale(-1).subtract(up), up.subtract(side)};
                for (int face = 0; face < 4; face++) {
                    Vec3 first = offsets[face], second = offsets[(face + 1) % 4];
                    vertex(vertices, pose, a.add(first), rgb); vertex(vertices, pose, b.add(first), rgb);
                    vertex(vertices, pose, b.add(second), rgb); vertex(vertices, pose, a.add(second), rgb);
                }
            }
        });
    }
    private static void vertex(VertexConsumer out, PoseStack.Pose pose, Vec3 value, int rgb) {
        out.addVertex(pose.pose(), (float)value.x, (float)value.y, (float)value.z)
            .setColor(((rgb >> 16) & 255) / 255F, ((rgb >> 8) & 255) / 255F, (rgb & 255) / 255F, .85F);
    }
}
