package com.wjz.worldsmith.core.content;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One bounded procedural pose evaluator shared by the native renderer and offline authoring preview. */
public final class CreaturePose {
    public static final List<String> POSES = List.of("idle", "walk", "fly", "windup", "strike", "recovery");
    private static final float DEG = (float)(Math.PI / 180.0);
    /** How far a resting wing sweeps back from spread, in radians. */
    static float FOLD = 1.35F;
    private CreaturePose() {}

    /** {@code airborne} is flight: a flier off the ground beats its wings and tucks its legs. */
    public record Frame(float ageInTicks, float walkPosition, float walkSpeed, float headYaw, float headPitch, long appearanceSeed, int action,
                        int bossPhase, float poseIntensity, boolean airborne) {
        public Frame(float ageInTicks, float walkPosition, float walkSpeed, float headYaw, float headPitch, long appearanceSeed, int action) {
            this(ageInTicks,walkPosition,walkSpeed,headYaw,headPitch,appearanceSeed,action,false);
        }
        public Frame(float ageInTicks, float walkPosition, float walkSpeed, float headYaw, float headPitch, long appearanceSeed, int action, boolean airborne) {
            this(ageInTicks,walkPosition,walkSpeed,headYaw,headPitch,appearanceSeed,action,0,1.0F,airborne);
        }
        public Frame {
            if (bossPhase < 0 || bossPhase > 2 || !Float.isFinite(poseIntensity) || poseIntensity < .5F || poseIntensity > 2F)
                throw new IllegalArgumentException("Invalid bounded boss pose state");
        }
    }
    public record Rotation(float x, float y, float z) {}

    public static Frame preview(String pose, float tick, float headYaw, float headPitch, long seed) {
        if (!POSES.contains(pose)) throw new IllegalArgumentException("Unknown creature pose: " + pose);
        int action = switch (pose) {
            case "windup" -> CreatureCombatState.WINDUP.ordinal();
            case "strike" -> CreatureCombatState.STRIKE.ordinal();
            case "recovery" -> CreatureCombatState.RECOVERY.ordinal();
            default -> CreatureCombatState.IDLE.ordinal();
        };
        return new Frame(tick, pose.equals("walk") ? tick : 0, pose.equals("walk") ? .75F : 0, headYaw, headPitch, seed, action, pose.equals("fly"));
    }

    /** Preview chooses a phase explicitly; gameplay passes the index synchronized by the server. */
    public static Frame withBossPhase(Frame frame, CreatureDefinition definition, int phase) {
        if (definition == null || definition.getBoss() == null) {
            if (phase != 0) throw new IllegalArgumentException("A non-boss has no later phase");
            return frame;
        }
        if (definition.getBoss().getPhases().isEmpty()) {
            if (phase != 0) throw new IllegalArgumentException("A program-driven boss has no indexed stat phases");
            return new Frame(frame.ageInTicks,frame.walkPosition,frame.walkSpeed,frame.headYaw,frame.headPitch,frame.appearanceSeed,frame.action,0,1.0F,frame.airborne);
        }
        if (phase < 0 || phase >= definition.getBoss().getPhases().size()) throw new IllegalArgumentException("Unknown boss phase");
        return new Frame(frame.ageInTicks,frame.walkPosition,frame.walkSpeed,frame.headYaw,frame.headPitch,frame.appearanceSeed,frame.action,
            phase,definition.getBoss().getPhases().get(phase).getPoseIntensity(),frame.airborne);
    }

    /**
     * How far down a run of same-role bones each one sits: 0 for a tail's root,
     * 1 for the segment it carries, and so on. A segment's motion lags its parent's
     * by this much, so a jointed tail travels as a wave instead of swinging stiff.
     */
    public static Map<String, Integer> chains(List<CreatureBone> bones) {
        Map<String, CreatureBone> byId = new HashMap<>();
        for (var bone : bones) byId.put(bone.getId(), bone);
        Map<String, Integer> chains = new HashMap<>();
        for (var bone : bones) {
            int depth = 0;
            for (var parent = byId.get(bone.getParent()); parent != null && parent.getRole() == bone.getRole() && depth < 64; parent = byId.get(parent.getParent())) depth++;
            chains.put(bone.getId(), depth);
        }
        return Map.copyOf(chains);
    }

