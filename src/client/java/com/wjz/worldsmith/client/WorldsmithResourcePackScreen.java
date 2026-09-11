package com.wjz.worldsmith.client;

import com.wjz.worldsmith.Worldsmith;
import com.wjz.worldsmith.core.mcp.ResourcePackExchange;
import com.wjz.worldsmith.core.mcp.ResourcePackInboxEntry;
import com.wjz.worldsmith.core.mcp.ResourcePackInboxListing;
import com.wjz.worldsmith.core.mcp.ResourcePackReceipt;
import com.wjz.worldsmith.core.mcp.ResourcePackSummary;
import com.wjz.worldsmith.mcp.WorldsmithMcpService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;

/** Native, optional-dependency-free archive library. Import/export never activates world content. */
public final class WorldsmithResourcePackScreen extends Screen {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "worldsmith-resource-pack-io"); thread.setDaemon(true); return thread;
    });
    private enum Tab { LIBRARY, INBOX }
    private final Screen parent;
    // ResourcePackExchange creates/verifies its directories, so even its constructor stays on IO.
    private ResourcePackExchange exchange;
    private String inboxPath = WorldsmithMcpService.packDirectory().toAbsolutePath().normalize().resolveSibling("resource-packs").resolve("inbox").toString();
    private List<ResourcePackSummary> packs = List.of();
    private List<ResourcePackInboxEntry> inbox = List.of();
    private boolean inboxTruncated;
    private String selectedPack;
    private String selectedFile;
    private ResourcePackReceipt inspected;
    private String inspectedFile;
    private String resultPath = "";
    private Tab tab = Tab.LIBRARY;
    private boolean busy;
    private boolean attached;
    private boolean needsRefresh = true;
    private long visit;
    private long operation;
    private Component notice = tr("boundary");
    private Component busyLabel = tr("loading");
    private int noticeColor = 0xFFB6C7DC;
    private int page;
    private int pageSize;
    private int detailScroll;
    private int maximumDetailScroll;
    private int listWidth;
    private int detailX;
    private int detailWidth;
    private int bodyBottom;
    private boolean tooSmall;
    private static final int BODY_TOP = 65;

    public WorldsmithResourcePackScreen(Screen parent) {
        super(tr("title")); this.parent = parent;
    }

    @Override public void added() { attached = true; visit++; needsRefresh = true; }
    @Override public void removed() { attached = false; visit++; }
    @Override public void tick() { if (needsRefresh && !busy && !tooSmall) { needsRefresh = false; refresh(false); } }

    @Override protected void init() {
        tooSmall = width < 320 || height < 240;
        if (tooSmall) {
            var back = addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
                .bounds(width / 2 - 50, height - 28, 100, 20).build());
            back.active = !busy; return;
        }
        int tabWidth = Math.min(108, (width - 126) / 2);
        var library = addRenderableWidget(Button.builder(tr("library"), button -> switchTab(Tab.LIBRARY)).bounds(12, 35, tabWidth, 20).build());
        var incoming = addRenderableWidget(Button.builder(tr("inbox"), button -> switchTab(Tab.INBOX)).bounds(18 + tabWidth, 35, tabWidth, 20).build());
        library.active = !busy && tab != Tab.LIBRARY; incoming.active = !busy && tab != Tab.INBOX;
        addButton("refresh", width - 94, 35, 82, () -> refresh(true), !busy);
        bodyBottom = height - 129;
        listWidth = Math.max(116, Math.min(220, width / 3)); detailX = listWidth + 24; detailWidth = width - detailX - 12;
        pageSize = Math.max(1, (bodyBottom - BODY_TOP - 21) / 24);
        int pages = pages(); page = Math.max(0, Math.min(page, pages - 1));
        for (int row = 0; row < pageSize; row++) {
            int index = page * pageSize + row; if (index >= count()) break;
            String name = tab == Tab.LIBRARY ? packs.get(index).getDisplayName() : inbox.get(index).getFilename();
            String id = tab == Tab.LIBRARY ? packs.get(index).getBundleId() : name;
            boolean selected = id.equals(tab == Tab.LIBRARY ? selectedPack : selectedFile);
            Component label = Component.literal((selected ? "> " : "") + font.plainSubstrByWidth(name, listWidth - 28));
            var entry = addRenderableWidget(Button.builder(label, button -> select(id))
                .bounds(16, BODY_TOP + 2 + row * 24, listWidth - 8, 22).tooltip(Tooltip.create(Component.literal(name))).build());
            entry.active = !busy;
        }
        var previous = addRenderableWidget(Button.builder(Component.literal("<"), button -> { page--; rebuildWidgets(); })
            .bounds(16, bodyBottom - 19, 24, 18).tooltip(Tooltip.create(tr("previous"))).build());
        var next = addRenderableWidget(Button.builder(Component.literal(">"), button -> { page++; rebuildWidgets(); })
            .bounds(12 + listWidth - 28, bodyBottom - 19, 24, 18).tooltip(Tooltip.create(tr("next"))).build());
        previous.active = !busy && page > 0; next.active = !busy && page + 1 < pages;
        int actionWidth = (width - 36) / 3;
        if (tab == Tab.INBOX) {
            addButton("inspect", 12, height - 61, actionWidth, this::inspectSelected, !busy && selectedInbox() != null);
            addButton("import", 18 + actionWidth, height - 61, actionWidth, this::importSelected, !busy && selectedInbox() != null);
        } else {
            addButton("export", 12, height - 61, actionWidth, this::exportSelected, !busy && selected() != null);
            addButton("create", 18 + actionWidth, height - 61, actionWidth, this::createWorld,
                !busy && selected() != null && minecraft.level == null && minecraft.getSingleplayerServer() == null);
        }
        addButton("copy_path", 24 + 2 * actionWidth, height - 61, actionWidth, () -> {
            String path = shownPath(); if (!path.isBlank()) { minecraft.keyboardHandler.setClipboard(path); setNotice(tr("path_copied"), false); }
        }, !busy && !shownPath().isBlank());
        addButton("open_inbox", 12, height - 33, actionWidth, () -> openDirectory(true), !busy);
        addButton("open_exports", 18 + actionWidth, height - 33, actionWidth, () -> openDirectory(false), !busy);
        var back = addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
            .bounds(24 + 2 * actionWidth, height - 33, actionWidth, 20).build());
        back.active = !busy;
    }

    private void addButton(String key, int x, int y, int buttonWidth, Runnable action, boolean active) {
        var button = addRenderableWidget(Button.builder(tr(key), ignored -> action.run()).bounds(x, y, buttonWidth, 20)
            .tooltip(Tooltip.create(tr(key + ".hint"))).build());
        button.active = active;
    }
    private static Component tr(String key, Object... args) { return Component.translatable("worldsmith.packs." + key, args); }
    private void switchTab(Tab target) { tab = target; page = 0; detailScroll = 0; resultPath = ""; rebuildWidgets(); }
    private void select(String id) {
        if (tab == Tab.LIBRARY) selectedPack = id;
        else { selectedFile = id; if (!id.equals(inspectedFile)) { inspected = null; inspectedFile = null; } }
        detailScroll = 0; resultPath = ""; rebuildWidgets();
    }
    private int count() { return tab == Tab.LIBRARY ? packs.size() : inbox.size(); }
    private int pages() { return Math.max(1, (count() + Math.max(1, pageSize) - 1) / Math.max(1, pageSize)); }
    private ResourcePackSummary selected() { return packs.stream().filter(pack -> pack.getBundleId().equals(selectedPack)).findFirst().orElse(null); }
    private ResourcePackInboxEntry selectedInbox() { return inbox.stream().filter(file -> file.getFilename().equals(selectedFile)).findFirst().orElse(null); }

    private record LibrarySnapshot(List<ResourcePackSummary> packs, ResourcePackInboxListing inbox) {}
    /** Only used by the single IO executor; render code consumes plain snapshot DTOs. */
    private ResourcePackExchange exchange() {
        if (exchange == null) exchange = new ResourcePackExchange(WorldsmithMcpService.packDirectory());
        return exchange;
    }
    private void refresh(boolean explicit) {
        needsRefresh = false;
        runTask(tr("loading"), () -> new LibrarySnapshot(exchange().listPacks(), exchange().listInbox()), snapshot -> {
            packs = List.copyOf(snapshot.packs()); inbox = List.copyOf(snapshot.inbox().getEntries()); inboxTruncated = snapshot.inbox().getTruncated();
            inboxPath = snapshot.inbox().getDirectory();
            if (selected() == null) selectedPack = packs.isEmpty() ? null : packs.getFirst().getBundleId();
            if (selectedInbox() == null) { selectedFile = inbox.isEmpty() ? null : inbox.getFirst().getFilename(); inspected = null; inspectedFile = null; }
            if (tab == Tab.LIBRARY && selectedPack != null) {
                for (int i = 0; i < packs.size(); i++) if (packs.get(i).getBundleId().equals(selectedPack)) { page = i / Math.max(1, pageSize); break; }
            }
            if (explicit) setNotice(tr("refreshed", packs.size(), inbox.size()), false);
        });
    }
    private void inspectSelected() {
        var file = selectedInbox(); if (file == null) return; String filename = file.getFilename();
        runTask(tr("checking"), () -> exchange().inspect(filename), receipt -> {
            inspected = receipt; inspectedFile = filename; resultPath = receipt.getPath();
            setNotice(tr("inspected", receipt.getDisplayName()), false); detailScroll = 0;
        });
    }
    private void importSelected() {
        var file = selectedInbox(); if (file == null) return;
        runTask(tr("importing"), () -> exchange().importPack(file.getFilename()), receipt -> {
            selectedPack = receipt.getBundleId(); tab = Tab.LIBRARY; page = 0; detailScroll = 0;
            resultPath = receipt.getSavedPackPath() == null ? receipt.getPath() : receipt.getSavedPackPath();
            setNotice(tr(receipt.getReusedExisting() ? "imported_existing" : "imported", receipt.getDisplayName()), false);
            needsRefresh = true;
        });
    }
    private void exportSelected() {
        var pack = selected(); if (pack == null) return;
        runTask(tr("exporting"), () -> exchange().exportPack(pack.getBundleId()), receipt -> {
            resultPath = receipt.getPath(); setNotice(tr("exported", receipt.getFilename()), false);
        });
    }
    private void openDirectory(boolean incoming) {
        runTask(tr("opening_folder"), () -> {
            Path directory = incoming ? exchange().inboxDirectory() : exchange().exportsDirectory();
            Files.createDirectories(directory); Util.getPlatform().openPath(directory); return directory.toAbsolutePath().toString();
        }, path -> {
            resultPath = path; setNotice(tr("folder_requested"), false);
        });
    }
    private void createWorld() {
        var pack = selected(); if (pack == null || minecraft.level != null || minecraft.getSingleplayerServer() != null) return;
        runTask(tr("preparing_creation"), () -> WorldsmithWorldCreationBridge.prepareCreationSelection(pack.getBundleId()), selection -> {
            // Both checks are repeated inside the bridge. No worker can change the active selection.
            WorldsmithWorldCreationBridge.selectForCreation(selection, this);
            CreateWorldScreen.openFresh(minecraft, () -> minecraft.gui.setScreen(this));
        });
    }
    @FunctionalInterface private interface Work<T> { T run() throws Exception; }
    private <T> void runTask(Component label, Work<T> work, Consumer<T> success) {
        if (busy) return;
        busy = true; busyLabel = label; long taskVisit = visit, taskOperation = ++operation; rebuildWidgets();
        CompletableFuture.supplyAsync(() -> {
            try { return work.run(); } catch (Exception failure) { throw new CompletionException(failure); }
        }, IO).whenComplete((result, failure) -> minecraft.execute(() -> {
            if (operation != taskOperation) return;
            busy = false;
            if (!attached || visit != taskVisit || minecraft.gui.screen() != this) return;
            if (failure != null) showFailure(failure);
            else { try { success.accept(result); } catch (Exception error) { showFailure(error); } }
            if (minecraft.gui.screen() == this) rebuildWidgets();
        }));
    }
    private void showFailure(Throwable failure) {
        Throwable cause = failure; while (cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        // Archive diagnostics can include untrusted names; bound repeated font layout, not the log.
        if (message.length() > 4096) message = message.substring(0, 4096) + "\u2026";
        setNotice(tr("error", message), true); Worldsmith.LOGGER.warn("Worldsmith resource pack action failed", failure);
    }
    private void setNotice(Component text, boolean error) { notice = text; noticeColor = error ? 0xFFFFA6A6 : 0xFFA5DDB4; }
    private String shownPath() {
        if (!resultPath.isBlank()) return resultPath;
        if (tab == Tab.INBOX) return inboxPath;
        return selected() == null ? "" : selected().getPath();
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.centeredText(font, title, width / 2, 8, 0xFFF0E7CD);
        if (tooSmall) {
            graphics.textWithWordWrap(font, tr("small_window"), 16, 42, Math.max(30, width - 32), 0xFFFFFFFF);
            super.extractRenderState(graphics, mouseX, mouseY, partialTick); return;
        }
        graphics.centeredText(font, font.plainSubstrByWidth(tr("version", SharedConstants.getCurrentVersion().name()).getString(), width - 24), width / 2, 22, 0xFFBBC8D8);
        graphics.fill(12, BODY_TOP, 12 + listWidth, bodyBottom, 0xD0202A38);
        graphics.fill(detailX, BODY_TOP, detailX + detailWidth, bodyBottom, 0xDB16212E);
        graphics.centeredText(font, (page + 1) + " / " + pages(), 12 + listWidth / 2, bodyBottom - 14, 0xFFCFD5DF);
        drawDetails(graphics);
        Component feedback = busy ? busyLabel.copy().append(" ").append(tr("busy_hint")) : notice;
        drawLimited(graphics, feedback, 12, height - 119, width - 24, 3, busy ? 0xFFF0D596 : noticeColor);
        if (mouseY >= height - 120 && mouseY < height - 88) graphics.setTooltipForNextFrame(feedback, mouseX, mouseY);
        String path = shownPath();
        graphics.text(font, font.plainSubstrByWidth(path, width - 24), 12, height - 82, 0xFFB5C7DF);
        if (!path.isBlank() && mouseY >= height - 86 && mouseY < height - 67) graphics.setTooltipForNextFrame(Component.literal(path), mouseX, mouseY);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }
    private record TextLine(FormattedCharSequence text, int color) {}
    private void drawDetails(GuiGraphicsExtractor graphics) {
        List<TextLine> lines = new ArrayList<>();
        if (tab == Tab.LIBRARY) {
            var pack = selected();
            if (pack == null) add(lines, tr("empty_library"), 0xFFCCD6E5);
            else {
                add(lines, Component.literal(pack.getDisplayName()), 0xFFF1D99D);
                add(lines, tr("bundle_id", pack.getBundleId()), 0xFFB0C5DD);
                add(lines, tr("pack_details", pack.getFormatVersion(), pack.getAssetCount()), 0xFFB0C5DD);
                add(lines, Component.literal(pack.getDescription()), 0xFFDEE5EF);
                add(lines, tr("create.hint"), 0xFFDFC992);
            }
            add(lines, tr("library_limit"), 0xFF93A9C0);
        } else {
            var file = selectedInbox();
            if (file == null) add(lines, tr("empty_inbox"), 0xFFCCD6E5);
            else {
                add(lines, Component.literal(file.getFilename()), 0xFFF1D99D);
                add(lines, tr("file_size", file.getByteLength()), 0xFFB0C5DD);
                if (inspected != null && file.getFilename().equals(inspectedFile)) {
                    add(lines, Component.literal(inspected.getDisplayName()), 0xFFDEE5EF);
                    add(lines, tr("archive_version", inspected.getArchiveInfo().getArchiveVersion(), inspected.getFormatVersion()), 0xFFB0C5DD);
                    add(lines, tr("bundle_id", inspected.getBundleId()), 0xFFB0C5DD);
                    add(lines, Component.literal(inspected.getDescription()), 0xFFDEE5EF);
                } else add(lines, tr("inspect.hint"), 0xFFB0C5DD);
            }
            add(lines, tr("boundary"), 0xFFDFC992);
            if (inboxTruncated) add(lines, tr("inbox_truncated"), 0xFFFFCC7A);
        }
        int viewport = bodyBottom - BODY_TOP - 12;
        maximumDetailScroll = Math.max(0, lines.size() * 12 - viewport); detailScroll = Math.max(0, Math.min(detailScroll, maximumDetailScroll));
        graphics.enableScissor(detailX + 3, BODY_TOP + 3, detailX + detailWidth - 3, bodyBottom - 3);
        int y = BODY_TOP + 6 - detailScroll;
        for (var line : lines) { if (y + 10 >= BODY_TOP && y < bodyBottom) graphics.text(font, line.text(), detailX + 8, y, line.color()); y += 12; }
        graphics.disableScissor();
        if (maximumDetailScroll > 0) {
            int track = bodyBottom - BODY_TOP - 8, thumb = Math.max(8, track * viewport / (lines.size() * 12));
            int top = BODY_TOP + 4 + (track - thumb) * detailScroll / maximumDetailScroll;
            graphics.fill(detailX + detailWidth - 5, top, detailX + detailWidth - 3, top + thumb, 0xFF7E93AD);
        }
    }
    private void add(List<TextLine> lines, Component text, int color) {
        for (var line : font.split(text, Math.max(20, detailWidth - 21))) lines.add(new TextLine(line, color));
    }
    private void drawLimited(GuiGraphicsExtractor graphics, Component text, int x, int y, int textWidth, int maximum, int color) {
        var lines = font.split(text, textWidth); for (int i = 0; i < Math.min(lines.size(), maximum); i++) graphics.text(font, lines.get(i), x, y + i * 10, color);
    }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!tooSmall && !busy && mouseY >= BODY_TOP && mouseY < bodyBottom) {
            if (mouseX >= detailX && mouseX < detailX + detailWidth) { detailScroll = Math.max(0, Math.min(maximumDetailScroll, detailScroll - (int)Math.round(scrollY * 24))); return true; }
            if (mouseX >= 12 && mouseX < 12 + listWidth && scrollY != 0) {
                page = Math.max(0, Math.min(pages() - 1, page + (scrollY > 0 ? -1 : 1))); rebuildWidgets(); return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
    @Override public boolean shouldCloseOnEsc() { return !busy; }
    @Override public void onClose() { if (!busy) minecraft.gui.setScreen(parent); }
}
