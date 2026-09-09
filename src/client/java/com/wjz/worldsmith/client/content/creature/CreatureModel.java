package com.wjz.worldsmith.client.content.creature;

import com.wjz.worldsmith.core.content.CreatureBone;
import com.wjz.worldsmith.core.content.CreatureCombatState;
import java.util.*;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.util.Mth;

/** Native arbitrary cuboid skeleton, box UVs and role-based procedural animation (not a vanilla reskin). */
public final class CreatureModel extends EntityModel<CreatureRenderState> {
    private final List<BonePart> animated;
    private record BonePart(CreatureBone bone, ModelPart part) {}

    private CreatureModel(ModelPart root, List<CreatureBone> bones) {
        super(root);
        var parts = new HashMap<String, ModelPart>();
        var pending = new ArrayList<>(bones);
        while (!pending.isEmpty()) {
            int before = pending.size();
            for (var iterator = pending.iterator(); iterator.hasNext();) {
                var b = iterator.next();
                if (b.getParent() != null && !parts.containsKey(b.getParent())) continue;
                var parent = b.getParent() == null ? root : parts.get(b.getParent());
                parts.put(b.getId(), parent.getChild(b.getId())); iterator.remove();
            }
            if (before == pending.size()) throw new IllegalArgumentException("Invalid creature bone hierarchy");
        }
        animated = bones.stream().map(b -> new BonePart(b, parts.get(b.getId()))).toList();
    }

    public static CreatureModel bake(com.wjz.worldsmith.core.content.CreatureModel definition) {
        MeshDefinition mesh = new MeshDefinition();
        var parts = new HashMap<String, PartDefinition>();
        var pending = new ArrayList<>(definition.getBones());
        while (!pending.isEmpty()) {
            int before = pending.size();
            for (var iterator = pending.iterator(); iterator.hasNext();) {
                var bone = iterator.next();
                if (bone.getParent() != null && !parts.containsKey(bone.getParent())) continue;
                var parent = bone.getParent() == null ? mesh.getRoot() : parts.get(bone.getParent());
                CubeListBuilder boxes = CubeListBuilder.create();
                for (var cube : bone.getCubes()) {
                    var o = cube.getOrigin(); var s = cube.getSize();
                    boxes.texOffs(cube.getUv().getU(), cube.getUv().getV()).mirror(cube.getMirror()).addBox(o.getX(), o.getY(), o.getZ(), s.getX(), s.getY(), s.getZ());
                }
                var p = bone.getPivot(); var r = bone.getRotation();
                parts.put(bone.getId(), parent.addOrReplaceChild(bone.getId(), boxes,
                    PartPose.offsetAndRotation(p.getX(), p.getY(), p.getZ(), r.getX() * Mth.DEG_TO_RAD, r.getY() * Mth.DEG_TO_RAD, r.getZ() * Mth.DEG_TO_RAD)));
                iterator.remove();
            }
            if (before == pending.size()) throw new IllegalArgumentException("Invalid creature bone hierarchy");
        }
        return new CreatureModel(LayerDefinition.create(mesh, definition.getTextureWidth(), definition.getTextureHeight()).bakeRoot(), definition.getBones());
    }

    /** Deliberately unmistakable missing-asset marker; no fallback to an unrelated vanilla species. */
    public static CreatureModel missing() {
        MeshDefinition mesh = new MeshDefinition();
        mesh.getRoot().addOrReplaceChild("missing", CubeListBuilder.create().texOffs(0, 0).addBox(-6, -12, -6, 12, 12, 12), PartPose.offset(0, 24, 0));
        return new CreatureModel(LayerDefinition.create(mesh, 64, 64).bakeRoot(), List.of());
    }

    @Override public void setupAnim(CreatureRenderState state) {
        super.setupAnim(state);
        float idlePhase = (state.appearanceSeed & 1023) * .006135923F;
        for (var entry : animated) {
            var b = entry.bone(); var p = entry.part();
            float phase = state.walkAnimationPos * .6662F + b.getGaitPhase() * Mth.DEG_TO_RAD;
            float amplitude = Math.min(state.walkAnimationSpeed, 1.0F) * 1.2F;
            switch (b.getRole()) {
                case HEAD -> {
                    p.yRot += Mth.clamp(state.yRot, -65, 65) * Mth.DEG_TO_RAD;
                    p.xRot += Mth.clamp(state.xRot, -40, 40) * Mth.DEG_TO_RAD;
                    if (state.action == CreatureCombatState.WINDUP.ordinal()) p.xRot -= .25F;
                    if (state.action == CreatureCombatState.STRIKE.ordinal()) p.xRot += .55F;
                }
                case LEG_LEFT -> p.xRot += Mth.cos(phase) * amplitude;
                case LEG_RIGHT -> p.xRot += Mth.cos(phase + Mth.PI) * amplitude;
                case ARM_LEFT, ARM_RIGHT -> {
                    float offset = b.getRole() == com.wjz.worldsmith.core.content.CreatureBoneRole.ARM_LEFT ? Mth.PI : 0;
                    p.xRot += Mth.cos(phase + offset) * amplitude * .6F;
                    if (state.action == CreatureCombatState.WINDUP.ordinal()) p.xRot -= 1.3F;
                    if (state.action == CreatureCombatState.STRIKE.ordinal()) p.xRot -= .4F;
                    if (state.action == CreatureCombatState.RECOVERY.ordinal()) p.xRot -= .2F;
                }
                case TAIL -> p.yRot += Mth.sin(state.ageInTicks * .08F + idlePhase) * .16F + Mth.cos(phase) * amplitude * .15F;
                case NONE -> { }
            }
        }
    }
}
