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

    @Test void discoveredBranchMetadataAndAuthoritativeCompletionRoundTrip() {
        var entry = new QuestProtocol.Entry("promise", "A promise", "Known text", QuestProtocol.Status.AVAILABLE,
            List.of(new QuestProtocol.Objective("fact", "promise.0", "Return to the keeper", 0, 1, false)), List.of(), true, "village", true);
        var snapshot = new QuestProtocol.Snapshot("b".repeat(64), "World", 1, 7, List.of(entry), QuestProtocol.Feedback.ACCEPTED, "", false, "promise");
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            QuestProtocol.Snapshot.STREAM_CODEC.encode(buffer, snapshot);
            assertEquals(snapshot, QuestProtocol.Snapshot.STREAM_CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally { buffer.release(); }
        assertThrows(IllegalArgumentException.class, () -> new QuestProtocol.Snapshot("b".repeat(64), "World", 1, 7, List.of(entry), QuestProtocol.Feedback.NONE, "", false, "future"));
        assertFalse(new QuestProtocol.Snapshot("b".repeat(64), "World", 1, 7, List.of(), QuestProtocol.Feedback.NONE, "").campaignComplete());
    }

    @Test void intentPacketsCarryNoClientFactOrProgressAndUntrackHasNoQuestTarget() {
        for (var action : List.of(QuestProtocol.ActionKind.ACCEPT, QuestProtocol.ActionKind.DECLINE, QuestProtocol.ActionKind.TRACK)) {
            var value = new QuestProtocol.Action("c".repeat(64), "known", action, 4, 12);
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                QuestProtocol.Action.STREAM_CODEC.encode(buffer, value);
                assertEquals(value, QuestProtocol.Action.STREAM_CODEC.decode(buffer)); assertEquals(0, buffer.readableBytes());
            } finally { buffer.release(); }
        }
        assertThrows(IllegalArgumentException.class, () -> new QuestProtocol.Action("c".repeat(64), "known", QuestProtocol.ActionKind.UNTRACK, 1, 0));
        assertDoesNotThrow(() -> new QuestProtocol.Action("c".repeat(64), "", QuestProtocol.ActionKind.UNTRACK, 1, 0));
    }
}
