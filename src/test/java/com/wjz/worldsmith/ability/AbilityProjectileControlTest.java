package com.wjz.worldsmith.ability;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class AbilityProjectileControlTest {
    @Test void steeringHonorsAngleAndRequestedMagnitude() {
        var result = AbilityProjectileControl.steerVelocity(new Vec3(0, 0, 1), new Vec3(2, 0, 0), Math.PI / 4);
        assertEquals(2, result.length(), 1e-9);
        assertEquals(Math.PI / 4, Math.acos(result.normalize().dot(new Vec3(0, 0, 1))), 1e-9);
        assertTrue(result.x > 0 && result.z > 0);
    }
    @Test void oppositeDirectionsHaveADeterministicFiniteTurnPlane() {
        var first = AbilityProjectileControl.steerVelocity(new Vec3(1, 0, 0), new Vec3(-1, 0, 0), .2);
        assertEquals(first, AbilityProjectileControl.steerVelocity(new Vec3(1, 0, 0), new Vec3(-1, 0, 0), .2));
        assertEquals(1, first.length(), 1e-9); assertEquals(Math.cos(.2), first.x, 1e-9);
    }
    @Test void stationaryProjectileCanChooseDirectionAndInvalidInputsFail() {
        assertEquals(new Vec3(0, 1, 0), AbilityProjectileControl.steerVelocity(Vec3.ZERO, new Vec3(0, 1, 0), .1));
        assertThrows(IllegalArgumentException.class, () -> AbilityProjectileControl.steerVelocity(Vec3.ZERO, Vec3.ZERO, .1));
        assertThrows(IllegalArgumentException.class, () -> AbilityProjectileControl.steerVelocity(Vec3.ZERO, new Vec3(3, 0, 0), .1));
        assertThrows(IllegalArgumentException.class, () -> AbilityProjectileControl.steerVelocity(Vec3.ZERO, new Vec3(1, 0, 0), Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> AbilityProjectileControl.steerVelocity(Vec3.ZERO, new Vec3(1, 0, 0), 4));
    }
}
