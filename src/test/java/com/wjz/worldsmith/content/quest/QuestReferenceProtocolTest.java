package com.wjz.worldsmith.content.quest;

import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QuestReferenceProtocolTest {
    @Test void identicalLabelsRetainDifferentMechanicReferencesAcrossTheWire() {
        var snapshot = new QuestProtocol.Snapshot("a".repeat(64), "World", 3, 8,
            List.of(new QuestProtocol.Entry("awaken", "Awaken", "Find the broken altar", QuestProtocol.Status.ACTIVE,
                List.of(new QuestProtocol.Objective("activate_mechanic", "first_altar", "Ancient Altar", 0, 1),
                    new QuestProtocol.Objective("activate_mechanic", "second_altar", "Ancient Altar", 0, 1)), List.of())),
            QuestProtocol.Feedback.NONE, "");
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            QuestProtocol.Snapshot.STREAM_CODEC.encode(buffer, snapshot);
            var decoded = QuestProtocol.Snapshot.STREAM_CODEC.decode(buffer);
            assertEquals(snapshot, decoded); assertEquals(0, buffer.readableBytes());
            assertNotEquals(decoded.quests().getFirst().objectives().getFirst().reference(), decoded.quests().getFirst().objectives().get(1).reference());
        } finally { buffer.release(); }
    }

    @Test void labelsAreNotAcceptedAsMissingOrUnsafeMechanicIds() {
        assertThrows(IllegalArgumentException.class, () -> new QuestProtocol.Objective("activate_mechanic", "", "Altar", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new QuestProtocol.Objective("activate_mechanic", "../altar", "Altar", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new QuestProtocol.Objective("deliver_item", "x".repeat(257), "Token", 0, 1));
        assertEquals("worldsmith:item/key", new QuestProtocol.Objective("deliver_item", "worldsmith:item/key", "Key", 0, 1).reference());
    }
}
