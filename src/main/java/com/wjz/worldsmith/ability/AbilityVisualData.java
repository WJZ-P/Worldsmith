package com.wjz.worldsmith.ability;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.Vec3;

/** Small immutable render description; it contains no source code or arbitrary entity data. */
public record AbilityVisualData(Kind kind, String scope, long expiresAt, String texture, Vec3 castOrigin, Vec3 velocity,
                               List<Vec3> points, int count, float size, int rgb) {
    public enum Kind { NONE, PARTICLES, PATH }
    public static final AbilityVisualData EMPTY = new AbilityVisualData(Kind.NONE, "", 0, "", Vec3.ZERO, Vec3.ZERO, List.of(), 0, 1, 0xFFFFFF);
    public static final StreamCodec<RegistryFriendlyByteBuf, AbilityVisualData> CODEC = StreamCodec.of(AbilityVisualData::write, AbilityVisualData::read);
    public AbilityVisualData {
        java.util.Objects.requireNonNull(kind); java.util.Objects.requireNonNull(velocity); java.util.Objects.requireNonNull(castOrigin);
        points = List.copyOf(points);
        if (scope == null || kind != Kind.NONE && !scope.matches("[0-9a-f]{64}") || expiresAt < 0
            || !Float.isFinite(size) || size < .01F || size > 4 || rgb < 0 || rgb > 0xFFFFFF || count < 0 || count > 64
            || points.size() > 64 || !finite(velocity) || velocity.lengthSqr() > 4 || !finite(castOrigin)
            || Math.max(Math.abs(castOrigin.x), Math.max(Math.abs(castOrigin.y), Math.abs(castOrigin.z))) > 1e9)
            throw new IllegalArgumentException("Invalid bounded ability visual");
        if (texture == null || kind == Kind.PARTICLES && (!texture.matches("[0-9a-f]{64}") || count < 1))
            throw new IllegalArgumentException("Particle visuals need a content-addressed texture and 1..64 particles");
        if (kind == Kind.PATH && points.size() < 2) throw new IllegalArgumentException("Path visuals need 2..64 points");
        for (var point : points) if (!finite(point) || point.lengthSqr() > 96 * 96) throw new IllegalArgumentException("Visual path offset exceeds bounds");
    }
    public double cullingRadius() { return Math.min(196, points.stream().mapToDouble(Vec3::length).max().orElse(1) * 4 + size * 4); }
    private static boolean finite(Vec3 value) { return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z); }
    private static void write(RegistryFriendlyByteBuf out, AbilityVisualData value) {
        out.writeVarInt(value.kind.ordinal()); out.writeUtf(value.scope, 64); out.writeVarLong(value.expiresAt); out.writeUtf(value.texture, 64);
        vector(out, value.castOrigin); vector(out, value.velocity); out.writeVarInt(value.points.size()); value.points.forEach(point -> vector(out, point));
        out.writeVarInt(value.count); out.writeFloat(value.size); out.writeInt(value.rgb);
    }
    private static AbilityVisualData read(RegistryFriendlyByteBuf in) {
        int kind = in.readVarInt(); if (kind < 0 || kind >= Kind.values().length) throw new IllegalArgumentException("Unknown visual primitive");
        String scope = in.readUtf(64); long expiry = in.readVarLong(); String texture = in.readUtf(64); Vec3 origin = vector(in), velocity = vector(in);
        int count = in.readVarInt(); if (count < 0 || count > 64) throw new IllegalArgumentException("Visual point budget exceeded");
        var points = new ArrayList<Vec3>(count); for (int i = 0; i < count; i++) points.add(vector(in));
        return new AbilityVisualData(Kind.values()[kind], scope, expiry, texture, origin, velocity, points, in.readVarInt(), in.readFloat(), in.readInt());
    }
    private static void vector(RegistryFriendlyByteBuf out, Vec3 value) { out.writeDouble(value.x); out.writeDouble(value.y); out.writeDouble(value.z); }
    private static Vec3 vector(RegistryFriendlyByteBuf in) { return new Vec3(in.readDouble(), in.readDouble(), in.readDouble()); }
}
