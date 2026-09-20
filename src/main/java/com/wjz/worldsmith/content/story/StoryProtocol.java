package com.wjz.worldsmith.content.story;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/** Player knowledge, not the authoring library. Requests carry intent and one-use dialogue identity only. */
public final class StoryProtocol {
    public static final int MAX_KNOWLEDGE = 128, MAX_PLACES = 128, MAX_SOUNDS = 16, MAX_CHOICES = 16;
    public static final int MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024;
    private static boolean registered, serverRegistered;
    private StoryProtocol() {}

    public enum ActionKind { SYNC, CHOOSE, CLOSE }
    public enum Feedback { NONE, CHANGED, STALE_REVISION, EXPIRED, UNAVAILABLE, NO_MATERIALS, NO_SPACE, LOCKED, COOLDOWN, ERROR }

    public record Action(String scope, ActionKind kind, int requestId, long expectedRevision, UUID token, UUID actor, String optionId) implements CustomPacketPayload {
        public static final Type<Action> TYPE = new Type<>(identifier("story_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Action> STREAM_CODEC = StreamCodec.of(StoryProtocol::writeAction, StoryProtocol::readAction);
        public Action {
            checkScope(scope); Objects.requireNonNull(kind); id(optionId, kind != ActionKind.CHOOSE);
            if (requestId < 1 || expectedRevision < 0 || scope.isEmpty()
                || kind == ActionKind.SYNC && (token != null || actor != null || !optionId.isEmpty())
                || kind != ActionKind.SYNC && (token == null || actor == null)
                || kind == ActionKind.CLOSE && !optionId.isEmpty()) throw new IllegalArgumentException("Invalid story intent identity");
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Choice(String id, String label, String details) {
        public Choice(String id, String label) { this(id, label, ""); }
        public Choice { StoryProtocol.id(id, false); text(label, 512, false); text(details, 8192, true); if (label.isBlank()) throw new IllegalArgumentException("Empty choice label"); }
    }
    public record Dialogue(UUID token, UUID actor, String nodeId, String speaker, String text, List<Choice> choices) {
        public Dialogue {
            Objects.requireNonNull(token); Objects.requireNonNull(actor); id(nodeId, false);
            StoryProtocol.text(speaker, 160, false); StoryProtocol.text(text, 8192, true);
            choices = List.copyOf(choices); unique(choices.stream().map(Choice::id).toList(), MAX_CHOICES);
        }
    }
    public record Knowledge(String id, String title, String text, String truth) {
        public Knowledge {
            StoryProtocol.id(id, false); StoryProtocol.text(title, 160, false); StoryProtocol.text(text, 8192, true);
            if (!List.of("FACT", "LEGEND", "BELIEF").contains(truth)) throw new IllegalArgumentException("Unknown knowledge attribution");
        }
    }
    /** A server-discovered marker instance. Definition IDs alone are never a claim that a generated location exists. */
    public record Place(String id, UUID instance, String dimension, int x, int y, int z, String name, String description, String clue) {
        public Place {
            StoryProtocol.id(id, false); Objects.requireNonNull(instance); resource(dimension);
            text(name, 160, false); text(description, 2048, true); text(clue, 1024, true);
            if (Math.abs((long)x) > 30_000_000 || Math.abs((long)z) > 30_000_000 || y < -2048 || y > 2048)
                throw new IllegalArgumentException("Discovered place exceeds world position bounds");
        }
    }
    /** Stable key belongs to a soundscape/layer, so repeat snapshots do not restart playback. */
    public record SoundCue(String key, String sound, float volume, float pitch, int periodTicks, int fadeTicks, int priority, boolean music, String subtitle) {
        public SoundCue {
            if (key == null || !key.matches("[a-z0-9][a-z0-9_.:/-]{0,128}")) throw new IllegalArgumentException("Invalid stable sound layer key");
            resource(sound); text(subtitle, 256, false);
            if (!Float.isFinite(volume) || volume < 0 || volume > 1 || !Float.isFinite(pitch) || pitch < .5f || pitch > 2
                || periodTicks < 20 || periodTicks > 24000 || fadeTicks < 0 || fadeTicks > 1200 || priority < -100 || priority > 100)
                throw new IllegalArgumentException("Invalid bounded story sound parameters");
        }
    }
    public record Snapshot(String scope, int requestId, long revision, Dialogue dialogue, List<Knowledge> knowledge,
                           List<Place> places, List<SoundCue> sounds, Feedback feedback, String message) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = new Type<>(identifier("story_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> STREAM_CODEC = StreamCodec.of(StoryProtocol::writeSnapshot, StoryProtocol::readSnapshot);
        public Snapshot {
            checkScope(scope); Objects.requireNonNull(feedback); text(message, 512, true);
            knowledge = List.copyOf(knowledge); places = List.copyOf(places); sounds = List.copyOf(sounds);
            unique(knowledge.stream().map(Knowledge::id).toList(), MAX_KNOWLEDGE);
            unique(places.stream().map(p -> p.instance().toString()).toList(), MAX_PLACES);
            unique(sounds.stream().map(SoundCue::key).toList(), MAX_SOUNDS);
            if (requestId < 0 || revision < 0 || scope.isEmpty() && (dialogue != null || !knowledge.isEmpty() || !places.isEmpty() || !sounds.isEmpty()))
                throw new IllegalArgumentException("Invalid scoped story snapshot");
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static synchronized void registerTypes() {
        if (registered) return;
        PayloadTypeRegistry.serverboundPlay().register(Action.TYPE, Action.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().registerLarge(Snapshot.TYPE, Snapshot.STREAM_CODEC, MAX_SNAPSHOT_BYTES);
        registered = true;
    }
    /** Fabric play receivers execute on the owning server thread. The handler performs all authority checks. */
    public static synchronized void registerServer(BiConsumer<ServerPlayer, Action> handler) {
        Objects.requireNonNull(handler); registerTypes();
        if (serverRegistered) throw new IllegalStateException("Story authority already registered");
        if (!ServerPlayNetworking.registerGlobalReceiver(Action.TYPE, (action, context) -> handler.accept(context.player(), action)))
            throw new IllegalStateException("Story intent channel already registered");
        serverRegistered = true;
    }

    private static void writeAction(RegistryFriendlyByteBuf b, Action v) {
        b.writeUtf(v.scope, 64); b.writeVarInt(v.kind.ordinal()); b.writeVarInt(v.requestId); b.writeVarLong(v.expectedRevision);
        b.writeBoolean(v.token != null); if (v.token != null) { b.writeUUID(v.token); b.writeUUID(v.actor); }
        b.writeUtf(v.optionId, 64);
    }
    private static Action readAction(RegistryFriendlyByteBuf b) {
        String scope = b.readUtf(64); ActionKind kind = enumeration(b, ActionKind.values()); int request = b.readVarInt(); long revision = b.readVarLong();
        boolean session = b.readBoolean(); UUID token = session ? b.readUUID() : null, actor = session ? b.readUUID() : null;
        return new Action(scope, kind, request, revision, token, actor, b.readUtf(64));
    }
    private static void writeSnapshot(RegistryFriendlyByteBuf b, Snapshot v) {
        b.writeUtf(v.scope, 64); b.writeVarInt(v.requestId); b.writeVarLong(v.revision); b.writeBoolean(v.dialogue != null);
        if (v.dialogue != null) {
            var d = v.dialogue; b.writeUUID(d.token); b.writeUUID(d.actor); b.writeUtf(d.nodeId, 64); b.writeUtf(d.speaker, 160); b.writeUtf(d.text, 8192);
            b.writeVarInt(d.choices.size()); for (var c : d.choices) { b.writeUtf(c.id, 64); b.writeUtf(c.label, 512); b.writeUtf(c.details, 8192); }
        }
        b.writeVarInt(v.knowledge.size());
        for (var k : v.knowledge) { b.writeUtf(k.id, 64); b.writeUtf(k.title, 160); b.writeUtf(k.text, 8192); b.writeUtf(k.truth, 8); }
        b.writeVarInt(v.places.size());
        for (var p : v.places) {
            b.writeUtf(p.id, 64); b.writeUUID(p.instance); b.writeUtf(p.dimension, 256); b.writeInt(p.x); b.writeInt(p.y); b.writeInt(p.z);
            b.writeUtf(p.name, 160); b.writeUtf(p.description, 2048); b.writeUtf(p.clue, 1024);
        }
        b.writeVarInt(v.sounds.size());
        for (var s : v.sounds) {
            b.writeUtf(s.key, 129); b.writeUtf(s.sound, 256); b.writeFloat(s.volume); b.writeFloat(s.pitch); b.writeVarInt(s.periodTicks);
            b.writeVarInt(s.fadeTicks); b.writeVarInt(s.priority); b.writeBoolean(s.music); b.writeUtf(s.subtitle, 256);
        }
        b.writeVarInt(v.feedback.ordinal()); b.writeUtf(v.message, 512);
    }
    private static Snapshot readSnapshot(RegistryFriendlyByteBuf b) {
        String scope = b.readUtf(64); int request = b.readVarInt(); long revision = b.readVarLong(); Dialogue dialogue = null;
        if (b.readBoolean()) {
            UUID token = b.readUUID(), actor = b.readUUID(); String node = b.readUtf(64), speaker = b.readUtf(160), text = b.readUtf(8192);
            int n = count(b, MAX_CHOICES); var choices = new ArrayList<Choice>(n);
            for (int i = 0; i < n; i++) choices.add(new Choice(b.readUtf(64), b.readUtf(512), b.readUtf(8192)));
            dialogue = new Dialogue(token, actor, node, speaker, text, choices);
        }
        int n = count(b, MAX_KNOWLEDGE); var knowledge = new ArrayList<Knowledge>(n);
        for (int i = 0; i < n; i++) knowledge.add(new Knowledge(b.readUtf(64), b.readUtf(160), b.readUtf(8192), b.readUtf(8)));
        n = count(b, MAX_PLACES); var places = new ArrayList<Place>(n);
        for (int i = 0; i < n; i++) places.add(new Place(b.readUtf(64), b.readUUID(), b.readUtf(256), b.readInt(), b.readInt(), b.readInt(), b.readUtf(160), b.readUtf(2048), b.readUtf(1024)));
        n = count(b, MAX_SOUNDS); var sounds = new ArrayList<SoundCue>(n);
        for (int i = 0; i < n; i++) sounds.add(new SoundCue(b.readUtf(129), b.readUtf(256), b.readFloat(), b.readFloat(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readBoolean(), b.readUtf(256)));
        return new Snapshot(scope, request, revision, dialogue, knowledge, places, sounds, enumeration(b, Feedback.values()), b.readUtf(512));
    }
    private static int count(RegistryFriendlyByteBuf b, int maximum) {
        int n = b.readVarInt(); if (n < 0 || n > maximum) throw new IllegalArgumentException("Story collection exceeds wire budget"); return n;
    }
    private static <T> T enumeration(RegistryFriendlyByteBuf b, T[] values) {
        int i = b.readVarInt(); if (i < 0 || i >= values.length) throw new IllegalArgumentException("Unknown story intent enum"); return values[i];
    }
    private static void unique(List<String> ids, int maximum) {
        if (ids.size() > maximum || new HashSet<>(ids).size() != ids.size()) throw new IllegalArgumentException("Duplicate or excessive story entries");
    }
    private static Identifier identifier(String path) { return Identifier.fromNamespaceAndPath("worldsmith", path); }
    private static void resource(String value) {
        if (value == null || value.length() > 256 || !value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || Identifier.tryParse(value) == null)
            throw new IllegalArgumentException("Invalid story resource identifier");
    }
    private static void checkScope(String value) { if (value == null || !value.isEmpty() && !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid story world scope"); }
    private static void id(String value, boolean empty) { if (value == null || !(empty && value.isEmpty()) && !value.matches("[a-z0-9][a-z0-9_.-]{0,63}")) throw new IllegalArgumentException("Invalid story identity"); }
    private static void text(String value, int maximum, boolean multiline) {
        if (value == null || value.length() > maximum || value.chars().anyMatch(c -> Character.isISOControl(c) && !(multiline && c == '\n')))
            throw new IllegalArgumentException("Invalid story display text");
    }
}
