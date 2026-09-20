package com.wjz.worldsmith.ability;

import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Test-only simulation of an old physics callback arriving after its projectile lease ended. */
public final class AbilityProjectileLeaseChecks {
    private AbilityProjectileLeaseChecks() {}
    public static void deliverRetiredImpact(ServerLevel level, UUID invocation, UUID projectile, Vec3 position) {
        WorldAbilityRuntime.projectileHit(level, invocation, projectile, null, position, "deadline");
    }
}
