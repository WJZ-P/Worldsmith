package com.wjz.worldsmith.client.story;

import com.mojang.blaze3d.platform.InputConstants;
import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.client.content.WorldContentClientRuntime;
import com.wjz.worldsmith.client.quest.WorldArrivalOverlay;
import com.wjz.worldsmith.config.WorldsmithConfig;
import com.wjz.worldsmith.content.story.StoryNavigation;
import com.wjz.worldsmith.content.story.StoryProtocol;
import com.wjz.worldsmith.content.story.StoryReplyPolicy;
import com.wjz.worldsmith.mixin.client.ToastManagerAccessor;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Connection-scoped server projection. No local fact, discovery, trade or choice mutation. */
public final class StoryJournalClient {
    private static boolean initialized;
    private static KeyMapping openKey;
    private static ClientPacketListener connection;
    private static ClientLevel level;
    private static String scope;
    private static StoryProtocol.Snapshot snapshot;
    private static int sequence, latestRequest, pendingRequest, ticks, lastSync, attempts, closeAttempts;
    private static long requestedAt, noticeUntil, discoveryUntil;
    private static boolean syncNeeded, waitingForScope;
    private static UUID trackedInstance, dismissedActor;
    private static Component notice = Component.empty(), discovery = Component.empty();
    private StoryJournalClient() {}

