package com.wjz.worldsmith.ability;

import com.wjz.worldsmith.core.ability.visual.AbilityClip;
import java.util.ArrayList;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server-to-client only, with model tracks rather than executable code. Null clip retires presentation. */
public final class AbilityAnimationProtocol {
    private static boolean registered;
    private AbilityAnimationProtocol() {}
    public record Snapshot(String scope, String dimension, int actorId, UUID actorUuid, long revision,
                           long startedAt, AbilityClip clip) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = new Type<>(Identifier.fromNamespaceAndPath("worldsmith", "ability_animation"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = StreamCodec.of(AbilityAnimationProtocol::write, AbilityAnimationProtocol::read);
        public Snapshot {
            if (scope == null || !scope.matches("[0-9a-f]{64}") || dimension == null || dimension.length() > 256
                || Identifier.tryParse(dimension) == null || actorId < 0 || actorUuid == null || revision < 1 || startedAt < 0)
                throw new IllegalArgumentException("Invalid scoped animation snapshot");
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public static synchronized void register() {
        if (registered) return;
        PayloadTypeRegistry.clientboundPlay().register(Snapshot.TYPE, Snapshot.CODEC); registered = true;
    }
    private static void write(RegistryFriendlyByteBuf out, Snapshot value) {
        out.writeUtf(value.scope, 64); out.writeUtf(value.dimension, 256); out.writeVarInt(value.actorId); out.writeUUID(value.actorUuid);
        out.writeVarLong(value.revision); out.writeVarLong(value.startedAt); out.writeBoolean(value.clip != null);
        if (value.clip == null) return;
        out.writeVarInt(value.clip.durationTicks()); out.writeVarInt(value.clip.blendTicks()); out.writeVarInt(value.clip.tracks().size());
        for (var track : value.clip.tracks()) {
            out.writeUtf(track.bone(), 96); out.writeVarInt(track.frames().size());
            for (var frame : track.frames()) {
                out.writeVarInt(frame.tick()); vector(out, frame.transform().translation()); vector(out, frame.transform().rotation()); vector(out, frame.transform().scale());
            }
        }
    }
    private static Snapshot read(RegistryFriendlyByteBuf in) {
        String scope = in.readUtf(64), dimension = in.readUtf(256); int actor = in.readVarInt(); UUID uuid = in.readUUID();
        long revision = in.readVarLong(), start = in.readVarLong(); AbilityClip clip = null;
        if (in.readBoolean()) {
            int duration = in.readVarInt(), blend = in.readVarInt(), tracks = count(in, AbilityClip.MAX_TRACKS);
            if (duration < 1 || duration > AbilityClip.MAX_DURATION || blend < 0 || blend > Math.min(40, duration / 2))
                throw new IllegalArgumentException("Invalid animation timing");
            var decoded = new ArrayList<AbilityClip.Track>(tracks); int totalFrames = 0;
            for (int i = 0; i < tracks; i++) {
                String bone = in.readUtf(96); int frames = count(in, 64);
                if ((totalFrames += frames) > AbilityClip.MAX_FRAMES) throw new IllegalArgumentException("Animation packet frame budget exceeded");
                var keyframes = new ArrayList<AbilityClip.Keyframe>(frames);
                for (int j = 0; j < frames; j++) keyframes.add(new AbilityClip.Keyframe(in.readVarInt(), new AbilityClip.Transform(vector(in), vector(in), vector(in))));
                decoded.add(new AbilityClip.Track(bone, keyframes));
            }
            clip = new AbilityClip(duration, blend, decoded);
        }
        return new Snapshot(scope, dimension, actor, uuid, revision, start, clip);
    }
    private static int count(RegistryFriendlyByteBuf in, int maximum) { int count = in.readVarInt(); if (count < 1 || count > maximum) throw new IllegalArgumentException("Animation packet list limit exceeded"); return count; }
    private static void vector(RegistryFriendlyByteBuf out, AbilityClip.Vector value) { out.writeFloat(value.x()); out.writeFloat(value.y()); out.writeFloat(value.z()); }
    private static AbilityClip.Vector vector(RegistryFriendlyByteBuf in) { return new AbilityClip.Vector(in.readFloat(), in.readFloat(), in.readFloat()); }
}
