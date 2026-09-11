package com.wjz.worldsmith.client.content.creature;

import com.wjz.worldsmith.core.content.CreatureBone;
import com.wjz.worldsmith.core.content.CreaturePose;
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
        int phase=state.definition==null || state.definition.getBoss()==null ? 0
            : Math.max(0,Math.min(state.bossPhase,state.definition.getBoss().getPhases().size()-1));
        var frame = CreaturePose.withBossPhase(new CreaturePose.Frame(state.ageInTicks, state.walkAnimationPos, state.walkAnimationSpeed,
            state.yRot, state.xRot, state.appearanceSeed, state.action),state.definition,phase);
        for (var entry : animated) {
            var rotation = CreaturePose.rotation(entry.bone(), frame);
            var part = entry.part(); part.xRot = rotation.x(); part.yRot = rotation.y(); part.zRot = rotation.z();
        }
    }
}