    /** Absolute bone-local Euler angles in radians, composed as Rz * Ry * Rx by both renderers. */
    public static Rotation rotation(CreatureBone bone, Frame frame) { return rotation(bone, frame, 0); }

    public static Rotation rotation(CreatureBone bone, Frame frame, int chain) {
        float x = bone.getRotation().getX() * DEG, y = bone.getRotation().getY() * DEG, z = bone.getRotation().getZ() * DEG;
        float phase = frame.walkPosition * .6662F + bone.getGaitPhase() * DEG;
        float amplitude = Math.min(frame.walkSpeed, 1.0F) * 1.2F * frame.poseIntensity;
        float seedPhase = (frame.appearanceSeed & 1023) * .006135923F;
        float lag = chain * .55F;
        switch (bone.getRole()) {
            case HEAD -> {
                y += clamp(frame.headYaw, -65, 65) * DEG;
                x += clamp(frame.headPitch, -40, 40) * DEG;
                if (frame.action == CreatureCombatState.WINDUP.ordinal()) x -= .25F * frame.poseIntensity;
                if (frame.action == CreatureCombatState.STRIKE.ordinal()) x += .55F * frame.poseIntensity;
                x += .06F * frame.bossPhase;
            }
            case LEG_LEFT, LEG_RIGHT -> {
                if (frame.airborne) x += .7F;
                else x += (float)Math.cos(phase + (bone.getRole() == CreatureBoneRole.LEG_RIGHT ? (float)Math.PI : 0)) * amplitude;
            }
            case ARM_LEFT, ARM_RIGHT -> {
                float offset = bone.getRole() == CreatureBoneRole.ARM_LEFT ? (float)Math.PI : 0;
                x += (float)Math.cos(phase + offset) * amplitude * .6F;
                if (frame.action == CreatureCombatState.WINDUP.ordinal()) x -= 1.3F * frame.poseIntensity;
                if (frame.action == CreatureCombatState.STRIKE.ordinal()) x -= .4F * frame.poseIntensity;
                if (frame.action == CreatureCombatState.RECOVERY.ordinal()) x -= .2F * frame.poseIntensity;
                z += (bone.getRole() == CreatureBoneRole.ARM_LEFT ? 1 : -1) * .09F * frame.bossPhase;
            }
            case TAIL -> y += (float)Math.sin(frame.ageInTicks * .08F + seedPhase - lag) * .16F * frame.poseIntensity
                + (float)Math.cos(phase - lag) * amplitude * .15F;
            case WING_LEFT, WING_RIGHT -> {
                // A wing is drawn spread. In flight it beats about the shoulder; on the
                // ground it sweeps back to lie along the body, lifting with each breath.
                float side = bone.getRole() == CreatureBoneRole.WING_LEFT ? -1 : 1;
                if (frame.airborne) z += side * (float)Math.sin(frame.ageInTicks * 1.1F + seedPhase) * .9F * frame.poseIntensity;
                else {
                    y -= side * FOLD;
                    z += side * (float)Math.sin(frame.ageInTicks * .08F + seedPhase) * .05F;
                }
            }
            case BODY -> {
                x += (float)Math.sin(frame.ageInTicks * .1F + seedPhase) * .025F;
                z += (float)Math.sin(phase) * amplitude * .05F;
                if (frame.airborne) x += .12F;
                if (frame.action == CreatureCombatState.WINDUP.ordinal()) x -= .12F * frame.poseIntensity;
                if (frame.action == CreatureCombatState.STRIKE.ordinal()) x += .18F * frame.poseIntensity;
            }
            case JAW -> {
                if (frame.action == CreatureCombatState.WINDUP.ordinal()) x += .3F * frame.poseIntensity;
                if (frame.action == CreatureCombatState.STRIKE.ordinal()) x += .6F * frame.poseIntensity;
                if (frame.action == CreatureCombatState.RECOVERY.ordinal()) x += .15F * frame.poseIntensity;
            }
            case NONE -> { }
        }
        return new Rotation(x, y, z);
    }

    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
}
