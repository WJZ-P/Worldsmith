package com.wjz.worldsmith.client.quest;

import com.mojang.blaze3d.platform.InputConstants;
import com.wjz.worldsmith.client.content.WorldContentClientRuntime;
import com.wjz.worldsmith.content.quest.QuestProtocol;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** Client-only intent sender and scoped server snapshot cache. It never changes quest progress locally. */
public final class QuestJournalClient {
    private static boolean initialized;
    private static KeyMapping openKey;
    private static ClientPacketListener connection;
    private static String scope;
    private static QuestProtocol.Snapshot snapshot;
    private static int sequence;
    private static int latestRequest;
    private static int pendingRequest;
    private static long requestedAt;
    private static int ticks;
    private static int lastSyncTick;
    private static boolean syncNeeded;
    private static int syncAttempts;
    private static Component notice;
    private static int noticeColor = 0xFFBCC5D3;
    private static long noticeUntil;
    private static boolean waitingNotice;
    private QuestJournalClient() {}

    public static void initialize() {
        if (initialized) return;
        QuestProtocol.registerTypes();
        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.worldsmith.quest_journal", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J,
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("worldsmith", "quests"))));
        ClientPlayNetworking.registerGlobalReceiver(QuestProtocol.Snapshot.TYPE, (payload, context) -> {
            if (context.player() != context.client().player) return;
            receive(context.client(), payload);
        });
        ClientPlayConnectionEvents.INIT.register((handler, client) -> reset(client, handler, null));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset(client, null, null));
        ClientTickEvents.END_CLIENT_TICK.register(QuestJournalClient::tick);
        initialized = true;
    }

    private static void tick(Minecraft client) {
        ticks++;
        String current = localScope(client);
        if (connection != client.getConnection() || !java.util.Objects.equals(scope, current)) reset(client, client.getConnection(), current);
        while (openKey.consumeClick()) if (client.gui.screen() == null) open(client);
        if (pendingRequest != 0 && System.nanoTime() - requestedAt > 10_000_000_000L) {
            pendingRequest = 0; snapshot = null;
            // Recover state after an uncertain action, never replay the action itself.
            syncNeeded = syncAttempts < 3;
            setNotice(Component.translatable("worldsmith.quests.timeout"), 0xFFFFCC7A, 12);
            changed(client);
        }
        if (syncNeeded && current != null && pendingRequest == 0 && ticks - lastSyncTick >= 20) sync();
    }

    public static void open(Minecraft client) {
        String current = localScope(client);
        if (current == null) {
            if (client.player != null) client.player.sendOverlayMessage(Component.translatable("worldsmith.quests.local_only"));
            return;
        }
        if (connection != client.getConnection() || !current.equals(scope)) reset(client, client.getConnection(), current);
        client.gui.setScreen(new QuestJournalScreen(current, connection));
        syncAttempts = 0;
        sync();
    }

    static boolean matchesOpenKey(net.minecraft.client.input.KeyEvent event) { return openKey != null && openKey.matches(event); }
    static Component openKeyLabel() { return openKey.getTranslatedKeyMessage(); }
    static QuestProtocol.Snapshot snapshot(String expectedScope) { return expectedScope.equals(scope) ? snapshot : null; }
    static boolean pending() { return pendingRequest != 0; }
    static boolean current(String expectedScope, ClientPacketListener expectedConnection) {
        Minecraft client = Minecraft.getInstance();
        return expectedConnection != null && expectedConnection == client.getConnection() && expectedConnection == connection && expectedScope.equals(scope) && expectedScope.equals(localScope(client));
    }

    static void sync() { request(QuestProtocol.ActionKind.SYNC, ""); }
    static void deliver(String questId) { request(QuestProtocol.ActionKind.DELIVER, questId); }
    static void claim(String questId) { request(QuestProtocol.ActionKind.CLAIM, questId); }

    private static void request(QuestProtocol.ActionKind kind, String questId) {
        Minecraft client = Minecraft.getInstance();
        if (scope == null || !current(scope, connection)) return;
        if (pendingRequest != 0) return;
        if (kind == QuestProtocol.ActionKind.SYNC) { syncNeeded = false; syncAttempts++; }
        if (!ClientPlayNetworking.canSend(QuestProtocol.Action.TYPE)) {
            syncNeeded = syncAttempts < 3;
            setNotice(Component.translatable("worldsmith.quests.channel_unavailable"), 0xFFFFCC7A, 8); lastSyncTick = ticks; changed(client); return;
        }
        if (kind != QuestProtocol.ActionKind.SYNC && snapshot == null) return;
        if (sequence == Integer.MAX_VALUE) sequence = 0;
        int request = ++sequence;
        long revision = snapshot == null ? 0 : snapshot.revision();
        latestRequest = request; pendingRequest = request; requestedAt = System.nanoTime(); lastSyncTick = ticks;
        if (kind != QuestProtocol.ActionKind.SYNC || snapshot == null) {
            setNotice(Component.translatable(kind == QuestProtocol.ActionKind.SYNC ? "worldsmith.quests.loading" : "worldsmith.quests.processing"), 0xFF99C8F5, 10);
            waitingNotice = true;
        }
        try {
            ClientPlayNetworking.send(new QuestProtocol.Action(scope, questId, kind, request, revision));
        } catch (RuntimeException failure) {
            pendingRequest = 0;
            syncNeeded = syncAttempts < 3;
            setNotice(Component.translatable("worldsmith.quests.channel_unavailable"), 0xFFFFCC7A, 10);
        }
        changed(client);
    }

    private static void receive(Minecraft client, QuestProtocol.Snapshot payload) {
        if (scope == null || !current(scope, connection) || payload.requestId() != 0 && payload.requestId() != latestRequest) return;
        if (payload.requestId() != 0 && payload.requestId() == pendingRequest) pendingRequest = 0;
        if (!payload.scope().equals(scope)) {
            if (payload.scope().isEmpty() && payload.feedback() == QuestProtocol.Feedback.UNAVAILABLE) {
                snapshot = null; feedback(payload); changed(client); return;
            }
            if (client.player != null) client.player.sendOverlayMessage(Component.translatable("worldsmith.quests.world_changed"));
            reset(client, client.getConnection(), localScope(client)); return;
        }
        if (snapshot != null && payload.revision() < snapshot.revision()) {
            if (payload.requestId() != 0 && payload.feedback() != QuestProtocol.Feedback.NONE) feedback(payload);
            changed(client); return;
        }
        snapshot = payload;
        syncNeeded = false; syncAttempts = 0;
        if (payload.feedback() != QuestProtocol.Feedback.NONE) feedback(payload);
        else if (waitingNotice && pendingRequest == 0) { notice = null; noticeUntil = 0; waitingNotice = false; }
        changed(client);
    }

    private static void feedback(QuestProtocol.Snapshot payload) {
        Component message = Component.translatable("worldsmith.quests.feedback." + payload.feedback().name().toLowerCase(java.util.Locale.ROOT));
        if (!payload.message().isBlank()) message = message.copy().append(" ").append(Component.literal(payload.message()));
        int color = switch (payload.feedback()) { case CLAIMED, DELIVERED -> 0xFF9CDDAD; case NONE -> 0xFFBCC5D3; default -> 0xFFFFCC7A; };
        setNotice(message, color, 15);
    }

    static Component notice() {
        if (notice != null && System.nanoTime() < noticeUntil) return notice;
        return Component.empty();
    }
    static int noticeColor() { return notice != null && System.nanoTime() < noticeUntil ? noticeColor : 0xFFBCC5D3; }
    private static void setNotice(Component value, int color, int seconds) { notice = value; noticeColor = color; noticeUntil = System.nanoTime() + seconds * 1_000_000_000L; waitingNotice = false; }

    private static String localScope(Minecraft client) {
        return client.player != null && client.player.isAlive() && client.level != null && client.getConnection() != null && client.isLocalServer()
            ? WorldContentClientRuntime.activeScope() : null;
    }
    private static void changed(Minecraft client) { if (client.gui.screen() instanceof QuestJournalScreen screen) screen.serverStateChanged(); }
    private static void reset(Minecraft client, ClientPacketListener nextConnection, String nextScope) {
        snapshot = null; pendingRequest = 0; latestRequest = 0; notice = null; noticeUntil = 0; waitingNotice = false;
        connection = nextConnection; scope = nextScope; lastSyncTick = ticks;
        syncNeeded = nextScope != null; syncAttempts = 0;
        if (client.gui.screen() instanceof QuestJournalScreen) client.gui.setScreen(null);
    }
}
