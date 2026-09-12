package com.wjz.worldsmith.client;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.core.mcp.ResourcePackDetails;
import com.wjz.worldsmith.core.mcp.ResourcePackExchange;
import com.wjz.worldsmith.core.mcp.ResourcePackSummary;
import com.wjz.worldsmith.core.mcp.ResourcePackIconPreview;
import com.mojang.blaze3d.platform.NativeImage;
import com.wjz.worldsmith.mcp.WorldsmithMcpService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;

/** A quiet configuration browser. Import and native activation happen only on the explicit create action. */
public final class WorldsmithResourcePackScreen extends Screen {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "worldsmith-resource-pack-io"); thread.setDaemon(true); return thread;
    });
    private static final Identifier ICON = Identifier.fromNamespaceAndPath("worldsmith", "icon/world_pack");
    private static final int MAX_ICON_CACHE = 64;
    private static final int MAX_PENDING_ICONS = 4;
    private static final java.util.concurrent.atomic.AtomicLong ICON_OWNERS = new java.util.concurrent.atomic.AtomicLong();
    private static final int BODY_TOP = 34, CARD_HEIGHT = 42, CARD_STEP = 48;
    private static final List<String> COUNT_KEYS = List.of("biomes", "structures", "features", "blocks", "creatures", "items", "quests", "pngAssets");
    private final Screen parent;
    private ResourcePackExchange exchange; // IO executor only, including construction.
    private final Map<String, CachedArchive> archives = new HashMap<>(); // IO only.
    private final Map<String, ResourcePackDetails> detailCache = new HashMap<>(); // IO only.
    private final long iconOwner = ICON_OWNERS.incrementAndGet();
    private final LinkedHashMap<String, Identifier> iconTextures = new LinkedHashMap<>(16, .75f, true); // Client thread only; null caches an unavailable icon.
    private final Set<String> pendingIcons = new HashSet<>();
    private volatile long iconEpoch;
    private List<PackCard> packs = List.of();
    private String selectedId;
    private ResourcePackDetails selectedDetails;
    private Component detailFailure;
    private Component notice = Component.empty();
    private int noticeColor = 0xFFFFB9A1;
    private boolean attached, loading, detailsLoading, actionBusy, needsRefresh = true, invalidateDetails = true;
    private boolean tooSmall, watchFallback, invalidateArchives;
    private volatile WorldsmithPackDirectoryWatch watcher;
    private long visit, catalogOperation, detailOperation, actionOperation, refreshAfter, fallbackAfter;
    private int page, pageSize, listWidth, detailX, detailWidth, bodyBottom, detailScroll, maximumDetailScroll;
    private boolean showPager;
    private List<FormattedCharSequence> titleLines = List.of(), descriptionLines = List.of();

    private record PackCard(ResourcePackSummary summary, String filename, ResourcePackDetails details) {}
    private record Stamp(long bytes, FileTime modified, String fileKey) {}
    private record CachedArchive(Stamp stamp, ResourcePackDetails details, String failure) {}
    private record Snapshot(List<PackCard> cards, boolean settling, int failures, String firstFailure,
                            WorldsmithPackDirectoryWatch watch, boolean ownsWatch) {}

    public WorldsmithResourcePackScreen(Screen parent) { super(tr("title")); this.parent = parent; }
    private static Component tr(String key, Object... args) { return Component.translatable("worldsmith.packs." + key, args); }
    private static long now() { return System.nanoTime() / 1_000_000; }

    @Override public void added() {
        attached = true; visit++; needsRefresh = true; invalidateDetails = true;
        refreshAfter = 0; fallbackAfter = now() + 3000; watchFallback = false;
    }
    @Override public void removed() {
        attached = false; visit++; catalogOperation++; detailOperation++; actionOperation++;
        loading = false; detailsLoading = false; actionBusy = false;
        var old = watcher; watcher = null; if (old != null) old.close();
        clearIcons();
    }
    @Override public void tick() {
        long time = now(); var watch = watcher;
        if (watch != null && watch.changed()) {
            needsRefresh = true; invalidateDetails = true; invalidateArchives = true; refreshAfter = time + 450;
        }
        // Some filesystems do not offer native watch notifications. Keep that fallback off the render path.
        if ((watch == null || watch.needsRegistration()) && time >= fallbackAfter) {
            needsRefresh = true; invalidateDetails = true; fallbackAfter = time + 3000;
        }
        if (needsRefresh && !loading && !actionBusy && !tooSmall && time >= refreshAfter) refresh();
    }

    @Override protected void init() {
        tooSmall = width < 320 || height < 200;
        if (tooSmall) {
            addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
                    .bounds(width / 2 - 50, height - 28, 100, 20).build()); return;
        }
        bodyBottom = height - 46;
        listWidth = Math.max(126, Math.min(210, width / 3));
        detailX = 24 + listWidth; detailWidth = width - detailX - 12;
        int available = bodyBottom - BODY_TOP - 12;
        showPager = packs.size() > Math.max(1, available / CARD_STEP);
        pageSize = Math.max(1, (available - (showPager ? 22 : 0)) / CARD_STEP);
        page = Math.max(0, Math.min(page, pages() - 1));
        for (int row = 0; row < pageSize; row++) {
            int index = page * pageSize + row; if (index >= packs.size()) break;
            var card = packs.get(index);
            addRenderableWidget(new PackButton(18, BODY_TOP + 6 + row * CARD_STEP, listWidth - 12, card));
        }
        if (showPager) {
            var previous = addRenderableWidget(Button.builder(Component.literal("<"), button -> { page--; rebuildWidgets(); })
                    .bounds(18, bodyBottom - 23, 22, 18).tooltip(Tooltip.create(tr("previous"))).build());
            var next = addRenderableWidget(Button.builder(Component.literal(">"), button -> { page++; rebuildWidgets(); })
                    .bounds(12 + listWidth - 28, bodyBottom - 23, 22, 18).tooltip(Tooltip.create(tr("next"))).build());
            previous.active = page > 0; next.active = page + 1 < pages();
        }
        int buttonWidth = (width - 36) / 3;
        var create = addButton("create", 12, buttonWidth, this::createWorld);
        create.active = !actionBusy && selected() != null && minecraft.level == null && minecraft.getSingleplayerServer() == null;
        addButton("directory", 18 + buttonWidth, buttonWidth, this::openDirectory).active = !actionBusy;
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
                .bounds(24 + 2 * buttonWidth, height - 28, buttonWidth, 20).build());
        wrapDetails();
        loadVisibleIcons();
    }

    private Button addButton(String key, int x, int buttonWidth, Runnable action) {
        return addRenderableWidget(Button.builder(tr(key), ignored -> action.run()).bounds(x, height - 28, buttonWidth, 20)
                .tooltip(Tooltip.create(tr(key + ".hint"))).build());
    }
    private int pages() { return Math.max(1, (packs.size() + Math.max(1, pageSize) - 1) / Math.max(1, pageSize)); }
    private PackCard selected() { return packs.stream().filter(p -> p.summary().getBundleId().equals(selectedId)).findFirst().orElse(null); }
    private void select(String id) {
        if (actionBusy || id.equals(selectedId)) return;
        selectedId = id; detailScroll = 0; notice = Component.empty(); loadDetails(); rebuildWidgets();
    }
    private ResourcePackExchange exchange() {
        if (exchange == null) exchange = new ResourcePackExchange(WorldsmithMcpService.packDirectory());
        return exchange;
    }

    private Snapshot readSnapshot(boolean invalidate, boolean refreshArchives, WorldsmithPackDirectoryWatch currentWatch, boolean fallback) throws Exception {
        var store = exchange(); if (invalidate) detailCache.clear();
        if (refreshArchives) archives.clear();
        var managed = store.listPacks(); var files = store.listInbox();
        var result = new LinkedHashMap<String, PackCard>();
        for (var pack : managed) result.put(pack.getBundleId(), new PackCard(pack, null, detailCache.get(pack.getBundleId())));
        var present = new HashSet<String>(); boolean settling = false; int failures = 0; String firstFailure = "";
        for (var file : files.getEntries()) {
            String filename = file.getFilename(); present.add(filename);
            long age = System.currentTimeMillis() - file.getModifiedMillis();
            if (age >= 0 && age < 600) { settling = true; continue; }
            try {
                Path path = store.inboxDirectory().resolve(filename);
                var attributes = Files.readAttributes(path, BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                var stamp = new Stamp(attributes.size(), attributes.lastModifiedTime(), String.valueOf(attributes.fileKey()));
                var cached = archives.get(filename);
                if (cached == null || !cached.stamp().equals(stamp)) {
                    try { cached = new CachedArchive(stamp, store.describeInbox(filename), null); }
                    catch (Exception error) { cached = new CachedArchive(stamp, null, shortFailure(error)); }
                    archives.put(filename, cached);
                }
                if (cached.details() != null) {
                    var details = cached.details();
                    result.putIfAbsent(details.getSummary().getBundleId(), new PackCard(details.getSummary(), filename, details));
                } else { failures++; if (firstFailure.isEmpty()) firstFailure = filename + ": " + cached.failure(); }
            } catch (java.nio.file.NoSuchFileException ignored) { settling = true; }
        }
        archives.keySet().retainAll(present);
        var cards = new ArrayList<>(result.values());
        cards.sort(Comparator.comparing(card -> card.summary().getDisplayName(), String.CASE_INSENSITIVE_ORDER));
        boolean owns = currentWatch == null;
        if (currentWatch != null || !fallback) {
            try {
                if (currentWatch == null) currentWatch = new WorldsmithPackDirectoryWatch();
                currentWatch.register(store.libraryDirectory(), store.inboxDirectory(), managed.stream().map(p -> Path.of(p.getPath())).toList());
            } catch (Exception error) {
                if (currentWatch != null) currentWatch.close(); currentWatch = null;
                Worldsmith.LOGGER.debug("World-pack directory watch unavailable; using asynchronous snapshot fallback", error);
            }
        }
        return new Snapshot(List.copyOf(cards), settling, failures, firstFailure, currentWatch, owns);
    }

    private void refresh() {
        needsRefresh = false; loading = true;
        long taskVisit = visit, ticket = ++catalogOperation;
        boolean invalidated = invalidateDetails; invalidateDetails = false;
        boolean refreshArchives = invalidateArchives; invalidateArchives = false;
        var currentWatch = watcher; boolean fallback = watchFallback;
        CompletableFuture.supplyAsync(() -> {
            try { return readSnapshot(invalidated, refreshArchives, currentWatch, fallback); }
            catch (Exception error) { throw new CompletionException(error); }
        }, IO).whenComplete((snapshot, failure) -> minecraft.execute(() -> {
            if (!attached || taskVisit != visit || ticket != catalogOperation) {
                if (snapshot != null && snapshot.ownsWatch() && snapshot.watch() != null) snapshot.watch().close(); return;
            }
            loading = false;
            if (failure != null) { showFailure(failure); return; }
            watcher = snapshot.watch(); watchFallback = watcher == null;
            packs = snapshot.cards();
            if (invalidated) clearIcons();
            if (selected() == null) { selectedId = packs.isEmpty() ? null : packs.getFirst().summary().getBundleId(); page = 0; detailScroll = 0; }
            notice = snapshot.failures() == 0 ? Component.empty() : tr("unreadable", snapshot.failures()).copy().append(" ").append(snapshot.firstFailure());
            noticeColor = 0xFFFFB9A1;
            if (snapshot.settling()) { needsRefresh = true; refreshAfter = now() + 650; }
            loadDetails(); rebuildWidgets();
        }));
    }

    private void loadDetails() {
        long ticket = ++detailOperation, taskVisit = visit;
        selectedDetails = null; detailFailure = null; detailsLoading = false;
        var card = selected();
        if (card == null) { wrapDetails(); return; }
        if (card.details() != null) { selectedDetails = card.details(); wrapDetails(); return; }
        detailsLoading = true; wrapDetails();
        CompletableFuture.supplyAsync(() -> {
            try {
                var found = detailCache.get(card.summary().getBundleId());
                if (found == null) { found = exchange().describePack(card.summary().getBundleId()); detailCache.put(card.summary().getBundleId(), found); }
                return found;
            } catch (Exception error) { throw new CompletionException(error); }
        }, IO).whenComplete((details, failure) -> minecraft.execute(() -> {
            if (!attached || taskVisit != visit || ticket != detailOperation) return;
            detailsLoading = false;
            if (failure != null) detailFailure = tr("error", shortFailure(failure)); else selectedDetails = details;
            wrapDetails();
        }));
    }

    private void openDirectory() {
        runAction(tr("opening_folder"), () -> {
            Path directory = exchange().inboxDirectory(); Files.createDirectories(directory); Util.getPlatform().openPath(directory); return directory;
        }, ignored -> {});
    }
    private void createWorld() {
        var card = selected();
        if (card == null || minecraft.level != null || minecraft.getSingleplayerServer() != null) return;
        runAction(tr("preparing_creation"), () -> {
            if (card.filename() != null) {
                var imported = exchange().importPack(card.filename());
                if (!imported.getBundleId().equals(card.summary().getBundleId())) throw new IllegalStateException("World-pack file changed; select its refreshed entry");
            }
            return WorldsmithWorldCreationBridge.prepareCreationSelection(card.summary().getBundleId());
        }, selection -> {
            WorldsmithWorldCreationBridge.openForCreation(selection, this);
        });
    }

    private void loadVisibleIcons() {
        if (!attached || tooSmall) return;
        long epoch = iconEpoch, taskVisit = visit;
        for (int row = 0; row < Math.min(pageSize, MAX_ICON_CACHE); row++) {
            int index = page * pageSize + row; if (index >= packs.size()) break;
            var card = packs.get(index); String id = card.summary().getBundleId();
            if (pendingIcons.size() >= MAX_PENDING_ICONS) break;
            if (iconTextures.containsKey(id) || !pendingIcons.add(id)) continue;
            CompletableFuture.supplyAsync(() -> {
                if (epoch != iconEpoch) return null;
                try {
                    var details = card.details();
                    if (details == null) {
                        details = detailCache.get(id);
                        if (details == null) { details = exchange().describePack(id); detailCache.put(id, details); }
                    }
                    return details.getIcon() == null ? null : ResourcePackIconPreview.read(Path.of(card.summary().getPath()), details.getIcon());
                } catch (Exception error) { throw new CompletionException(error); }
            }, IO).whenComplete((pixels, failure) -> minecraft.execute(() -> {
                if (!attached || epoch != iconEpoch || taskVisit != visit) return;
                pendingIcons.remove(id);
                Identifier textureId = null;
                if (failure == null && pixels != null) {
                    NativeImage image = new NativeImage(pixels.getWidth(), pixels.getHeight(), false);
                    try {
                        int[] argb = pixels.getArgb();
                        for (int y = 0; y < pixels.getHeight(); y++) for (int x = 0; x < pixels.getWidth(); x++)
                            image.setPixel(x, y, argb[y * pixels.getWidth() + x]);
                        textureId = Identifier.fromNamespaceAndPath("worldsmith", "pack_preview/" + iconOwner + "/" + id);
                        minecraft.getTextureManager().register(textureId, new DynamicTexture(() -> "Worldsmith pack icon " + id, image));
                    } catch (RuntimeException error) {
                        image.close(); textureId = null;
                        Worldsmith.LOGGER.debug("World-pack preview upload failed for {}", id, error);
                    }
                } else if (failure != null) Worldsmith.LOGGER.debug("World-pack preview unavailable for {}", id, failure);
                iconTextures.put(id, textureId);
                while (iconTextures.size() > MAX_ICON_CACHE) {
                    var oldest = iconTextures.entrySet().iterator().next();
                    Identifier oldTexture = oldest.getValue(); iconTextures.remove(oldest.getKey());
                    if (oldTexture != null) minecraft.getTextureManager().release(oldTexture);
                }
                loadVisibleIcons();
            }));
        }
    }

    private void clearIcons() {
        iconEpoch++; pendingIcons.clear();
        for (Identifier icon : iconTextures.values()) if (icon != null) minecraft.getTextureManager().release(icon);
        iconTextures.clear();
    }
    @FunctionalInterface private interface Work<T> { T run() throws Exception; }
    private <T> void runAction(Component label, Work<T> work, Consumer<T> success) {
        if (actionBusy) return;
        actionBusy = true; notice = label; noticeColor = 0xFFD7D0B3;
        long taskVisit = visit, ticket = ++actionOperation; rebuildWidgets();
        CompletableFuture.supplyAsync(() -> {
            try { return work.run(); } catch (Exception failure) { throw new CompletionException(failure); }
        }, IO).whenComplete((result, failure) -> minecraft.execute(() -> {
            if (!attached || taskVisit != visit || ticket != actionOperation) return;
            actionBusy = false; notice = Component.empty();
            if (failure != null) showFailure(failure);
            else { try { success.accept(result); } catch (Exception error) { showFailure(error); } }
            if (minecraft.gui.screen() == this) rebuildWidgets();
        }));
    }
    private static String shortFailure(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        String text = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return text.length() > 512 ? text.substring(0, 512) + "…" : text;
    }
    private static String displayText(String text, int maximum) { return text.length() > maximum ? text.substring(0, maximum) + "…" : text; }
    private void showFailure(Throwable error) {
        notice = tr("error", shortFailure(error)); noticeColor = 0xFFFFB9A1;
        Worldsmith.LOGGER.warn("Worldsmith resource pack action failed", error);
    }
    private void wrapDetails() {
        if (font == null || detailWidth <= 0) return;
        var card = selected();
        if (card == null) { titleLines = List.of(); descriptionLines = List.of(); return; }
        var summary = selectedDetails == null ? card.summary() : selectedDetails.getSummary();
        titleLines = font.split(Component.literal(displayText(summary.getDisplayName(), 160)).withStyle(ChatFormatting.BOLD), detailWidth - 28);
        Component description = summary.getDescription().isBlank() ? tr("no_description") : Component.literal(displayText(summary.getDescription(), 4096));
        descriptionLines = font.split(description, detailWidth - 28);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.centeredText(font, title, width / 2, 12, 0xFFF0E7CD);
        if (tooSmall) {
            graphics.textWithWordWrap(font, tr("small_window"), 16, 42, Math.max(30, width - 32), 0xFFFFFFFF);
            super.extractRenderState(graphics, mouseX, mouseY, partialTick); return;
        }
        graphics.fill(12, BODY_TOP, 12 + listWidth, bodyBottom, 0xB51A1D22);
        graphics.fill(detailX, BODY_TOP, detailX + detailWidth, bodyBottom, 0xCD1B1F24);
        graphics.outline(12, BODY_TOP, listWidth, bodyBottom - BODY_TOP, 0xFF41464C);
        graphics.outline(detailX, BODY_TOP, detailWidth, bodyBottom - BODY_TOP, 0xFF41464C);
        if (packs.isEmpty()) {
            Component text = loading ? tr("loading") : tr("empty_library");
            var lines = font.split(text, listWidth - 20); int y = BODY_TOP + (bodyBottom - BODY_TOP - lines.size() * 12) / 2;
            for (var line : lines) { graphics.text(font, line, 12 + listWidth / 2 - font.width(line) / 2, y, 0xFFAEB6C0); y += 12; }
        }
        if (showPager) graphics.centeredText(font, (page + 1) + " / " + pages(), 12 + listWidth / 2, bodyBottom - 18, 0xFFAFB9C3);
        drawDetails(graphics);
        if (!notice.getString().isBlank()) {
            graphics.text(font, font.plainSubstrByWidth(notice.getString(), width - 24), 12, height - 41, noticeColor);
            if (mouseY >= height - 43 && mouseY < height - 28) graphics.setTooltipForNextFrame(notice, mouseX, mouseY);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }
    private void drawDetails(GuiGraphicsExtractor graphics) {
        var card = selected();
        if (card == null) {
            graphics.textWithWordWrap(font, tr("select_pack"), detailX + 14, BODY_TOP + 18, detailWidth - 28, 0xFFD6DCE2);
            graphics.textWithWordWrap(font, tr("empty_details"), detailX + 14, BODY_TOP + 42, detailWidth - 28, 0xFF929DA8);
            return;
        }
        int columns = detailWidth >= 230 ? 2 : 1;
        int rows = (COUNT_KEYS.size() + columns - 1) / columns;
        int titleHeight = titleLines.size() * 12, descriptionHeight = descriptionLines.size() * 12;
        int contentHeight = 24 + titleHeight + descriptionHeight + (selectedDetails == null ? 36 : 23 + rows * 30);
        int viewport = bodyBottom - BODY_TOP - 16;
        maximumDetailScroll = Math.max(0, contentHeight - viewport); detailScroll = Math.max(0, Math.min(detailScroll, maximumDetailScroll));
        graphics.enableScissor(detailX + 3, BODY_TOP + 3, detailX + detailWidth - 3, bodyBottom - 3);
        int y = BODY_TOP + 12 - detailScroll;
        for (var line : titleLines) { graphics.text(font, line, detailX + 14, y, 0xFFF2EBD9); y += 12; }
        y += 8;
        for (var line : descriptionLines) { graphics.text(font, line, detailX + 14, y, 0xFFC5CDD5); y += 12; }
        y += 14;
        if (selectedDetails == null) {
            graphics.textWithWordWrap(font, detailFailure != null ? detailFailure : tr("loading_details"), detailX + 14, y, detailWidth - 28,
                    detailFailure != null ? 0xFFFFB9A1 : 0xFF929DA8);
        } else {
            graphics.text(font, tr("contents"), detailX + 14, y, 0xFF9BAC9B); y += 16;
            int gap = 6, cellWidth = (detailWidth - 28 - (columns - 1) * gap) / columns;
            for (int i = 0; i < COUNT_KEYS.size(); i++) {
                String key = COUNT_KEYS.get(i); int x = detailX + 14 + (i % columns) * (cellWidth + gap), top = y + (i / columns) * 30;
                graphics.fill(x, top, x + cellWidth, top + 24, 0x9A30373D);
                graphics.text(font, tr("count." + key), x + 7, top + 8, 0xFFB6C0C9);
                String count = Integer.toString(selectedDetails.getCounts().getOrDefault(key, 0));
                graphics.text(font, count, x + cellWidth - 7 - font.width(count), top + 8, 0xFFF0EBD8);
            }
        }
        graphics.disableScissor();
        if (maximumDetailScroll > 0) {
            int track = bodyBottom - BODY_TOP - 8, thumb = Math.max(12, track * viewport / contentHeight);
            int top = BODY_TOP + 4 + (track - thumb) * detailScroll / maximumDetailScroll;
            graphics.fill(detailX + detailWidth - 5, top, detailX + detailWidth - 3, top + thumb, 0xFF84958A);
        }
    }

    private final class PackButton extends Button {
        private final boolean selected;
        private final String bundleId;
        private final List<FormattedCharSequence> lines;
        PackButton(int x, int y, int width, PackCard card) {
            super(x, y, width, CARD_HEIGHT, Component.literal(displayText(card.summary().getDisplayName(), 160)),
                    ignored -> WorldsmithResourcePackScreen.this.select(card.summary().getBundleId()), DEFAULT_NARRATION);
            selected = card.summary().getBundleId().equals(selectedId);
            active = !actionBusy;
            bundleId = card.summary().getBundleId();
            lines = font.split(getMessage(), Math.max(20, width - 38));
            setTooltip(Tooltip.create(getMessage()));
        }
        @Override public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            boolean highlighted = isHoveredOrFocused();
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), selected ? 0xEF35423A : highlighted ? 0xEF343A40 : 0xE4262B30);
            graphics.outline(getX(), getY(), getWidth(), getHeight(), selected || highlighted ? 0xFF91AF91 : 0xFF444C54);
            if (selected) graphics.fill(getX(), getY() + 1, getX() + 2, getY() + getHeight() - 1, 0xFFA8C599);
            Identifier icon = iconTextures.get(bundleId);
            if (icon == null) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, ICON, getX() + 5, getY() + 9, 24, 24);
            else graphics.blit(icon, getX() + 5, getY() + 9, getX() + 29, getY() + 33, 0, 1, 0, 1);
            int y = getY() + (getHeight() - Math.min(2, lines.size()) * 12) / 2;
            for (int i = 0; i < Math.min(2, lines.size()); i++) { graphics.text(font, lines.get(i), getX() + 32, y, selected ? 0xFFF0EAD8 : 0xFFD6DCE2); y += 12; }
        }
    }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!tooSmall && mouseY >= BODY_TOP && mouseY < bodyBottom) {
            if (mouseX >= detailX && mouseX < detailX + detailWidth) { detailScroll = Math.max(0, Math.min(maximumDetailScroll, detailScroll - (int)Math.round(scrollY * 24))); return true; }
            if (mouseX >= 12 && mouseX < 12 + listWidth && scrollY != 0) { page = Math.max(0, Math.min(pages() - 1, page + (scrollY > 0 ? -1 : 1))); rebuildWidgets(); return true; }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
}
