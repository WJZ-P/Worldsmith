package com.wjz.worldsmith.content.interaction;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.content.quest.server.QuestRuntime;
import com.wjz.worldsmith.core.content.WorldMechanicValidation;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/** Manual, bounded and read-only examination. Static instructions never need this request or a built device. */
public final class MechanicGuideProtocol {
    private static boolean typesRegistered;
    private static boolean serverRegistered;
    private static final Map<ServerPlayer, Integer> LAST_QUERY = Collections.synchronizedMap(new WeakHashMap<>());
    private MechanicGuideProtocol() {}

    public record Query(String scope, String dimension, String mechanicId, BlockPos anchor, int requestId) implements CustomPacketPayload {
        public static final Type<Query> TYPE = new Type<>(Identifier.fromNamespaceAndPath("worldsmith", "mechanic_inspect"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Query> STREAM_CODEC = StreamCodec.of(MechanicGuideProtocol::writeQuery, MechanicGuideProtocol::readQuery);
        public Query { validate(scope, dimension, mechanicId, anchor, requestId); anchor = anchor.immutable(); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Reply(String scope, String dimension, String mechanicId, BlockPos anchor, int requestId, MechanicInspection result) implements CustomPacketPayload {
        public static final Type<Reply> TYPE = new Type<>(Identifier.fromNamespaceAndPath("worldsmith", "mechanic_inspection"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Reply> STREAM_CODEC = StreamCodec.of(MechanicGuideProtocol::writeReply, MechanicGuideProtocol::readReply);
        public Reply { validate(scope, dimension, mechanicId, anchor, requestId); anchor = anchor.immutable(); Objects.requireNonNull(result); }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static synchronized void registerTypes() {
        if (typesRegistered) return;
        PayloadTypeRegistry.serverboundPlay().register(Query.TYPE, Query.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Reply.TYPE, Reply.STREAM_CODEC);
        typesRegistered = true;
    }

    public static synchronized void registerServer() {
        if (serverRegistered) return;
        registerTypes();
        if (!ServerPlayNetworking.registerGlobalReceiver(Query.TYPE, (query, context) -> {
            ServerPlayer player = context.player();
            int now = player.level().getServer().getTickCount();
            Integer last = LAST_QUERY.get(player);
            // A rejected rapid query still receives an answer, so a client need not replay an uncertain request.
            Reply reply;
            if (last != null && now >= last && now - last < 5) reply = unavailable(query);
            else { LAST_QUERY.put(player, now); reply = evaluate(player, query); }
            if (ServerPlayNetworking.canSend(player, Reply.TYPE)) ServerPlayNetworking.send(player, reply);
        })) throw new IllegalStateException("Mechanic inspection channel is already registered");
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> LAST_QUERY.remove(handler.player));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            synchronized (LAST_QUERY) { LAST_QUERY.keySet().removeIf(player -> player.level().getServer() == server); }
        });
        serverRegistered = true;
    }

    /** Shared policy used by the real packet handler and the native exploration regression. No mutations. */
    public static Reply evaluate(ServerPlayer player, Query query) {
        if (!player.level().getServer().isSameThread()) throw new IllegalStateException("Inspect mechanics on the owning server thread");
        var snapshot = WorldMechanicRuntime.snapshot(player.level());
        if (snapshot == null || !snapshot.scope().equals(query.scope) || !player.level().dimension().identifier().toString().equals(query.dimension)
            || !QuestRuntime.canReadMechanicGuide(player, query.mechanicId)) return unavailable(query);
        try {
            return new Reply(query.scope, query.dimension, query.mechanicId, query.anchor, query.requestId,
                WorldMechanicRuntime.inspect(player, query.mechanicId, query.anchor));
        } catch (RuntimeException failure) {
            Worldsmith.LOGGER.warn("Read-only mechanic inspection failed for {}", query.mechanicId, failure);
            return unavailable(query);
        }
    }

    private static Reply unavailable(Query query) {
        return new Reply(query.scope, query.dimension, query.mechanicId, query.anchor, query.requestId,
            new MechanicInspection(MechanicInspection.Code.UNAVAILABLE, "", 0, 0, 0, 0));
    }

    private static void validate(String scope, String dimension, String mechanic, BlockPos anchor, int request) {
        Identifier dimensionId = dimension == null ? null : Identifier.tryParse(dimension);
        if (scope == null || !scope.matches("[0-9a-f]{64}") || mechanic == null || !WorldMechanicValidation.validId(mechanic)
            || dimensionId == null || dimension.length() > 256 || !dimension.equals(dimensionId.toString())
            || anchor == null || request < 1 || Math.abs((long)anchor.getX()) > 30_000_000 || Math.abs((long)anchor.getZ()) > 30_000_000
            || anchor.getY() < -2048 || anchor.getY() > 2047) throw new IllegalArgumentException("Invalid bounded mechanic inspection request");
    }
    private static void writeQuery(RegistryFriendlyByteBuf buffer, Query value) {
        buffer.writeUtf(value.scope, 64); buffer.writeUtf(value.dimension, 256); buffer.writeUtf(value.mechanicId, 64); buffer.writeBlockPos(value.anchor); buffer.writeVarInt(value.requestId);
    }
    private static Query readQuery(RegistryFriendlyByteBuf buffer) {
        return new Query(buffer.readUtf(64), buffer.readUtf(256), buffer.readUtf(64), buffer.readBlockPos(), buffer.readVarInt());
    }
    private static void writeReply(RegistryFriendlyByteBuf buffer, Reply value) {
        writeQuery(buffer, new Query(value.scope, value.dimension, value.mechanicId, value.anchor, value.requestId));
        var result = value.result;
        buffer.writeVarInt(result.code().ordinal()); buffer.writeUtf(result.ruleId(), 64);
        buffer.writeVarInt(result.missingCells()); buffer.writeVarInt(result.requiredItems()); buffer.writeVarInt(result.heldItems()); buffer.writeVarLong(result.cooldownTicks());
    }
    private static Reply readReply(RegistryFriendlyByteBuf buffer) {
        Query identity = readQuery(buffer);
        int ordinal = buffer.readVarInt(); var codes = MechanicInspection.Code.values();
        if (ordinal < 0 || ordinal >= codes.length) throw new IllegalArgumentException("Invalid mechanic inspection status");
        var result = new MechanicInspection(codes[ordinal], buffer.readUtf(64), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarLong());
        return new Reply(identity.scope, identity.dimension, identity.mechanicId, identity.anchor, identity.requestId, result);
    }
}
