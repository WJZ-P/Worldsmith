package com.wjz.worldsmith.ability;

import com.wjz.worldsmith.core.ability.AbilityValue;
import com.wjz.worldsmith.core.ability.AbilityValues;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Instantaneous control of this invocation's live projectile handles, not a homing-skill preset. */
public final class AbilityProjectileControl {
    private AbilityProjectileControl() {}
    public static AbilityValue velocity(WorldAbilityRuntime.Context context, List<AbilityValue> args) {
        var projectile = owned(context, args.get(0)); if (projectile == null) return AbilityValues.bool(false);
        Vec3 velocity = vector(args.get(1)); bounded(velocity);
        projectile.setDeltaMovement(velocity); projectile.hurtMarked = true;
        return AbilityValues.bool(true);
    }
    public static AbilityValue steer(WorldAbilityRuntime.Context context, List<AbilityValue> args) {
        var projectile = owned(context, args.get(0)); if (projectile == null) return AbilityValues.bool(false);
        Vec3 desired = vector(args.get(1)); bounded(desired);
        if (!(args.get(2) instanceof AbilityValue.NumberValue turn)) throw new IllegalArgumentException("Steering turn must be a number of radians");
        projectile.setDeltaMovement(steerVelocity(projectile.getDeltaMovement(), desired, turn.getValue())); projectile.hurtMarked = true;
        return AbilityValues.bool(true);
    }
    public static AbilityValue retire(WorldAbilityRuntime.Context context, List<AbilityValue> args) {
        var projectile = owned(context, args.getFirst());
        return AbilityValues.bool(projectile != null && context.retireEntity(projectile));
    }
    private static AbilityProjectile owned(WorldAbilityRuntime.Context context, AbilityValue value) {
        var entity = context.resolve(value);
        return entity instanceof AbilityProjectile projectile && context.ownsEntity(projectile)
            && context.invocation().equals(projectile.invocation()) ? projectile : null;
    }
    /** Rotate toward a desired velocity by a bounded angle; desired magnitude is applied without hidden tracking. */
    public static Vec3 steerVelocity(Vec3 current, Vec3 desired, double maxTurnRadians) {
        finite(current); bounded(desired);
        if (!Double.isFinite(maxTurnRadians) || maxTurnRadians < 0 || maxTurnRadians > Math.PI)
            throw new IllegalArgumentException("Projectile turn is 0..PI radians per call");
        double speed = desired.length();
        if (speed < 1e-9) throw new IllegalArgumentException("Steering needs a nonzero desired direction; use projectile.velocity to stop");
        if (current.lengthSqr() < 1e-12) return desired;
        Vec3 from = current.normalize(), to = desired.scale(1 / speed);
        double dot = Math.max(-1, Math.min(1, from.dot(to))), angle = Math.acos(dot);
        if (angle <= maxTurnRadians + 1e-9) return desired;
        var tangent = to.subtract(from.scale(dot));
        if (tangent.lengthSqr() < 1e-12) {
            // Opposite directions have no unique turn plane; choose one deterministically.
            Vec3 axis = Math.abs(from.x) < .8 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
            tangent = from.cross(axis);
        }
        return from.scale(Math.cos(maxTurnRadians)).add(tangent.normalize().scale(Math.sin(maxTurnRadians))).normalize().scale(speed);
    }
    private static Vec3 vector(AbilityValue value) {
        if (!(value instanceof AbilityValue.VectorValue vector)) throw new IllegalArgumentException("Projectile velocity must be a vector");
        return new Vec3(vector.getX(), vector.getY(), vector.getZ());
    }
    private static void bounded(Vec3 value) {
        finite(value);
        if (value.lengthSqr() > 4.000000001)
            throw new IllegalArgumentException("Projectile velocity must be finite and at most two blocks per tick");
    }
    private static void finite(Vec3 value) {
        if (value == null || !Double.isFinite(value.x) || !Double.isFinite(value.y) || !Double.isFinite(value.z) || !Double.isFinite(value.lengthSqr()))
            throw new IllegalArgumentException("Projectile velocity must be finite");
    }
}
