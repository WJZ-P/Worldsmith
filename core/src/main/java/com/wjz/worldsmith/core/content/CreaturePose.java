package com.wjz.worldsmith.core.content;

import java.util.List;

/** One bounded procedural pose evaluator shared by the native renderer and offline authoring preview. */
public final class CreaturePose {
    public static final List<String> POSES = List.of("idle", "walk", "windup", "strike", "recovery");
    private static final float DEG = (float)(Math.PI / 180.0);
    private CreaturePose() {}

    public record Frame(float ageInTicks, float walkPosition, float walkSpeed, float headYaw, float headPitch, long appearanceSeed, int action) {}
    public record Rotation(float x, float y, float z) {}

    public static Frame preview(String pose, float tick, float headYaw, float headPitch, long seed) {
        if (!POSES.contains(pose)) throw new IllegalArgumentException("Unknown creature pose: " + pose);
        int action = switch (pose) {
            case "windup" -> CreatureCombatState.WINDUP.ordinal();
            case "strike" -> CreatureCombatState.STRIKE.ordinal();
            case "recovery" -> CreatureCombatState.RECOVERY.ordinal();
            default -> CreatureCombatState.IDLE.ordinal();
        };
        return new Frame(tick, pose.equals("walk") ? tick : 0, pose.equals("walk") ? .75F : 0, headYaw, headPitch, seed, action);
    }

    /** Absolute bone-local Euler angles in radians, composed as Rz * Ry * Rx by both renderers. */
    public static Rotation rotation(CreatureBone bone, Frame frame) {
        float x = bone.getRotation().getX() * DEG, y = bone.getRotation().getY() * DEG, z = bone.getRotation().getZ() * DEG;
        float phase = frame.walkPosition * .6662F + bone.getGaitPhase() * DEG;
        float amplitude = Math.min(frame.walkSpeed, 1.0F) * 1.2F;
        switch (bone.getRole()) {
            case HEAD -> {
                y += clamp(frame.headYaw, -65, 65) * DEG;
                x += clamp(frame.headPitch, -40, 40) * DEG;
                if (frame.action == CreatureCombatState.WINDUP.ordinal()) x -= .25F;
                if (frame.action == CreatureCombatState.STRIKE.ordinal()) x += .55F;
            }
            case LEG_LEFT -> x += (float)Math.cos(phase) * amplitude;
            case LEG_RIGHT -> x += (float)Math.cos(phase + (float)Math.PI) * amplitude;
            case ARM_LEFT, ARM_RIGHT -> {
                float offset = bone.getRole() == CreatureBoneRole.ARM_LEFT ? (float)Math.PI : 0;
                x += (float)Math.cos(phase + offset) * amplitude * .6F;
                if (frame.action == CreatureCombatState.WINDUP.ordinal()) x -= 1.3F;
                if (frame.action == CreatureCombatState.STRIKE.ordinal()) x -= .4F;
                if (frame.action == CreatureCombatState.RECOVERY.ordinal()) x -= .2F;
            }
            case TAIL -> y += (float)Math.sin(frame.ageInTicks * .08F + (frame.appearanceSeed & 1023) * .006135923F) * .16F
                + (float)Math.cos(phase) * amplitude * .15F;
            case NONE -> { }
        }
        return new Rotation(x, y, z);
    }

    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
}