    public static synchronized void initialize() {
        if (initialized) return;
        StoryProtocol.registerTypes();
        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.worldsmith.story_journal", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K,
            KeyMapping.Category.register(Worldsmith.id("story"))));
        ClientPlayNetworking.registerGlobalReceiver(StoryProtocol.Snapshot.TYPE, (payload, context) -> {
            if (context.player() == context.client().player) receive(context.client(), payload);
        });
        ClientPlayConnectionEvents.INIT.register((handler, client) -> client.execute(() -> { if (client.getConnection() == handler) reset(client, handler, null); }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            if (connection == handler && (client.getConnection() == null || client.getConnection() == handler)) reset(client, null, null);
        }));
        ClientTickEvents.END_CLIENT_TICK.register(StoryJournalClient::tick);
        HudElementRegistry.attachElementAfter(VanillaHudElements.SUBTITLES, Worldsmith.id("story_navigation"), (graphics, delta) -> renderHud(graphics));
        initialized = true;
    }
    private static String localScope(Minecraft client) {
        return client.player != null && client.player.isAlive() && client.level != null && client.getConnection() != null
            ? WorldContentClientRuntime.activeScope() : null;
    }
    public static boolean current(String expectedScope) {
        Minecraft client = Minecraft.getInstance();
        return expectedScope != null && expectedScope.equals(scope) && expectedScope.equals(localScope(client)) && connection != null && connection == client.getConnection();
    }
    public static StoryProtocol.Snapshot snapshot() { return current(scope) ? snapshot : null; }
    public static boolean pending() { return pendingRequest != 0; }
    public static Component notice() { return System.nanoTime() < noticeUntil ? notice : Component.empty(); }
    public static void open() {
        Minecraft client = Minecraft.getInstance(); String current = localScope(client);
        if (current == null) return;
        if (!Objects.equals(current, scope) || connection != client.getConnection()) reset(client, client.getConnection(), current);
        if (snapshot != null && snapshot.dialogue() != null && dismissedActor == null) {
            client.gui.setScreen(new StoryDialogueScreen(scope, snapshot.dialogue())); return;
        }
        client.gui.setScreen(new StoryJournalScreen(scope)); attempts = 0; syncNeeded = true;
        if (!pending()) request(StoryProtocol.ActionKind.SYNC, null, "");
    }
    public static boolean hasDiscoveredPlace(String definitionId) { return snapshot() != null && snapshot.places().stream().anyMatch(p -> p.id().equals(definitionId)); }
    public static boolean trackPlace(String definitionId) {
        var state = snapshot(); Minecraft client = Minecraft.getInstance(); if (state == null || client.player == null || client.level == null) return false;
        String dimension = client.level.dimension().identifier().toString();
        var target = state.places().stream().filter(p -> p.id().equals(definitionId))
            .min(Comparator.<StoryProtocol.Place>comparingInt(p -> p.dimension().equals(dimension) ? 0 : 1)
                .thenComparingInt(p -> StoryNavigation.distance(client.player.getX(), client.player.getY(), client.player.getZ(), p)));
        if (target.isEmpty()) return false; trackedInstance = target.get().instance(); return true;
    }
    public static void clearTracking() { trackedInstance = null; }
    static void track(StoryProtocol.Place place) { if (snapshot() != null && snapshot.places().stream().anyMatch(p -> p.instance().equals(place.instance()))) trackedInstance = place.instance(); }
    public static StoryProtocol.Place trackedPlace() { return snapshot() == null || trackedInstance == null ? null : snapshot.places().stream().filter(p -> p.instance().equals(trackedInstance)).findFirst().orElse(null); }
    /** Native toasts own this screen region until their real slide-out has finished; queued-but-hidden toasts do not. */
    public static boolean nativeTopRightToastVisible() {
        return !((ToastManagerAccessor)Minecraft.getInstance().gui.toastManager()).worldsmith$getVisibleToasts().isEmpty();
    }
    public static boolean topRightHudUnobstructed() { return !WorldArrivalOverlay.isVisible() && !nativeTopRightToastVisible(); }
    static void choose(UUID token, String option) {
        if (pending() || snapshot() == null || snapshot.dialogue() == null || !snapshot.dialogue().token().equals(token) || dismissedActor != null) return;
        if (snapshot.dialogue().choices().stream().noneMatch(c -> c.id().equals(option))) return;
        request(StoryProtocol.ActionKind.CHOOSE, snapshot.dialogue(), option);
    }
    static void closeDialogue(UUID actor) {
        dismissedActor = actor; closeAttempts = 0;
        if (!pending() && snapshot() != null && snapshot.dialogue() != null && snapshot.dialogue().actor().equals(actor)) request(StoryProtocol.ActionKind.CLOSE, snapshot.dialogue(), "");
    }
    private static void tick(Minecraft client) {
        ticks++; String current = localScope(client);
        if (connection != client.getConnection() || level != client.level || !Objects.equals(scope, current)) reset(client, client.getConnection(), current);
        while (openKey.consumeClick()) if (client.gui.screen() == null) open();
        if (pending() && System.nanoTime() - requestedAt > 10_000_000_000L) {
            pendingRequest = 0; syncNeeded = attempts < 3; setNotice(tr("timeout"));
            if (snapshot != null && snapshot.dialogue() != null) dismissedActor = snapshot.dialogue().actor();
            if (client.gui.screen() instanceof StoryDialogueScreen) client.gui.setScreen(null);
        }
        if (current != null && syncNeeded && !pending() && ticks - lastSync >= 20) request(StoryProtocol.ActionKind.SYNC, null, "");
        if (snapshot != null && !pending() && closeAttempts < 3 && snapshot.dialogue() != null && dismissedActor != null && snapshot.dialogue().actor().equals(dismissedActor))
            request(StoryProtocol.ActionKind.CLOSE, snapshot.dialogue(), "");
        if (snapshot != null && snapshot.dialogue() != null && dismissedActor == null && client.gui.screen() == null && client.gui.overlay() == null)
            client.gui.setScreen(new StoryDialogueScreen(scope, snapshot.dialogue()));
        StorySoundscapeClient.tick(client, current == null || snapshot == null ? List.of() : snapshot.sounds());
    }
    private static void request(StoryProtocol.ActionKind kind, StoryProtocol.Dialogue dialogue, String option) {
        if (!current(scope) || pending()) return;
        if (!ClientPlayNetworking.canSend(StoryProtocol.Action.TYPE)) { syncNeeded = false; setNotice(tr("unavailable")); return; }
        if (sequence == Integer.MAX_VALUE) sequence = 0;
        int request = ++sequence; latestRequest = request; pendingRequest = request; requestedAt = System.nanoTime(); lastSync = ticks;
        if (kind == StoryProtocol.ActionKind.SYNC) { attempts++; syncNeeded = false; }
        if (kind == StoryProtocol.ActionKind.CLOSE) closeAttempts++;
        try {
            ClientPlayNetworking.send(new StoryProtocol.Action(scope, kind, request, snapshot == null ? 0 : snapshot.revision(),
                dialogue == null ? null : dialogue.token(), dialogue == null ? null : dialogue.actor(), option));
        } catch (RuntimeException failure) { pendingRequest = 0; syncNeeded = attempts < 3; setNotice(tr("unavailable")); }
    }
    private static void receive(Minecraft client, StoryProtocol.Snapshot payload) {
        String active = localScope(client); if (active == null) return;
        if (connection != client.getConnection() || !active.equals(scope)) reset(client, client.getConnection(), active);
        if (payload.requestId() != 0 && payload.requestId() != latestRequest) return;
        if (StoryReplyPolicy.matchingUnavailable(latestRequest, pendingRequest, payload)) {
            // Asset activation can precede the server's level binding. An empty-scope negative acknowledgement
            // retires only our outstanding request; it never replaces a newer unsolicited snapshot or choice token.
            pendingRequest = 0; syncNeeded = snapshot == null && attempts < 3;
            if (snapshot == null) { waitingForScope = true; setNotice(tr("unavailable")); }
            return;
        }
        if (!active.equals(payload.scope())) return;
        if (payload.requestId() == pendingRequest && payload.requestId() != 0) pendingRequest = 0;
        if (snapshot != null && payload.revision() < snapshot.revision()) return;
        if (snapshot != null) {
            var known = new HashSet<>(snapshot.knowledge().stream().map(StoryProtocol.Knowledge::id).toList());
            var places = new HashSet<>(snapshot.places().stream().map(StoryProtocol.Place::instance).toList());
            var newKnowledge = payload.knowledge().stream().filter(k -> !known.contains(k.id())).findFirst();
            var newPlace = payload.places().stream().filter(p -> !places.contains(p.instance())).findFirst();
            if (newPlace.isPresent()) discover(tr("discovered_place", newPlace.get().name()));
            else if (newKnowledge.isPresent()) discover(tr("discovered_knowledge", newKnowledge.get().title()));
        }
        snapshot = payload; syncNeeded = false; attempts = 0;
        if (waitingForScope) { waitingForScope = false; notice = Component.empty(); noticeUntil = 0; }
        if (payload.feedback() != StoryProtocol.Feedback.NONE) setNotice(payload.message().isBlank()
            ? tr("feedback." + payload.feedback().name().toLowerCase(Locale.ROOT)) : Component.literal(payload.message()));
        if (trackedInstance != null && payload.places().stream().noneMatch(p -> p.instance().equals(trackedInstance))) trackedInstance = null;
        if (payload.dialogue() == null) {
            dismissedActor = null; closeAttempts = 0; if (client.gui.screen() instanceof StoryDialogueScreen) client.gui.setScreen(null);
        } else {
            if (dismissedActor != null && !dismissedActor.equals(payload.dialogue().actor())) { dismissedActor = null; closeAttempts = 0; }
            if (dismissedActor == null && client.gui.screen() instanceof StoryDialogueScreen screen) screen.update(payload.dialogue());
        }
        if (client.gui.screen() instanceof StoryJournalScreen screen) screen.update();
    }
    private static void discover(Component value) { discovery = value; discoveryUntil = System.nanoTime() + 7_000_000_000L; }
    private static void setNotice(Component value) { notice = value; noticeUntil = System.nanoTime() + 12_000_000_000L; }
    private static void reset(Minecraft client, ClientPacketListener nextConnection, String nextScope) {
        StorySoundscapeClient.clear(client); connection = nextConnection; level = client.level; scope = nextScope; snapshot = null;
        latestRequest = 0; pendingRequest = 0; attempts = 0; closeAttempts = 0; syncNeeded = nextScope != null; waitingForScope = false; lastSync = ticks;
        trackedInstance = null; dismissedActor = null; notice = Component.empty(); noticeUntil = 0; discoveryUntil = 0;
        if (client.gui.screen() instanceof StoryDialogueScreen || client.gui.screen() instanceof StoryJournalScreen) client.gui.setScreen(null);
    }
    private static void renderHud(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance(); if (!current(scope) || client.gui.hud.isHidden() || client.gui.screen() != null || client.gui.overlay() != null) return;
        var settings = WorldsmithConfig.get().getClient(); int width = graphics.guiWidth(); if (width < 200 || graphics.guiHeight() < 150) return;
        // Captions have a separate bottom-center region and remain available during an arrival or a native toast.
        StorySoundscapeClient.renderCaption(graphics, client);
        if (!topRightHudUnobstructed()) return;
        int w = Math.min(260, width - 24), x = width - w - 10, y = 12, bottom = graphics.guiHeight() - 78;
        var quest = settings.getShowStoryTracking() ? com.wjz.worldsmith.client.quest.QuestJournalClient.tracked() : null;
        if (quest != null) {
            var objective = quest.objectives().stream().filter(o -> !o.optional() && o.progress() < o.required()).findFirst();
            Component progress = objective.<Component>map(o -> Component.literal(o.targetLabel() + " " + o.progress() + "/" + o.required()))
                .orElseGet(() -> Component.translatable("worldsmith.quests.status.ready"));
            var lines = client.font.split(progress, w - 16).stream().limit(2).toList(); int h = 23 + lines.size() * 12;
            graphics.fill(x, y, width - 10, y + h, 0xBF152320);
            graphics.text(client.font, client.font.plainSubstrByWidth(quest.title(), w - 16), x + 8, y + 6, 0xFFF0DBA7);
            int lineY = y + 20; for (var line : lines) { graphics.text(client.font, line, x + 8, lineY, 0xFFDBE5D8); lineY += 12; }
            y += h + 6;
        }
        var place = settings.getShowStoryTracking() ? trackedPlace() : null;
        if (place != null && y + 34 <= bottom) {
            Component direction;
            if (!place.dimension().equals(client.level.dimension().identifier().toString())) direction = tr("other_dimension");
            else {
                var bearing = StoryNavigation.bearing(place.x() + .5 - client.player.getX(), place.z() + .5 - client.player.getZ(), client.player.getYRot());
                direction = tr("navigation", tr("bearing." + bearing.name().toLowerCase(Locale.ROOT)), StoryNavigation.distance(client.player.getX(), client.player.getY(), client.player.getZ(), place));
            }
            graphics.fill(x, y, width - 10, y + 34, 0xBF152320);
            graphics.text(client.font, client.font.plainSubstrByWidth(place.name(), w - 16), x + 8, y + 6, 0xFFF0DBA7);
            graphics.text(client.font, client.font.plainSubstrByWidth(direction.getString(), w - 16), x + 8, y + 20, 0xFFDBE5D8); y += 40;
        }
        if (settings.getShowStoryHints() && System.nanoTime() < discoveryUntil && bottom - y >= 22) {
            var lines = client.font.split(discovery.copy().append(" ").append(tr("journal_hint", openKey.getTranslatedKeyMessage())), w - 16);
            int maxLines = Math.min(3, (bottom - y - 10) / 12);
            graphics.fill(x, y, width - 10, y + 10 + Math.min(maxLines, lines.size()) * 12, 0xBF152320);
            for (var line : lines.stream().limit(maxLines).toList()) { graphics.text(client.font, line, x + 8, y + 6, 0xFFF0DBA7); y += 12; }
        }
    }
    private static Component tr(String key, Object... args) { return Component.translatable("worldsmith.story." + key, args); }
}
