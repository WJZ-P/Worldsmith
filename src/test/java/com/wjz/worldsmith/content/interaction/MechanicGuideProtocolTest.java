package com.wjz.worldsmith.content.interaction;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MechanicGuideProtocolTest {
    @Test void inspectionRetainsRequestIdentityAndReasonWithoutSendingExecutableRuleData() {
        var query = new MechanicGuideProtocol.Query("a".repeat(64), "minecraft:overworld", "altar", new BlockPos(-12, 80, 19), 4);
        var reply = new MechanicGuideProtocol.Reply(query.scope(), query.dimension(), query.mechanicId(), query.anchor(), query.requestId(),
            new MechanicInspection(MechanicInspection.Code.INCOMPLETE_PATTERN, "assemble", 2, 0, 0, 0));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            MechanicGuideProtocol.Query.STREAM_CODEC.encode(buffer, query);
            assertEquals(query, MechanicGuideProtocol.Query.STREAM_CODEC.decode(buffer));
            buffer.clear();
            MechanicGuideProtocol.Reply.STREAM_CODEC.encode(buffer, reply);
            assertTrue(buffer.readableBytes() < 256);
            assertEquals(reply, MechanicGuideProtocol.Reply.STREAM_CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally { buffer.release(); }
    }

    @Test void mutableCoordinatesAreDetachedAndPacketIdentitiesAreStrictlyBounded() {
        var pos = new BlockPos.MutableBlockPos(1, 2, 3);
        var query = new MechanicGuideProtocol.Query("b".repeat(64), "minecraft:overworld", "altar", pos, 1);
        pos.set(4, 5, 6); assertEquals(new BlockPos(1, 2, 3), query.anchor());
        assertThrows(IllegalArgumentException.class, () -> new MechanicGuideProtocol.Query("", "minecraft:overworld", "altar", BlockPos.ZERO, 1));
        assertThrows(IllegalArgumentException.class, () -> new MechanicGuideProtocol.Query("b".repeat(64), "overworld", "altar", BlockPos.ZERO, 1));
        assertThrows(IllegalArgumentException.class, () -> new MechanicGuideProtocol.Query("b".repeat(64), "minecraft:overworld", "../altar", BlockPos.ZERO, 1));
        assertThrows(IllegalArgumentException.class, () -> new MechanicGuideProtocol.Query("b".repeat(64), "minecraft:overworld", "altar", BlockPos.ZERO, 0));
        assertThrows(IllegalArgumentException.class, () -> new MechanicGuideProtocol.Query("b".repeat(64), "minecraft:overworld", "altar", new BlockPos(30_000_001, 0, 0), 1));
        assertThrows(IllegalArgumentException.class, () -> new MechanicGuideProtocol.Query("b".repeat(64), "minecraft:overworld", "altar", new BlockPos(0, 2048, 0), 1));
    }

    @Test void unknownStatusOrdinalIsRejectedRatherThanBecomingReady() {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            MechanicGuideProtocol.Query.STREAM_CODEC.encode(buffer, new MechanicGuideProtocol.Query("c".repeat(64), "minecraft:overworld", "altar", BlockPos.ZERO, 1));
            buffer.writeVarInt(999);
            assertThrows(IllegalArgumentException.class, () -> MechanicGuideProtocol.Reply.STREAM_CODEC.decode(buffer));
        } finally { buffer.release(); }
    }
}
