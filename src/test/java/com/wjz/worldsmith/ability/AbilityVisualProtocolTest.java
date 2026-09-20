package com.wjz.worldsmith.ability;

import com.wjz.worldsmith.core.ability.visual.AbilityClip;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class AbilityVisualProtocolTest {
    private static final String SCOPE = "a".repeat(64);
    private RegistryFriendlyByteBuf buffer() { return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY); }
    private AbilityClip clip() {
        var zero = new AbilityClip.Vector(0, 0, 0); var one = new AbilityClip.Vector(1, 1, 1);
        return new AbilityClip(40, 4, List.of(new AbilityClip.Track("arm", List.of(
            new AbilityClip.Keyframe(0, new AbilityClip.Transform(zero, zero, one)),
            new AbilityClip.Keyframe(40, new AbilityClip.Transform(new AbilityClip.Vector(2, 0, 0), new AbilityClip.Vector(-90, 0, 0), one))))));
    }
    @Test void visualDataRoundTripsAndOwnsItsPointList() {
        var points = new ArrayList<>(List.of(Vec3.ZERO, new Vec3(2, 1, 0)));
        var data = new AbilityVisualData(AbilityVisualData.Kind.PATH, SCOPE, 80, "", new Vec3(100, 64, 100), Vec3.ZERO, points, 0, .2F, 0xFFCC55);
        points.clear(); assertEquals(2, data.points().size());
        var buffer = buffer();
        try { AbilityVisualData.CODEC.encode(buffer, data); assertEquals(data, AbilityVisualData.CODEC.decode(buffer)); }
        finally { buffer.release(); }
        assertThrows(UnsupportedOperationException.class, () -> data.points().clear());
    }
    @Test void typedAnimationSnapshotsRoundTripStartAndRetirement() {
        var start = new AbilityAnimationProtocol.Snapshot(SCOPE, "minecraft:overworld", 7, new UUID(1, 2), 4, 20, clip());
        var stop = new AbilityAnimationProtocol.Snapshot(SCOPE, "minecraft:overworld", 7, new UUID(1, 2), 5, 30, null);
        var buffer = buffer();
        try {
            AbilityAnimationProtocol.Snapshot.CODEC.encode(buffer, start); AbilityAnimationProtocol.Snapshot.CODEC.encode(buffer, stop);
            assertEquals(start, AbilityAnimationProtocol.Snapshot.CODEC.decode(buffer));
            assertEquals(stop, AbilityAnimationProtocol.Snapshot.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }
    @Test void malformedAnimationCountsFailBeforeAllocatingTracks() {
        var buffer = buffer();
        try {
            buffer.writeUtf(SCOPE, 64); buffer.writeUtf("minecraft:overworld", 256); buffer.writeVarInt(1); buffer.writeUUID(new UUID(1, 2));
            buffer.writeVarLong(1); buffer.writeVarLong(1); buffer.writeBoolean(true);
            buffer.writeVarInt(40); buffer.writeVarInt(4); buffer.writeVarInt(65);
            assertThrows(IllegalArgumentException.class, () -> AbilityAnimationProtocol.Snapshot.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }
    @Test void visualAndAnimationBoundsRejectInvalidNumbersAndScopes() {
        assertThrows(IllegalArgumentException.class, () -> new AbilityVisualData(AbilityVisualData.Kind.PARTICLES, SCOPE, 30, "missing", Vec3.ZERO, Vec3.ZERO, List.of(), 2, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new AbilityVisualData(AbilityVisualData.Kind.PATH, SCOPE, 30, "", Vec3.ZERO, Vec3.ZERO, List.of(Vec3.ZERO), 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new AbilityVisualData(AbilityVisualData.Kind.PATH, SCOPE, 30, "", Vec3.ZERO, Vec3.ZERO, List.of(Vec3.ZERO, new Vec3(Double.NaN, 0, 0)), 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new AbilityAnimationProtocol.Snapshot("foreign", "minecraft:overworld", 1, new UUID(1, 2), 1, 0, clip()));
    }
}
