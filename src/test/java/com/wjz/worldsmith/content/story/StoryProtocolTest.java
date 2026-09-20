package com.wjz.worldsmith.content.story;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StoryProtocolTest {
    private static final String SCOPE = "a".repeat(64);
    private static final UUID TOKEN = UUID.fromString("10000000-0000-0000-0000-000000000001"), ACTOR = UUID.fromString("20000000-0000-0000-0000-000000000002");
    @Test void snapshotRoundTripPreservesTokensAndActualMarkerIdentity() {
        var dialogue = new StoryProtocol.Dialogue(TOKEN, ACTOR, "greeting", "灯塔守望者", "这条路通往故乡。\n先听听钟声。", List.of(new StoryProtocol.Choice("listen", "我想知道发生了什么。", "付出：铜锭 × 3\n获得：归灯铜钥 × 1")));
        var place = new StoryProtocol.Place("ruin", UUID.randomUUID(), "minecraft:overworld", -41, 88, 603, "旧灯塔", "残垣中的微光。", "沿溪而上。");
        var state = new StoryProtocol.Snapshot(SCOPE, 13, 75, dialogue,
            List.of(new StoryProtocol.Knowledge("old_lights", "旧灯", "有人说灯火从未熄灭。", "LEGEND")), List.of(place),
            List.of(cue("ruin/wind", false, 2)), StoryProtocol.Feedback.CHANGED, "记下了新的见闻。");
        var b = buffer(); try { StoryProtocol.Snapshot.STREAM_CODEC.encode(b, state); assertEquals(state, StoryProtocol.Snapshot.STREAM_CODEC.decode(b)); assertEquals(0, b.readableBytes()); } finally { b.release(); }
    }
    @Test void allIntentKindsRoundTripWithoutClientFactFields() {
        var actions = List.of(new StoryProtocol.Action(SCOPE, StoryProtocol.ActionKind.SYNC, 1, 0, null, null, ""),
            new StoryProtocol.Action(SCOPE, StoryProtocol.ActionKind.CHOOSE, 2, 31, TOKEN, ACTOR, "listen"),
            new StoryProtocol.Action(SCOPE, StoryProtocol.ActionKind.CLOSE, 3, 32, TOKEN, ACTOR, ""));
        for (var action : actions) { var b = buffer(); try { StoryProtocol.Action.STREAM_CODEC.encode(b, action); assertEquals(action, StoryProtocol.Action.STREAM_CODEC.decode(b)); assertEquals(0, b.readableBytes()); } finally { b.release(); } }
    }
    @Test void choiceRequiresBothOneUseTokenAndActorIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new StoryProtocol.Action(SCOPE, StoryProtocol.ActionKind.CHOOSE, 1, 0, null, ACTOR, "listen"));
        assertThrows(IllegalArgumentException.class, () -> new StoryProtocol.Action(SCOPE, StoryProtocol.ActionKind.CHOOSE, 1, 0, TOKEN, null, "listen"));
        assertThrows(IllegalArgumentException.class, () -> new StoryProtocol.Action(SCOPE, StoryProtocol.ActionKind.CHOOSE, 1, 0, TOKEN, ACTOR, ""));
        assertThrows(IllegalArgumentException.class, () -> new StoryProtocol.Action(SCOPE, StoryProtocol.ActionKind.CLOSE, 1, 0, TOKEN, ACTOR, "listen"));
        assertThrows(IllegalArgumentException.class, () -> new StoryProtocol.Action(SCOPE, StoryProtocol.ActionKind.SYNC, 1, 0, TOKEN, ACTOR, ""));
    }
    @Test void invalidEnumsAndCountsFailBeforeAllocatingCollections() {
        var b = buffer(); try { b.writeUtf(SCOPE); b.writeVarInt(999); assertThrows(IllegalArgumentException.class, () -> StoryProtocol.Action.STREAM_CODEC.decode(b)); } finally { b.release(); }
        var oversized = buffer(); try { oversized.writeUtf(SCOPE); oversized.writeVarInt(0); oversized.writeVarLong(0); oversized.writeBoolean(false); oversized.writeVarInt(Integer.MAX_VALUE); assertThrows(IllegalArgumentException.class, () -> StoryProtocol.Snapshot.STREAM_CODEC.decode(oversized)); } finally { oversized.release(); }
    }
    @Test void authoringSpoilersCannotRideAnUnscopedSnapshot() {
        assertThrows(IllegalArgumentException.class, () -> new StoryProtocol.Snapshot("", 0, 0, null,
            List.of(new StoryProtocol.Knowledge("future", "Future", "Never discovered", "FACT")), List.of(), List.of(), StoryProtocol.Feedback.NONE, ""));
        var k = new StoryProtocol.Knowledge("a", "A", "Text", "FACT");
        assertThrows(IllegalArgumentException.class, () -> new StoryProtocol.Snapshot(SCOPE, 0, 0, null, List.of(k, k), List.of(), List.of(), StoryProtocol.Feedback.NONE, ""));
    }
    @Test void listsAreFrozenAndSoundParametersBounded() {
        var choices = new ArrayList<StoryProtocol.Choice>(); choices.add(new StoryProtocol.Choice("a", "A"));
        var dialogue = new StoryProtocol.Dialogue(TOKEN, ACTOR, "node", "Speaker", "Text", choices); choices.clear(); assertEquals(1, dialogue.choices().size());
        assertThrows(IllegalArgumentException.class, () -> new StoryProtocol.SoundCue("a", "minecraft:ambient.cave", Float.NaN, 1, 100, 40, 0, false, ""));
        assertThrows(IllegalArgumentException.class, () -> new StoryProtocol.SoundCue("a", "file:C:/sound.ogg", .4f, 1, 100, 40, 0, false, ""));
        assertThrows(IllegalArgumentException.class, () -> new StoryProtocol.Place("a", ACTOR, "overworld", 0, 64, 0, "Place", "", ""));
    }
    @Test void completeTradeTermsFitTheWireAndOversizedOrControlTextIsRejected() {
        String details = ("投入：" + "长物品名".repeat(35) + " × 64\n").repeat(32);
        assertTrue(details.length() > 2048 && details.length() <= 8192);
        var choice = new StoryProtocol.Choice("trade", "交换", details);
        var snapshot = new StoryProtocol.Snapshot(SCOPE, 1, 1, new StoryProtocol.Dialogue(TOKEN, ACTOR, "node", "Speaker", "Terms", List.of(choice)),
            List.of(), List.of(), List.of(), StoryProtocol.Feedback.NONE, "");
        var b = buffer(); try { StoryProtocol.Snapshot.STREAM_CODEC.encode(b, snapshot); assertEquals(details, StoryProtocol.Snapshot.STREAM_CODEC.decode(b).dialogue().choices().getFirst().details()); } finally { b.release(); }
        assertEquals("", new StoryProtocol.Choice("close", "Close").details());
        assertThrows(IllegalArgumentException.class, () -> new StoryProtocol.Choice("trade", "Trade", "x".repeat(8193)));
        assertThrows(IllegalArgumentException.class, () -> new StoryProtocol.Choice("trade", "Trade", "cost\u0000hidden"));
    }
    private static RegistryFriendlyByteBuf buffer() { return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY); }
    static StoryProtocol.SoundCue cue(String key, boolean music, int priority) { return new StoryProtocol.SoundCue(key, "minecraft:ambient.cave", .4f, 1, 200, 40, priority, music, "Wind"); }
}
